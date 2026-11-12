package com.overseas.dorm.agent.stream;

import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.enums.StreamState;
import com.overseas.dorm.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.overseas.dorm.agent.config.AgentProperties;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 流式事件缓冲与广播 —— 「断点续打」的地基。
 *
 * <h3>要解决的问题</h3>
 * SSE 是"服务器推、客户端收"的单向连接。网络一抖、手机切后台、
 * 用户刷新页面，连接就断了，而此刻模型可能已经生成了半句话。
 * 传统做法是"断了就重新问一遍"—— 既浪费 token，又会出现内容重复或丢失。
 *
 * <h3>方案：每个事件都有序号，写进 Redis Stream</h3>
 * <pre>
 *   生成方：每个 token/事件 → 分配单调递增 seq → XADD 到 dorm:stream:{messageId}
 *                         → 同时 PUBLISH 到 dorm:stream:live:{messageId}
 *   消费方：正常时从 Pub/Sub 实时收；重连时先 XRANGE 拉取 seq &gt; lastEventId 的历史，
 *          再订阅 Pub/Sub 接续，并按 seq 去重。
 * </pre>
 *
 * <h3>为什么 Stream + Pub/Sub 两个都用，而不是只用其中一个</h3>
 * <table>
 *   <tr><th>只用 Pub/Sub</th>
 *       <td>断线期间的事件<b>永久丢失</b>（Pub/Sub 不存历史），无法续传</td></tr>
 *   <tr><th>只用 Stream</th>
 *       <td>需要轮询（XREAD BLOCK 会占用连接），实时性差，且每轮对话占用一个阻塞连接</td></tr>
 *   <tr><th>两者结合</th>
 *       <td>Stream 保历史（可靠），Pub/Sub 保实时（低延迟），<b>seq 保证两者交接处不重不漏</b></td></tr>
 * </table>
 *
 * <h3>内存保护</h3>
 * Stream 用 {@code MAXLEN ~ N} 做近似裁剪：单条流最多保留 N 条事件。
 * 不裁剪的话，一个生成 4000 token 的对话会在 Redis 里留下几千条记录，
 * 上千并发流就是百万级 key 的堆积 —— 这是"高并发 SSE 导致内存溢出"的典型成因。
 * 同时给 key 设置 TTL：对话结束后保留一段可重连窗口，之后自动清理。
 *
 * <h3>多实例部署</h3>
 * 因为缓冲在 Redis 而不是 JVM 内存里，用户在实例 A 发起对话、
 * 断线后重连落到实例 B，依然能从 Stream 里读到完整历史并继续接收 ——
 * 无需粘性会话（sticky session）。这是"无状态水平扩展"的关键前提。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStreamBuffer {

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;
    private final AgentProperties properties;

    /**
     * 本实例内每个 messageId 的共享订阅（引用计数）。
     *
     * <p>存在的唯一目的：保证「一个频道一个监听器」。
     * 详见 {@link #subscribe(Long, Consumer)} 的说明 —— 多条连接各订阅一次，
     * 会让监听容器在退订时踩到已失效的共享订阅连接（实测 HTTP 500）。
     */
    private final Map<Long, SharedSubscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * 串行化对监听容器的全部变更（注册 / 退订 / 重建）。
     *
     * <p>容器的 add 与 remove 一旦交错，就可能出现"A 线程正在注册、B 线程把订阅连接
     * 关掉"的竞态；容器内部虽有锁，但它保护不了"我们先读状态、容器后变更"这种跨调用
     * 的窗口。用应用层的一把锁把区间收口，代价是注册/退订变成串行 ——
     * 这只发生在"某条流的第一条连接接入 / 最后一条连接离开"的瞬间，量级是每秒几次。
     */
    private final ReentrantLock listenerLock = new ReentrantLock();

    /** 单条流的近似最大长度（启动时按配置生效）。 */
    private int maxLength = 4000;
    private Duration ttl = Duration.ofMinutes(30);
    /**
     * 并发计数键的存活时间。
     *
     * <p>这里刻意做成"比单轮最长耗时略长"而不是拍一个 10 分钟：
     * 如果进程在生成中途被强杀（发布、扩缩容、OOM），计数就来不及递减；
     * TTL 太长会让这些用户在一段时间内持续被自己的"幽灵并发"挡住
     * （表现为"过一会儿就再也问不了助手"）。TTL 取
     * 生成超时 + 60 秒，可以在最坏情况下快速自愈。
     */
    private Duration counterTtl = Duration.ofMinutes(3);

    /**
     * 从配置读取裁剪长度与 TTL。
     *
     * <p>放在自身的 {@code @PostConstruct} 而不是外部 Config 类里，
     * 是为了避免"配置类注入 Buffer、Buffer 又注入配置类定义的监听容器"
     * 造成的循环依赖 —— 循环依赖在启动时才暴露，而且报错信息很难指向根因。
     */
    @jakarta.annotation.PostConstruct
    public void initFromProperties() {
        AgentProperties.Stream stream = properties.getStream();
        this.maxLength = Math.max(stream.getBufferMaxLength(), 100);
        this.ttl = Duration.ofSeconds(Math.max(stream.getBufferTtlSeconds(), 60));
        this.counterTtl = Duration.ofSeconds(
                Math.max(stream.getGenerationTimeoutSeconds(), 30) + 60L);
    }

    /** 事件载荷。 */
    public record StreamEvent(long seq, String event, String data) {

        /** 渲染为 SSE 帧。 */
        public String toSseFrame() {
            return "id:" + seq + "\n" + "event:" + event + "\n" + "data:" + data + "\n\n";
        }
    }

    /**
     * 发布一个事件。
     *
     * @return 分配到的序号
     */
    public long publish(Long messageId, long seq, String event, Object payload) {
        String data = payload instanceof String s ? s : JsonUtil.toJson(payload);
        String json = JsonUtil.toJson(Map.of("seq", seq, "event", event, "data", data == null ? "" : data));

        String bufferKey = RedisKeys.streamBuffer(messageId);
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("seq", String.valueOf(seq));
            fields.put("event", event);
            fields.put("data", data == null ? "" : data);
            redis.opsForStream().add(bufferKey, fields);
            // 近似裁剪 + 续期：保证长对话不会无限增长。
            //
            // 不必每帧都做：XADD 本身是必须的，PUBLISH 是必须的，而 TRIM/EXPIRE
            // 只是后台维护。每 20 帧做一次，把每 token 的 Redis 往返从 4 次降到 2 次
            // （seq=1 时必做，保证短流也有 TTL）。
            if (seq <= 1 || seq % 20 == 0) {
                redis.opsForStream().trim(bufferKey, maxLength, true);
                redis.expire(bufferKey, ttl);
            }
        } catch (Exception e) {
            log.warn("[SSE] 写入流缓冲失败 messageId={}: {}", messageId, e.getMessage());
        }

        // 实时广播。
        // 只走 Redis Pub/Sub，不做"本实例内直接回调"的优化 ——
        // 那会导致同一事件被投递两次（本地直投一次 + Pub/Sub 回声一次），
        // 前端就会出现重复字符。省下的一次往返（亚毫秒级）远不值得冒这个风险。
        try {
            redis.convertAndSend(RedisKeys.streamLiveChannel(messageId), json);
        } catch (Exception e) {
            log.warn("[SSE] 广播事件失败 messageId={}: {}", messageId, e.getMessage());
        }
        return seq;
    }

    /**
     * 读取指定序号之后的历史事件。
     *
     * @param afterSeq 客户端已收到的最大序号（0 表示从头拉）
     */
    public List<StreamEvent> readAfter(Long messageId, long afterSeq, int count) {
        String bufferKey = RedisKeys.streamBuffer(messageId);
        List<StreamEvent> events = new ArrayList<>();
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                    bufferKey, org.springframework.data.domain.Range.unbounded());
            if (records == null) {
                return events;
            }
            for (MapRecord<String, Object, Object> record : records) {
                Map<Object, Object> value = record.getValue();
                long seq = parseLong(value.get("seq"), events.size());
                if (seq <= afterSeq) {
                    continue;
                }
                events.add(new StreamEvent(seq, String.valueOf(value.get("event")),
                        String.valueOf(value.get("data"))));
                if (events.size() >= count) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[SSE] 读取流缓冲失败 messageId={}: {}", messageId, e.getMessage());
        }
        return events;
    }

    /** 当前流已写到哪个序号。 */
    public long lastSeq(Long messageId) {
        String bufferKey = RedisKeys.streamBuffer(messageId);
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                    bufferKey, org.springframework.data.domain.Range.unbounded());
            if (records == null || records.isEmpty()) {
                return 0L;
            }
            Map<Object, Object> last = records.get(records.size() - 1).getValue();
            return parseLong(last.get("seq"), 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 记录本轮的流状态。
     *
     * <p>用 {@link StreamState} 枚举而不是字符串：这个值决定"重连时该补历史还是接实时流"，
     * 而且同时被三处使用（Redis 读写、终态判定、审计）。此前三处各写各的字符串字面量，
     * 一旦有人把 {@code DONE} 写成 {@code COMPLETED}，后果是<b>重连永远认为还在生成中</b>，
     * 客户端会一直等到超时 —— 而日志里没有任何报错。
     */
    public void setState(Long messageId, StreamState state) {
        try {
            redis.opsForValue().set(RedisKeys.streamState(messageId), state.name(), ttl);
        } catch (Exception e) {
            log.debug("[SSE] 写入流状态失败：{}", e.getMessage());
        }
    }

    /** 读取本轮流状态；键不存在（TTL 已过期）时返回 {@link StreamState#UNKNOWN}。 */
    public StreamState state(Long messageId) {
        try {
            return StreamState.of(redis.opsForValue().get(RedisKeys.streamState(messageId)));
        } catch (Exception e) {
            return StreamState.UNKNOWN;
        }
    }

    /** 是否终态（终态意味着不会再产生新事件，重连补完历史即可关闭连接）。 */
    public boolean isTerminal(StreamState state) {
        return state != null && state.terminal();
    }

    /**
     * 某个 SSE 事件名是否代表"这一轮结束了"。
     *
     * <p>刻意与 {@link #isTerminal(StreamState)} 分开而不是重载同名方法：
     * 两者判断的是不同的东西 —— 一个是<b>事件</b>（DONE/ERROR/BLOCKED/INTERRUPTED），
     * 一个是<b>流状态</b>（含 GENERATING）。同名重载会让调用方很容易传错参数，
     * 而这两者传错的后果（重连时提前关闭连接）非常隐蔽。
     */
    public boolean isTerminalEvent(String eventName) {
        if (eventName == null) {
            return false;
        }
        return switch (eventName) {
            case "DONE", "ERROR", "BLOCKED", "INTERRUPTED" -> true;
            default -> false;
        };
    }

    /** 请求打断：生成方在每次推送前检查这个标记。 */
    public void markCancelled(Long messageId) {
        try {
            redis.opsForValue().set(RedisKeys.streamCancel(messageId), "1", Duration.ofMinutes(10));
        } catch (Exception e) {
            log.debug("[SSE] 写入取消标记失败：{}", e.getMessage());
        }
    }

    public boolean isCancelled(Long messageId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(RedisKeys.streamCancel(messageId)));
        } catch (Exception e) {
            return false;
        }
    }

    /** 清理缓冲（正常结束后可选调用；默认保留以便短时间内重连查看）。 */
    public void clear(Long messageId) {
        try {
            redis.delete(RedisKeys.streamBuffer(messageId));
            redis.delete(RedisKeys.streamState(messageId));
            redis.delete(RedisKeys.streamCancel(messageId));
        } catch (Exception e) {
            log.debug("[SSE] 清理流缓冲失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 本实例内订阅

    /**
     * 订阅某条流的实时事件（**每个 messageId 在本实例内共享一份监听器，引用计数管理**）。
     *
     * <p>统一走 Redis Pub/Sub，因此无论是本实例还是其它实例产生的 token 都能收到。
     * 返回的 {@link AutoCloseable} 必须在连接结束时关闭，
     * 否则会造成监听器泄漏 —— 每个泄漏都是对 Redis 连接与内存的持续占用，
     * 在高并发下会逐步把服务拖垮。
     *
     * <h3>为什么一个 messageId 只能注册一个监听器（H3/H4 的核心）</h3>
     * 原实现是"每条连接各订阅一次、关闭即退订"。当同一个 messageId 上同时存在多条
     * 连接（断线续传 + 正在生成的原连接，或多条并发续传）时，会产生两类问题：
     * <ol>
     *   <li><b>订阅生命周期互相破坏</b>：某条连接关闭时执行的退订动作，会动到
     *       同一 messageId 上其它连接共用的那份 Redis 订阅；
     *       {@code RedisMessageListenerContainer} 的注册/退订会操作同一条共享的
     *       Redis 订阅连接，一旦"最后一个频道退订"先把连接关掉，随后的注册就会踩到
     *       已失效的订阅，实测表现为
     *       {@code RedisInvalidSubscriptionException: Subscription has been
     *       unsubscribed and cannot be used anymore}（HTTP 500）。</li>
     *   <li><b>注册/退订风暴</b>：N 条连接 = N 次 addMessageListener / removeMessageListener，
     *       把上述竞态的窗口放大了 N 倍。</li>
     * </ol>
     * 改成"每个 messageId 一份共享订阅 + 引用计数，最后一条连接关闭才真正退订"后，
     * 容器看到的永远是「一个频道一个监听器」，与单连接时代的行为一致。
     *
     * <h3>并发安全</h3>
     * 创建/注册/退订都在 {@link SharedSubscription} 自身的监视器下完成，
     * 并保证「注销最后一个订阅者」与「从注册表移除」是同一个原子步骤 ——
     * 否则新订阅者可能拿到一份刚被标记回收的共享订阅，造成监听器泄漏或
     * 同一频道出现两个监听器（正是要避免的情形）。
     */
    public AutoCloseable subscribe(Long messageId, Consumer<StreamEvent> consumer) {
        SharedSubscription shared = acquire(messageId, consumer);
        final AtomicBoolean closed = new AtomicBoolean(false);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                release(messageId, shared, consumer);
            }
        };
    }

    private SharedSubscription acquire(Long messageId, Consumer<StreamEvent> consumer) {
        while (true) {
            SharedSubscription shared = subscriptions.computeIfAbsent(messageId, SharedSubscription::new);
            synchronized (shared) {
                if (shared.retired) {
                    // 刚好被另一个线程回收：不能复用已退订的那份，换一份新的重试
                    continue;
                }
                if (!shared.registered) {
                    register(shared);
                    shared.registered = true;
                }
                shared.consumers.add(consumer);
                return shared;
            }
        }
    }

    private void release(Long messageId, SharedSubscription shared, Consumer<StreamEvent> consumer) {
        synchronized (shared) {
            if (!shared.consumers.remove(consumer) || !shared.consumers.isEmpty()) {
                // 还有其它连接在接收这条流：只退出自己，不碰共享订阅
                return;
            }
            shared.retired = true;
            if (shared.registered) {
                shared.registered = false;
                unregister(shared);
            }
            // 与"标记回收"同一个原子步骤，避免新订阅者拿到已回收的共享订阅
            subscriptions.remove(messageId, shared);
        }
    }

    /**
     * 注册共享监听器。
     *
     * <p>所有对监听容器的**变更**（注册/退订/重建）都在同一把锁下串行执行：
     * 让容器的 add 与 remove 交错，正是上面那类"踩到已失效订阅"的温床。
     *
     * <p>注册失败时做一次**自愈**：监听容器的全部订阅共享同一条 Redis 订阅连接，
     * 一旦它被判定失效，后续注册都会抛 {@code RedisInvalidSubscriptionException}，
     * 而该实例将再也收不到任何实时事件（客户端只能拿到历史然后干等）。
     * {@code stop()} 会关闭旧连接并把订阅状态复位，{@code start()} 会按容器内部
     * 的 channelMapping 把**包括本条在内的所有频道**重新订阅一遍。
     */
    private void register(SharedSubscription shared) {
        listenerLock.lock();
        try {
            ChannelTopic topic = new ChannelTopic(RedisKeys.streamLiveChannel(shared.messageId));
            MessageListener listener = (message, pattern) -> dispatch(shared, message);
            shared.topic = topic;
            shared.listener = listener;
            try {
                listenerContainer.addMessageListener(listener, topic);
            } catch (RuntimeException e) {
                log.warn("[SSE] 注册实时监听失败 messageId={}（{}），重建监听容器后重试",
                        shared.messageId, e.toString());
                rebuildListenerContainer();
                try {
                    listenerContainer.addMessageListener(listener, topic);
                } catch (RuntimeException retry) {
                    // 重建时容器已按 channelMapping 重新订阅了本条频道（addListener 先写映射
                    // 再订阅），因此这里的失败不再致命；但必须如实记录，
                    // 避免出现"看起来在接收、其实收不到"的静默失效。
                    log.error("[SSE] 重建后仍无法注册实时监听 messageId={}：{}",
                            shared.messageId, retry.toString());
                }
            }
        } finally {
            listenerLock.unlock();
        }
    }

    private void unregister(SharedSubscription shared) {
        listenerLock.lock();
        try {
            if (shared.listener == null || shared.topic == null) {
                return;
            }
            listenerContainer.removeMessageListener(shared.listener, shared.topic);
            shared.listener = null;
            shared.topic = null;
        } catch (RuntimeException e) {
            log.debug("[SSE] 移除监听器失败：{}", e.toString());
        } finally {
            listenerLock.unlock();
        }
    }

    /**
     * 重建监听容器的订阅连接（自愈）。
     *
     * <p>只允许在持有 {@link #listenerLock} 时调用 —— 它是"注册失败之后的补救动作"，
     * 必须与其它注册/退订互斥，否则会出现"刚重建好又被别人拆掉"的振荡。
     */
    private void rebuildListenerContainer() {
        try {
            listenerContainer.stop();
            listenerContainer.start();
            log.info("[SSE] 监听容器已重建（按当前频道表重新订阅全部 {} 条流）",
                    subscriptions.size());
        } catch (Exception e) {
            log.error("[SSE] 重建监听容器失败：{}", e.toString());
        }
    }

    /** 把一条实时事件分发给该 messageId 上的全部本地订阅者。 */
    private void dispatch(SharedSubscription shared, org.springframework.data.redis.connection.Message message) {
        StreamEvent event = parseEvent(new String(message.getBody(), StandardCharsets.UTF_8));
        if (event == null) {
            return;
        }
        for (Consumer<StreamEvent> consumer : shared.consumers) {
            try {
                consumer.accept(event);
            } catch (Exception e) {
                log.debug("[SSE] 订阅者回调异常：{}", e.getMessage());
            }
        }
    }

    /**
     * 一个 messageId 在本实例内的共享订阅。
     *
     * <p>除 {@link #consumers}（并发集合，供监听器回调无锁遍历）外，
     * 其余字段只在持有本对象监视器时读写。
     */
    private static final class SharedSubscription {

        private final Long messageId;
        private final Set<Consumer<StreamEvent>> consumers = ConcurrentHashMap.newKeySet();
        private MessageListener listener;
        private ChannelTopic topic;
        private boolean registered;
        private boolean retired;

        private SharedSubscription(Long messageId) {
            this.messageId = messageId;
        }
    }

    /** 解析 Pub/Sub 收到的 JSON 事件。 */
    private StreamEvent parseEvent(String body) {
        try {
            Map<String, Object> map = JsonUtil.parseMap(body);
            Object seq = map.get("seq");
            long seqValue = seq instanceof Number n ? n.longValue()
                    : Long.parseLong(String.valueOf(seq));
            return new StreamEvent(seqValue, String.valueOf(map.get("event")),
                    String.valueOf(map.get("data")));
        } catch (Exception e) {
            return null;
        }
    }


    // ------------------------------------------------------------------ 订阅者计数（跨实例）

    /**
     * 登记"有客户端正在接收这条流"，并刷新存活时间。
     *
     * <h3>为什么这个计数必须放在 Redis 而不是 JVM 内存</h3>
     * 生成方需要判断"还有没有人在接收这条流"：如果没人接收，就应该及时中止生成
     * （避免为一个已经离开的用户持续烧模型额度）。这个判断在单实例下用一个
     * 内存计数器就够了，但多实例部署时会失效：
     *
     * <pre>
     *   客户端在实例 A 建立连接 → 断网 → 重连落到实例 B → B 开始接收
     *   实例 A 的内存计数仍然是 0（它不知道 B 那边有人）
     *   → A 的孤儿看门狗误判"无人接收"→ 30 秒后中止生成
     *   → 用户明明连着，回答却被截断
     * </pre>
     *
     * 放到 Redis 后，A 能通过共享计数看到 B 的存在，跨实例续传才真正成立。
     * 这也和本项目其他并发控制（限流、并发上限、分布式锁）保持同一套原则：
     * <b>凡是"全局约束"，状态就不能放在单个实例的内存里。</b>
     *
     * <p>每个订阅者单独占一个键（而不是一个共享计数器 + INCR/DECR），
     * 因为这样能天然处理"实例被强杀导致来不及 DECR"的情况：
     * 键各自带 TTL，实例没了它的键也会自动过期，不会有永久残留。
     */
    public void registerSubscriber(Long messageId, String subscriberId, Duration ttl) {
        try {
            // 用 Set + SCARD 计数，而不是"每订阅者一个键 + KEYS 匹配"。
            //
            // 【为什么必须改】原实现用 `redis.keys(prefix + "*")` 统计人数。
            // Redis 是单线程的，KEYS 会**阻塞整个实例**并扫描全库：
            // 1000 并发时看门狗每 5 秒对每个断线消息各调一次，
            // 足以把所有请求串行化 —— 实测 1000 并发下完成延迟 P50 高达 14 秒，
            // 而 30 并发时只有 3.4 秒，这个差异就是被 KEYS 拖掉的。
            // 现在改为 SADD/SCARD：O(1)，且不会随键数量增长而恶化。
            String key = subscriberSetKey(messageId);
            redis.opsForSet().add(key, subscriberId);
            redis.expire(key, ttl);
        } catch (Exception e) {
            log.debug("[SSE] 登记订阅者失败：{}", e.getMessage());
        }
    }

    /**
     * 刷新订阅者存活时间（心跳时调用，保证"人还在"这个事实不会过期）。
     *
     * <p>续期的是整个订阅集合键，EXPIRE 对所有成员同时生效，因此不需要
     * subscriberId 参数 —— 原参数从未被使用，反而让调用方误以为它参与续期。
     */
    public void touchSubscriber(Long messageId, Duration ttl) {
        try {
            redis.expire(subscriberSetKey(messageId), ttl);
        } catch (Exception e) {
            log.debug("[SSE] 刷新订阅者失败：{}", e.getMessage());
        }
    }

    /** 注销订阅者（连接正常关闭时调用）。 */
    public void unregisterSubscriber(Long messageId, String subscriberId) {
        try {
            redis.opsForSet().remove(subscriberSetKey(messageId), subscriberId);
        } catch (Exception e) {
            log.debug("[SSE] 注销订阅者失败：{}", e.getMessage());
        }
    }

    /**
     * 当前有多少个（任意实例上的）客户端正在接收这条流。
     *
     * <p>用 SCAN 匹配键前缀统计。单条流的订阅者最多几个，
     * 因此这里的开销可以忽略；相比"用一个共享计数器"的复杂度，这个实现更不容易出错。
     */
    public int subscriberCount(Long messageId) {
        try {
            Long size = redis.opsForSet().size(subscriberSetKey(messageId));
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            // 统计失败时返回 1（保守）：宁可多生成一会儿，也不要误杀正在接收的客户端
            return 1;
        }
    }

    /** 每个 messageId 一个 Set（成员为订阅者 ID），用 SCARD 计数。 */
    private String subscriberSetKey(Long messageId) {
        return RedisKeys.PREFIX + "stream:subs:" + messageId;
    }

    // ------------------------------------------------------------------ 并发计数

    /**
     * 用户/全局并发流计数。
     *
     * <p>用 Redis 计数而不是 JVM 内的 AtomicInteger：多实例部署时，
     * 「每个用户最多 3 条并发流」必须是全局约束，
     * 否则 3 个实例各放行 3 条，用户实际拿到 9 条。
     */
    public long incrementUserStreams(Long userId) {
        try {
            Long value = redis.opsForValue().increment(RedisKeys.userStreamCount(userId));
            redis.expire(RedisKeys.userStreamCount(userId), counterTtl);
            return value == null ? 1 : value;
        } catch (Exception e) {
            return 1;
        }
    }

    public void decrementUserStreams(Long userId) {
        try {
            Long value = redis.opsForValue().decrement(RedisKeys.userStreamCount(userId));
            if (value != null && value <= 0) {
                redis.delete(RedisKeys.userStreamCount(userId));
            }
        } catch (Exception e) {
            log.debug("[SSE] 递减用户并发流计数失败：{}", e.getMessage());
        }
    }

    public long incrementGlobalStreams() {
        try {
            Long value = redis.opsForValue().increment(RedisKeys.globalStreamCount());
            redis.expire(RedisKeys.globalStreamCount(), counterTtl);
            return value == null ? 1 : value;
        } catch (Exception e) {
            return 1;
        }
    }

    public void decrementGlobalStreams() {
        try {
            Long value = redis.opsForValue().decrement(RedisKeys.globalStreamCount());
            if (value != null && value <= 0) {
                redis.delete(RedisKeys.globalStreamCount());
            }
        } catch (Exception e) {
            log.debug("[SSE] 递减全局并发流计数失败：{}", e.getMessage());
        }
    }

    private static long parseLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
