package com.overseas.dorm.agent.stream;

import com.overseas.dorm.agent.aiservice.AgentRegistry;
import com.overseas.dorm.agent.aiservice.AgentRouter;
import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.audit.AgentTurnContext;
import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.guardrail.ContentGuardrail;
import com.overseas.dorm.agent.model.ModelRegistry;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.enums.StreamState;
import com.overseas.dorm.common.enums.AgentType;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.entity.AgentConversation;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.agent.client.DormServiceClient;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一轮对话的流式编排，全项目最核心也最复杂的一个类。
 *
 * <table>
 *   <tr><th>需求</th><th>实现手段</th></tr>
 *   <tr><td>逐字输出</td><td>TokenStream.onPartialResponse → 推 SSE</td></tr>
 *   <tr><td>思考过程可见</td><td>onPartialThinking → 单独的 REASONING 事件</td></tr>
 *   <tr><td>工具调用过程可见</td><td>beforeToolExecution / onToolExecuted → TOOL 事件</td></tr>
 *   <tr><td>断线不丢、不重</td><td>每个事件带单调 seq，写 Redis Stream；重连按 seq 续传</td></tr>
 *   <tr><td>心跳保活</td><td>定时发送 SSE 注释帧</td></tr>
 *   <tr><td>实时内容安全</td><td>滑窗护栏，只推送"已过审"的部分</td></tr>
 *   <tr><td>可打断</td><td>cancel 标记 + 每个 token 检查</td></tr>
 *   <tr><td>幂等</td><td>turnId → 已存在则直接重放既有回答</td></tr>
 *   <tr><td>并发隔离</td><td>生成跑在独立线程池，不占用 Tomcat 请求线程</td></tr>
 *   <tr><td>模型降级</td><td>ModelRegistry 给出候选链 + 熔断</td></tr>
 *   <tr><td>可审计</td><td>用户消息/助手消息/工具调用/token 用量全部落库</td></tr>
 * </table>
 *
 * 三种策略各有问题：
 * <pre>
 *   A. 立刻停止   → 用户切个后台回来发现回答只有一半，体验差；
 *   B. 一直生成   → 用户再也不回来，token 白烧（真金白银）；
 *   C. 宽限再停   → 断线后继续生成，若 30 秒内无人重新订阅则中止。
 * </pre>
 * 本项目选 C：既给了移动端"切后台再回来"的容错窗口，
 * 又不会为彻底离开的用户持续消耗额度。这个宽限期是可配置的
 * （见 {@link #ORPHAN_GRACE_SECONDS} 与下方实现）。
 */
@Slf4j
@Service
public class AgentTurnService {

    /** 断线后的宽限秒数：期间继续生成并缓冲，超时无人订阅则中止。 */
    private static final long ORPHAN_GRACE_SECONDS = 30L;

    private final AgentProperties properties;
    private final AgentRegistry agentRegistry;
    private final AgentRouter agentRouter;
    private final AgentStreamBuffer streamBuffer;
    private final ConversationService conversationService;
    private final ContentGuardrail contentGuardrail;
    private final AgentRateLimiter rateLimiter;
    private final AgentAuditService agentAuditService;
    private final ModelRegistry modelRegistry;
    private final DormServiceClient auditService;

    /**
     * 每轮生成的中止钩子：messageId → "立刻收口这一轮"的动作。
     *
     * <p><b>【H10 修复】</b>原先"停止生成"只往 Redis 写一个 cancel 标记
     * （{@code AgentStreamBuffer.markCancelled}），模型回调看到标记就丢弃后续 token，
     * 但没有任何东西去终结这一轮，终态仍然要等模型自然收流后由
     * {@code onCompleteResponse → finishSuccessfully} 写出，于是：
     * 用户点了停止，却拿到 {@code DONE} + 落库 {@code COMPLETED} + 审计 SUCCESS，
     * 而正文已经被静默截断（实测 96/345 字，停在句子中间）。
     * 这是<b>静默的数据损坏</b>：用户会把半句话当成完整结论。
     *
     * <p>现在 {@code stop()} 会直接执行这个钩子：在轮次锁下立刻收口为
     * {@code INTERRUPTED}（部分内容保留并明确标记），不再等模型。
     */
    private final java.util.Map<Long, Runnable> abortHooks = new java.util.concurrent.ConcurrentHashMap<>();
    private final com.overseas.dorm.domain.mapper.AgentMessageMapper messageMapper;
    private final java.util.concurrent.ExecutorService streamExecutor;
    private final ThreadPoolTaskScheduler streamScheduler;
    private final com.overseas.dorm.agent.memory.AgentMemoryService memoryService;
    /** 本轮对话的内存记忆视图：工具循环每一轮都要读到上一轮的 assistant/tool 消息。 */
    private final com.overseas.dorm.agent.memory.DormChatMemoryStore chatMemoryStore;
    private final TaskExecutor agentTaskExecutor;
    /** 上游模型并发闸门：保护模型服务商（见 ModelConcurrencyGuard 的说明）。 */
    private final com.overseas.dorm.agent.model.ModelConcurrencyGuard modelGuard;

    /**
     * 订阅键的存活时间。
     *
     * <p>取值权衡：太短会让长回答在中途被误判"无人接收"而中止；太长会让
     * 进程被强杀后的残留键（"幽灵订阅"）拖延较久，白白多生成一会儿。
     * 180 秒覆盖了宽限期（30s）与典型的单轮耗时，同时残留也能在 3 分钟内自愈；
     * 更长的回答由心跳持续续期，不受此限制。
     */
    private static final Duration SUBSCRIBER_TTL = Duration.ofSeconds(180);

    /**
     * 本实例登记的订阅者 ID 集合：messageId → 本实例上仍在线的连接 ID。
     *
     * <p>用 Set 而不是单个 String：同一 messageId 允许生成连接与多条续传连接并存，
     * 单值会在续传接入时覆盖生成连接的 ID，导致生成结束时把整条消息的本地记录
     * 无条件删掉（P1-3），并且让心跳无法判断"本实例是否还有人在收"。
     *
     * <p>注意这里只放本实例的信息，跨实例的可见性由 Redis 里的订阅键提供。
     * 两者职责不同：Redis 键回答"全局还有没有人接收"，
     * 本地映射回答"本实例还要不要给它续期"。
     */
    private final Map<Long, Set<String>> localSubscriberIds = new ConcurrentHashMap<>();

    /**
     * 本实例已经确认取消的轮次。
     *
     * <p>{@code stop()} 在本实例时会立刻执行 abort 钩子；跨实例时只能靠 Redis 标记。
     * 但每 token 都 {@code hasKey} 一次 Redis 的成本太高（1000 并发 × 20 token/s
     * = 2 万次/秒），所以本地命中后不再查 Redis，跨实例的最坏延迟由
     * {@link #CANCEL_CHECK_INTERVAL_MS} 控制。
     */
    private final Set<Long> locallyCancelled = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastCancelCheckAt = new ConcurrentHashMap<>();
    private static final long CANCEL_CHECK_INTERVAL_MS = 500L;

    /**
     * 在线连接表：messageId → 该消息上全部在线连接的出站通道集合。
     *
     * <p>所有写入都必须经过通道（write-behind），生产者永不直接写 socket；
     *
     * 原实现是 {@code Map<Long, SseChannel>}，等价于"同一条消息只允许一条连接"：
     * <ul>
     *   <li>{@code resume()} 里 {@code channels.put()} 覆盖旧值并 {@code close()} 旧连接
     *       → 开一条续传会<b>静默踢掉正在生成的原连接</b>（实测原连接收不到任何终态事件，
     *       客户端无法区分"正常结束"与"我被踢下线"，H4）；</li>
     *   <li>被踢连接的清理又调用 {@code closeChannel(messageId)}（按 messageId 删除）
     *       → 关掉此刻表里的<b>最新</b>连接 → 多条连接互相消灭
     *       （实测同一已完成后消息并发 5 条续传：0~3/30 拿到完整内容、90% 空响应，H3）。</li>
     * </ul>
     * 改成集合后：同一 messageId 上生成连接与任意多条续传连接并存、各自独立收尾；
     * 轮次结束时 {@code closeChannel} 向集合里的每一条连接发终态并收尾。
     */
    private final Map<Long, Set<SseChannel>> channels = new ConcurrentHashMap<>();
    /** messageId -> 是否已经因为客户端断开而"孤儿化"。 */
    private final Map<Long, AtomicBoolean> orphaned = new ConcurrentHashMap<>();

    public AgentTurnService(AgentProperties properties,
                            AgentRegistry agentRegistry,
                            AgentRouter agentRouter,
                            AgentStreamBuffer streamBuffer,
                            ConversationService conversationService,
                            ContentGuardrail contentGuardrail,
                            AgentRateLimiter rateLimiter,
                            AgentAuditService agentAuditService,
                            ModelRegistry modelRegistry,
                            DormServiceClient auditService,
                            com.overseas.dorm.domain.mapper.AgentMessageMapper messageMapper,
                            @Qualifier("agentStreamExecutor") java.util.concurrent.ExecutorService streamExecutor,
                            @Qualifier("agentStreamScheduler") ThreadPoolTaskScheduler streamScheduler,
                            com.overseas.dorm.agent.memory.AgentMemoryService memoryService,
                            com.overseas.dorm.agent.memory.DormChatMemoryStore chatMemoryStore,
                            @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
                            com.overseas.dorm.agent.model.ModelConcurrencyGuard modelGuard) {
        this.properties = properties;
        this.agentRegistry = agentRegistry;
        this.agentRouter = agentRouter;
        this.streamBuffer = streamBuffer;
        this.conversationService = conversationService;
        this.contentGuardrail = contentGuardrail;
        this.rateLimiter = rateLimiter;
        this.agentAuditService = agentAuditService;
        this.modelRegistry = modelRegistry;
        this.auditService = auditService;
        this.messageMapper = messageMapper;
        this.streamExecutor = streamExecutor;
        this.streamScheduler = streamScheduler;
        this.memoryService = memoryService;
        this.chatMemoryStore = chatMemoryStore;
        this.agentTaskExecutor = agentTaskExecutor;
        this.modelGuard = modelGuard;
    }

    // ================================================================ 发起一轮对话

    /**
     * 发起一轮对话，返回 SSE 连接。
     *
     * @param conversationId 会话 ID；为空则新建会话
     * @param turnId         客户端轮次幂等键；为空则用 UUID（此时失去幂等能力）
     * @param message        用户消息
     */
    public SseEmitter chat(Long conversationId, String turnId, String message) {
        try {
            return doChat(conversationId, turnId, message);
        } catch (BizException e) {
            // 业务性拒绝（限流、并发上限、预算、输入护栏）必须走 SSE 的 ERROR 帧，
            // 而不是让 Spring 返回 HTTP 500 —— 后者前端拿不到任何可展示的原因，
            // 用户只会看到"服务器错误"，而实际原因是"你已经有 3 条对话在进行中"。
            log.info("[Agent] 本轮被业务规则拒绝：{} - {}", e.getResultCode(), e.getMessage());
            return errorEmitter(e.getResultCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Agent] 发起对话失败", e);
            return errorEmitter(ResultCode.SYSTEM_ERROR, "发起对话失败，请稍后重试。");
        }
    }

    /**
     * 构造一个立即返回错误并关闭的 SSE 连接。
     *
     * <p>这样前端只需要一套解析逻辑：连接建立 → 收到 ERROR 帧 → 展示 message。
     * 不需要再区分"HTTP 500 的 JSON 错误"与"SSE 流内的错误"两种形态，* 那种双形态契约在实际联调中几乎一定会漏掉一种。
     */
    private SseEmitter errorEmitter(ResultCode code, String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code.getCode());
            payload.put("message", message);
            payload.put("traceId", TraceContext.getOrCreate());
            emitter.send(SseEmitter.event()
                    .id("1")
                    .name(AgentEnums.StreamEvent.ERROR.name())
                    .data(JsonUtil.toJson(payload)));
            emitter.complete();
        } catch (Exception e) {
            log.debug("[SSE] 返回错误帧失败：{}", e.getMessage());
        }
        return emitter;
    }

    private SseEmitter doChat(Long conversationId, String turnId, String message) {
        LoginUser user = UserContext.require();

        if (!properties.isEnabled()) {
            throw BizException.of(ResultCode.AGENT_DISABLED);
        }

        // ---- 1. 输入护栏（注入检测 + 违规内容 + PII 脱敏） ----
        ContentGuardrail.InputCheck inputCheck = contentGuardrail.checkInput(message);
        String safeMessage = inputCheck.maskedText();

        String effectiveTurnId = turnId == null || turnId.isBlank()
                ? java.util.UUID.randomUUID().toString() : turnId;
        String traceId = TraceContext.getOrCreate();

        // ---- 2. 幂等预检：必须先于限流 / 预算 / 并发计数 ----
        //
        // 【M10 修复】纯重放不调用模型、不占用生成资源，因此不应消耗任何配额。
        // 原实现把幂等检查放在限流之后（第 6 步），于是客户端因网络抖动重试同一个
        // turnId 时，每次重试都白扣一个令牌：实测连续重放几次就把 20 次/分钟的
        // 配额吃光，用户随后真正的提问被 6001 拦下 —— 花的是重试的钱，
        // 拦的是真实的提问。
        //
        // 归属校验不需要额外查询：findAssistantByTurn 的 SQL 带 user_id 条件，
        // 拿别人的 conversationId + 自己的 turnId 只会查不到（H2 的修法），
        // 于是自然落到下面的常路径，由 requireOwned 抛 404。
        if (conversationId != null) {
            AgentMessage replayed = conversationService.findAssistantByTurn(
                    effectiveTurnId, user.getUserId(), conversationId);
            if (replayed != null) {
                return startReplay(replayed, user, "本轮的回复已生成过，以下是完整内容。");
            }
        }

        // ---- 3. 限流 ----
        String limitMessage = rateLimiter.tryAcquireTurn(user.getUserId());
        if (limitMessage != null) {
            throw BizException.of(ResultCode.AGENT_RATE_LIMITED, limitMessage);
        }

        // ---- 4. 预算守卫 ----
        AgentRateLimiter.BudgetDecision budget = rateLimiter.checkBudget(user.getUserId());
        if (budget.reject()) {
            throw BizException.of(ResultCode.AGENT_BUDGET_EXCEEDED, budget.message());
        }

        // ---- 5. 并发流上限 ----
        long userStreams = streamBuffer.incrementUserStreams(user.getUserId());
        if (userStreams > properties.getStream().getMaxStreamsPerUser()) {
            streamBuffer.decrementUserStreams(user.getUserId());
            throw BizException.of(ResultCode.AGENT_CONCURRENCY_LIMIT,
                    "你已有 %d 条对话正在进行中，请等它结束后再发起新的对话。"
                            .formatted(properties.getStream().getMaxStreamsPerUser()));
        }
        long globalStreams = streamBuffer.incrementGlobalStreams();
        if (globalStreams > properties.getStream().getMaxStreamsGlobal()) {
            streamBuffer.decrementUserStreams(user.getUserId());
            streamBuffer.decrementGlobalStreams();
            throw BizException.of(ResultCode.AGENT_CONCURRENCY_LIMIT,
                    "当前使用智能助手的同事较多，请稍后再试。");
        }

        // 从这里开始，user/global 两个计数都已经加过。只要没有把生成任务成功交给
        // generate()（handedOff=false），无论是建会话、beginTurn、模型闸门还是
        // 线程池提交失败，都必须在 finally 里把计数还回去。
        // 原实现只在个别分支手工 decrement，漏掉了 beginTurn 异常与模型闸门拒绝，
        // 结果是"被拒绝一次，并发额度永久少一个"。
        boolean handedOff = false;
        try {
            // ---- 6. 会话准备 ----
            AgentConversation conversation;
            boolean newConversation = false;
            if (conversationId == null) {
                conversation = conversationService.createConversation(user.getUserId(), safeMessage, AgentType.ROUTER);
                newConversation = true;
            } else {
                conversation = conversationService.requireOwned(conversationId, user.getUserId());
                if (conversation == null) {
                    throw BizException.of(ResultCode.NOT_FOUND, "会话不存在或不属于你");
                }
            }

            // 幂等重放已在第 2 步处理（见那里的说明：必须早于限流，否则重放白扣配额）。
            // 这里仍要处理一个漏网场景：beginTurn 的唯一索引把"同一 turnId 的并发第二次提交"
            // 拦下时，会抛出 DuplicateKeyException，由上层转为"该轮已在进行/已完成"。

            // ---- 7~8. 一轮开始的所有写入合并为一次提交 ----
            // 用户消息 + 会话计数/标题 + 助手消息占位，原本是 4 次独立提交；
            // 合并后每轮开始只提交 1 次（见 ConversationService.beginTurn 的说明：
            // 本机存储仅 26 次 fsync/秒，提交次数直接决定并发上限）。
            //
            // 注意：助手消息与用户消息共用同一个 turnId（不加后缀）。
            // 幂等查找按「turnId + role=ASSISTANT」定位，加了后缀就永远找不到，
            // 幂等会静默失效 —— 表现为同一轮问题被反复计费。
            AgentMessage assistant = conversationService.beginTurn(conversation.getId(),
                    safeMessage, effectiveTurnId, traceId);
            Long messageId = assistant.getId();

            // ---- 9. 起流 ----
            SseEmitter emitter = newEmitter();
            // 注册出站通道：生成线程只入队，由该连接的虚拟线程负责写 socket。
            // 这一层是"1000 并发长连接"的基础 —— 没有它，一个不读数据的客户端
            // 就会占住一个生成线程，几十个这样的客户端足以让整个实例停止生成。
            SseChannel channel = new SseChannel(messageId, emitter,
                    properties.getStream().getChannelQueueCapacity(), "initial", 1L);
            registerChannel(messageId, channel);
            // 【关键】初始连接也必须登记订阅者。
            //
            // 这里曾是重构时引入的回归：只有"断线续传"路径登记了订阅者，
            // 初次发起对话的连接没有登记。看门狗据此认为"无人接收"，
            // 在宽限期后中止生成 —— 而用户其实好好连着。
            // 更隐蔽的是：当时看门狗里的统计用了阻塞式 KEYS，
            // 慢到几乎跑不动，反而把这个缺陷掩盖了；把 KEYS 换成 O(1) 的 SCARD 后，
            // 看门狗"准时"了，缺陷立刻以 657 个失败的形式暴露出来。
            // 教训：修一个性能问题会把被它掩盖的正确性问题一起掀出来。
            String subscriberId = java.util.UUID.randomUUID().toString();
            streamBuffer.registerSubscriber(messageId, subscriberId, SUBSCRIBER_TTL);
            addLocalSubscriber(messageId, subscriberId);

            // 【P0-1】从这里到 handedOff=true 之间的任何失败，都必须回滚已经注册的
            // channel / 订阅者 / 本地映射。否则 modelGuard 拒绝时 chat() 会返回另一个
            // errorEmitter，而这里的 emitter 从未被 Spring initialize()：
            // 心跳每 15s 往它的 earlySendAttempts 里塞帧（无界增长），
            // 订阅键每 15s 被 touchSubscriber 续期（永不过期），看门狗也不会回收它。
            boolean permitAcquired = false;
            try {
                // ---- 上游模型闸门 ----
                // 容器能容纳 1000 条并发流，不代表可以向上游并发 1000 次模型调用。
                // 这里先申请许可：拿不到就用业务可读的提示快速失败，
                // 而不是把 429 风暴打给模型服务商（实测上游会连带触发熔断与降级放大）。
                if (!modelGuard.tryAcquire()) {
                    conversationService.finishTurn(messageId, conversation.getId(),
                            "当前咨询人数较多，请稍后重试。", null,
                            AgentEnums.MessageStatus.FAILED, "上游模型并发已达上限", 0, 0, 0L, 0L,
                            user.getUserId(), 0, 0, false, newConversation, null, List.of());
                    throw BizException.of(ResultCode.AGENT_CONCURRENCY_LIMIT,
                            "当前咨询人数较多，请稍后重试。你的问题没有被消耗额度，稍后再问即可。");
                }
                permitAcquired = true;

                Long finalConversationId = conversation.getId();
                streamBuffer.setState(messageId, StreamState.GENERATING);

                boolean finalNewConversation = newConversation;
                String budgetNote = budget.overBudget() ? budget.message() : null;
                // 虚拟线程不会继承 ThreadLocal：显式带上用户令牌，
                // 否则生成线程里的工具 HTTP 调用会缺少 Authorization（401）。
                String userToken = com.overseas.dorm.agent.security.AgentTokenContext.get();
                streamExecutor.execute(() -> com.overseas.dorm.agent.security.AgentTokenContext.runAs(
                        userToken, () -> generate(emitter, user, finalConversationId, messageId,
                                safeMessage, effectiveTurnId, traceId, finalNewConversation, budgetNote,
                                conversation.getConversationNo(), subscriberId)));
                handedOff = true;
                return emitter;
            } catch (RuntimeException e) {
                if (!handedOff) {
                    if (permitAcquired) {
                        modelGuard.release();
                    }
                    removeLocalSubscriber(messageId, subscriberId);
                    streamBuffer.unregisterSubscriber(messageId, subscriberId);
                    unregisterChannel(messageId, channel);
                    channel.close();
                    // 如果 setState(GENERATING) 已经成功、但线程池提交失败，不能让状态
                    // 永远停在 GENERATING：续传会一直认为"还在生成"，看门狗也会反复扫。
                    try {
                        streamBuffer.setState(messageId, StreamState.FAILED);
                    } catch (Exception stateError) {
                        log.debug("[SSE] 回滚流状态失败 messageId={}: {}", messageId, stateError.getMessage());
                    }
                }
                throw e;
            }
        } finally {
            if (!handedOff) {
                streamBuffer.decrementUserStreams(user.getUserId());
                streamBuffer.decrementGlobalStreams();
            }
        }
    }

    // ================================================================ 断点续传

    /**
     * 重连续传。
     *
     * <p>流程（顺序很关键，写反了会丢事件）：
     * <pre>
     *   1. 先订阅实时通道并<b>缓存</b>收到的事件（此时先不发给前端）
     *   2. 从 Redis Stream 拉取 seq &gt; lastEventId 的历史事件，按序发出
     *   3. 把步骤 1 缓存中 seq 仍大于"已发出最大 seq"的事件补发
     *   4. 之后转入纯实时转发
     * </pre>
     * 为什么必须先订阅再拉历史：如果先拉历史再订阅，
     * 两步之间产生的 token 既不在历史里（拉的时候还没写进去）、
     * 也收不到（还没订阅），就会永远丢掉那几个字。
     */
    public SseEmitter resume(Long messageId, long lastEventId) {
        LoginUser user = UserContext.require();
        AgentMessage assistant = findAssistantMessage(messageId, user.getUserId());
        if (assistant == null) {
            // 续传入口也可能被拒绝（会话不存在/不属于自己/已过期），
            // 同样用 SSE ERROR 帧表达，保持与前端的单一契约
            return errorEmitter(ResultCode.AGENT_TURN_NOT_FOUND, ResultCode.AGENT_TURN_NOT_FOUND.getMessage());
        }

        SseEmitter emitter = newEmitter();
        // 出站通道：所有帧先入队，由该连接专属的虚拟线程写入（生产者永不阻塞）
        SseChannel channel = new SseChannel(messageId, emitter,
                properties.getStream().getChannelQueueCapacity(), "resume");
        // 【H3/H4 修复】不再"同一条消息只保留最新连接"。
        //
        // 原实现 `channels.put(messageId, channel)` + `previous.close()` 有两重危害：
        //   1) 开一条续传会静默踢掉正在生成的原连接，原连接收不到任何终态事件，
        //      客户端无法区分"正常结束"与"我被踢下线"（H4）；
        //   2) 被踢连接的清理又调用 closeChannel(messageId)（按 messageId 关掉
        //      此刻表里的最新连接）→ 多条连接互相消灭（H3，实测 90% 空响应）。
        //
        // 现在同一 messageId 允许多条连接并存：生成连接与任意多条续传连接各自独立收尾。
        // 这里选择"补完历史再注册进 channels"：补历史期间的事件由 Pub/Sub 订阅缓存，
        // 切实时后按 seq 去重补发；注册太早会让心跳与轮次结束的 closeChannel 在历史
        // 补发过程中介入，生命周期更难推理。
        String subscriberId = java.util.UUID.randomUUID().toString();
        streamBuffer.registerSubscriber(messageId, subscriberId, SUBSCRIBER_TTL);
        // localSubscriberIds 是 Set：每条连接保留自己的 ID；订阅键续期是
        // `EXPIRE dorm:stream:subs:{messageId}`（整键续期、对所有成员生效），
        // 因此只要本实例还有任意一条连接，心跳就会给整个集合续期。
        addLocalSubscriber(messageId, subscriberId);

        // 序号边界：所有 seq <= sentUpTo 的事件都已经（或即将）由本方法按序发出。
        // 用 AtomicLong + 同步块保证「历史补发」与「实时转发」两条路径不会交叉乱序。
        final AtomicLong sentUpTo = new AtomicLong(lastEventId);
        final Object sendLock = new Object();
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean live = new AtomicBoolean(false);
        final java.util.Queue<AgentStreamBuffer.StreamEvent> pendingBuffer =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        AutoCloseable[] subscriptionHolder = new AutoCloseable[1];

        // 【只收尾自己这一条连接】H3/H4 修复的第二个关键点。
        //
        // 原实现在这些位置调用 closeChannel(messageId) —— 那是"按 messageId 关掉表里的
        // 连接"，在多连接并存之后会误关别的健康连接（比如正在生成的原连接）。
        // 现在统一走这个动作：注销自己的订阅者、退订（引用计数到 0 时才会真正退订）、
        // 把自己从 channels 移除，并优雅收尾自己的出站队列（把已入队的终态帧写完再关闭）。
        final Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                removeLocalSubscriber(messageId, subscriberId);
                streamBuffer.unregisterSubscriber(messageId, subscriberId);
                closeQuietly(subscriptionHolder[0]);
                unregisterChannel(messageId, channel);
                channel.close();
            }
        };

        java.util.function.Consumer<AgentStreamBuffer.StreamEvent> forward = event -> {
            boolean accepted;
            synchronized (sendLock) {
                if (closed.get() || event.seq() <= sentUpTo.get()) {
                    return;
                }
                accepted = channel.offer(String.valueOf(event.seq()), event.event(), event.data());
                if (accepted) {
                    sentUpTo.set(event.seq());
                }
            }
            // cleanup 里有 Redis SREM 与退订，绝不能在 sendLock 里执行：
            // 那会让历史补发线程等一次网络往返，还会把锁的持有时间放大到毫秒级。
            if (!accepted) {
                // 客户端又断了或跟不上：结束本次续传，等下一次重连（缓冲仍在 Redis 里）
                cleanup.run();
                return;
            }
            if (streamBuffer.isTerminalEvent(event.event())) {
                cleanup.run();
            }
        };

        // 【顺序至关重要】必须先订阅、再补历史。
        // 反过来写会留下一个致命窗口：在「读完历史」与「注册订阅」之间产生的事件
        // 既不在历史里（读的时候还没写进去），也收不到（订阅还没注册），于是永久丢失。
        // 表现就是断点续传后事件序号出现空洞 —— 文字可能刚好没丢，但过程事件丢了。
        // 订阅回调在补历史期间只往缓冲队列里放，等历史补完再统一按 seq 去重转发。
        //
        // 订阅按 messageId 共享一份（AgentStreamBuffer 内部引用计数）：
        // 多条并发续传不会再互相破坏订阅生命周期。
        subscriptionHolder[0] = streamBuffer.subscribe(messageId, event -> {
            if (closed.get()) {
                return;
            }
            if (!live.get()) {
                pendingBuffer.add(event);
                return;
            }
            forward.accept(event);
        });

        // 1) 补发历史
        List<AgentStreamBuffer.StreamEvent> history = streamBuffer.readAfter(messageId, lastEventId, 1000);
        for (AgentStreamBuffer.StreamEvent event : history) {
            forward.accept(event);
        }

        StreamState state = streamBuffer.state(messageId);
        if (streamBuffer.isTerminal(state)) {
            // 已经生成结束：历史补完即可关闭连接。
            // 如果历史末尾已经是终态事件，就不要再补一个，否则前端会收到两次结束信号。
            boolean alreadyTerminal = !history.isEmpty()
                    && streamBuffer.isTerminalEvent(history.get(history.size() - 1).event());
            if (!alreadyTerminal) {
                forward.accept(new AgentStreamBuffer.StreamEvent(sentUpTo.get() + 1,
                        AgentEnums.StreamEvent.DONE.name(),
                        JsonUtil.toJson(Map.of("messageId", messageId, "resumed", true,
                                "status", state,
                                "message", "本轮回答已生成完毕，以上为完整内容。"))));
            }
            cleanup.run();
            log.info("[SSE] 续传完成（本轮已结束）messageId={} 从 seq={} 补发 {} 条历史事件",
                    messageId, lastEventId, history.size());
            return emitter;
        }

        // 2) 切到实时转发，并清空缓冲队列（按 seq 去重）
        live.set(true);
        AgentStreamBuffer.StreamEvent buffered;
        while ((buffered = pendingBuffer.poll()) != null && !closed.get()) {
            forward.accept(buffered);
        }

        // 3) 历史补完、转入实时之后才把本连接登记进 channels：
        //    让心跳与轮次结束的 closeChannel 只在连接真正进入实时阶段后介入，
        //    避免与历史补发并发。SseChannel 内部会按 seq 重排两条投递路径的帧。
        if (!closed.get()) {
            registerChannel(messageId, channel);
        }

        // 续传连接同样需要心跳：既保活（长回答期间代理可能因空闲断连），
        // 也续期订阅键（否则重连后经过一个 TTL 会被看门狗误判为无人接收）。
        //
        // 注意：SseEmitter 的 onCompletion/onTimeout/onError 都是单回调（后注册覆盖先注册），
        // 所以这里必须把"关闭通道"一并做掉，否则会覆盖 SseChannel 构造时注册的
        // onCompletion(this::close)，导致客户端断开后通道不被收尾。
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.info("[SSE] 续传已接入 messageId={} 从 seq={} 补发 {} 条历史事件，当前状态={}",
                messageId, lastEventId, history.size(), state);
        return emitter;
    }

    /** 主动打断。 */
    public void stop(Long messageId) {
        LoginUser user = UserContext.require();
        AgentMessage assistant = findAssistantMessage(messageId, user.getUserId());
        if (assistant == null) {
            throw BizException.of(ResultCode.AGENT_TURN_NOT_FOUND);
        }
        streamBuffer.markCancelled(messageId);
        // 【H10 修复】打标记只让回调"不再消费"，并不能结束这一轮：
        // 必须立刻执行中止钩子，否则终态（DONE/COMPLETED/SUCCESS）会等模型自己跑完才写，
        // 用户看到的是一份"成功"的、被截断的回答。
        Runnable abort = abortHooks.get(messageId);
        if (abort != null) {
            // 本地命中后 shouldStop 不再查 Redis：同一实例的 stop 是同步路径，
            // 不能因为缓存而让"停止"晚 500ms 才生效。
            locallyCancelled.add(messageId);
            lastCancelCheckAt.remove(messageId);
            log.info("[Agent] 用户请求打断，立即收口 messageId={}", messageId);
            abort.run();
        } else {
            // 本轮已经结束（或还没注册钩子）：只留标记即可，终态由既有路径负责
            log.info("[Agent] 用户请求打断（该轮已不在生成中，仅记录标记）messageId={}", messageId);
        }
    }

    // ================================================================ 生成主流程

    /**
     * 真正的生成逻辑，运行在专用线程池上。
     */
    private void generate(SseEmitter emitter, LoginUser user, Long conversationId, Long messageId,
                          String message, String turnId, String traceId, boolean newConversation,
                          String budgetNote, String conversationNo, String subscriberId) {
        // 身份与链路上下文必须显式传递到本线程（ThreadLocal 不会自动继承）。
        // 同时把轮次上下文注册进 AgentTurnContext 的会话注册表，
        // 以便在模型客户端的异步回调线程与工具执行线程里恢复。
        UserContext.runAs(user, () -> TraceContext.runWith(traceId, () -> {
            AgentTurnContext turnContext = AgentTurnContext.begin(conversationId, messageId, turnId, user, traceId);
            long start = System.currentTimeMillis();
            StringBuilder answer = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            long[] firstTokenAt = {0L};
            int[] toolCallCount = {0};
            int[] ragHitCount = {0};
            AtomicBoolean finished = new AtomicBoolean(false);
            // 记录实际提供服务的模型名（降级后可能与配置的主模型不同），
            // 终态事件需要把它如实告诉前端 —— 否则用户看到"降级了但界面还显示原模型"
            java.util.concurrent.atomic.AtomicReference<String> lastModelName =
                    new java.util.concurrent.atomic.AtomicReference<>("");
            ContentGuardrail.StreamGuard guard = contentGuardrail.newStreamGuard();
            // 【H10 修复】轮次锁：让"用户停止后的立刻收口"与"模型回调仍在追加正文"互斥。
            // 为什么不是 synchronized：本项目跑在虚拟线程上，synchronized 会 pin 住载体线程，
            // 而收口路径里有 DB/Redis 往返；ReentrantLock 在虚拟线程上不会 pin。
            java.util.concurrent.locks.ReentrantLock turnLock =
                    new java.util.concurrent.locks.ReentrantLock();
            // 本轮事件的唯一序号源。之前混用了两个来源（本地数组 + 读 Redis 的 lastSeq），
            // 会出现事件序号被跳号 —— 表现为断点续传时序号出现空洞。
            // 声明在 try 之外，因为 catch 分支的收尾也要用它。
            AtomicLong sequence = new AtomicLong(1L);

            // 【关键】TokenStream.start() 是异步的：它只负责"发起"，立刻返回，
            // 真正的生成与回调发生在模型 HTTP 客户端的读流虚拟线程里
            // （agent-model-stream-*，见 VirtualThreadJdkHttpClient）。
            // 因此不能用 try-finally 直接判断"这一轮结束了" ——
            // 那会导致心跳被立刻取消、并发计数被立刻释放、
            // 最严重的是把轮次上下文提前注销，使得工具在"未登录"状态下执行
            // （表现为工具拿不到用户身份、审计丢失、RAG 检索不落日志）。
            // 正确做法是用闸门等待真正的终态回调。
            CountDownLatch finishedLatch = new CountDownLatch(1);

            try {
                // 心跳：定时发送 SSE 注释帧，避免中间代理因空闲断开长连接

                // 断线看门狗：宽限期内无人订阅则中止

                // START 必须同时写入缓冲并发给当前连接。
                // 只写缓冲（经 Redis 广播）会导致"首次连接收不到 START"，
                // 因为此刻这条连接还没有进入实时订阅路径。
                Map<String, Object> startPayload = new LinkedHashMap<>();
                startPayload.put("conversationId", conversationId);
                startPayload.put("conversationNo", conversationNo);
                startPayload.put("turnId", turnId);
                startPayload.put("startSeq", 1);
                startPayload.put("heartbeatSeconds", properties.getStream().getHeartbeatSeconds());
                if (budgetNote != null) {
                    startPayload.put("notice", budgetNote);
                }
                publish(messageId, sequence.get(), AgentEnums.StreamEvent.START, startPayload);

                // 本轮记忆视图从空开始：工具循环后续会通过 updateMessages 填充
                // assistant(tool_calls) 与 TOOL 结果（见 DormChatMemoryStore 的 H11 说明）。
                chatMemoryStore.beginTurn(conversationId);

                // 意图路由 → 选专家 Agent
                AgentType agentType = agentRouter.route(message, user.isAdmin());
                TokenStream stream = agentRegistry.get(agentType, user.isAdmin())
                        .chat(conversationId, message);

                ModelRegistry.ModelChoice choice = modelRegistry.select(agentType, false);

                stream
                        .onPartialThinking(partial -> inTurn(turnContext, () -> underTurnLock(turnLock, () -> {
                            if (shouldStop(messageId)) {
                                return;
                            }
                            String text = partial.text();
                            if (text == null || text.isEmpty()) {
                                return;
                            }
                            thinking.append(text);
                            publish(messageId, sequence, AgentEnums.StreamEvent.REASONING,
                                    Map.of("text", text));
                        })))
                        .onPartialResponse(token -> inTurn(turnContext, () -> underTurnLock(turnLock, () -> {
                            if (shouldStop(messageId)) {
                                return;
                            }
                            if (firstTokenAt[0] == 0L) {
                                firstTokenAt[0] = System.currentTimeMillis() - start;
                            }
                            answer.append(token);
                            // 实时内容安全：只推送已过审的部分（见 ContentGuardrail 的说明）
                            ContentGuardrail.GuardResult guardResult = guard.accept(token);
                            if (guardResult.blocked()) {
                                publish(messageId, sequence, AgentEnums.StreamEvent.BLOCKED,
                                        Map.of("reason", String.valueOf(guardResult.reason())));
                                return;
                            }
                            if (!guardResult.emit().isEmpty()) {
                                publish(messageId, sequence, AgentEnums.StreamEvent.DELTA,
                                        Map.of("text", guardResult.emit()));
                            }
                        })))
                        .beforeToolExecution(before -> inTurn(turnContext, () -> {
                            toolCallCount[0]++;
                            String toolName = before.request().name();
                            publish(messageId, sequence, AgentEnums.StreamEvent.TOOL, Map.of(
                                    "phase", "start",
                                    "tool", toolName,
                                    "label", toolLabel(toolName),
                                    "arguments", JsonUtil.parseMap(before.request().arguments())));
                        }))
                        .onToolExecuted((ToolExecution execution) -> inTurn(turnContext, () -> {
                            String toolName = execution.request().name();
                            publish(messageId, sequence, AgentEnums.StreamEvent.TOOL, Map.of(
                                    "phase", "end",
                                    "tool", toolName,
                                    "label", toolLabel(toolName),
                                    "failed", execution.hasFailed()));
                        }))
                        .onRetrieved((List<Content> contents) -> inTurn(turnContext, () -> {
                            if (contents == null || contents.isEmpty()) {
                                // LangChain4j 在未命中时也会回调一次空列表；
                                // 不要发 count=0 的 REFERENCE，否则前端会把已有引用清空。
                                return;
                            }
                            ragHitCount[0] = Math.max(ragHitCount[0], contents.size());
                            List<Map<String, Object>> references = contents.stream().map(content -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                String text = content.textSegment().text();
                                item.put("snippet", text.length() > 120 ? text.substring(0, 120) + "…" : text);
                                item.put("title", content.textSegment().metadata().getString("title"));
                                return item;
                            }).toList();
                            turnContext.addReferences(references);
                            publish(messageId, sequence, AgentEnums.StreamEvent.REFERENCE,
                                    Map.of("count", references.size(), "references", references));
                        }))
                        .onCompleteResponse((ChatResponse response) -> {
                            try {
                                inTurn(turnContext, () -> {
                                    if (!finished.compareAndSet(false, true)) {
                                        return;
                                    }
                                    String model = choice.modelName();
                                    if (response != null && response.modelName() != null) {
                                        model = response.modelName();
                                    }
                                    modelRegistry.reportSuccess(model);
                                    lastModelName.set(model);
                                    // 工具式 RAG 不会触发 onRetrieved；收尾前统一发一次累计引用，
                                    // 保证“回答里引用了制度”与“前端能看到来源”一致。
                                    List<Map<String, Object>> refs = turnContext.references();
                                    if (!refs.isEmpty()) {
                                        publish(messageId, sequence, AgentEnums.StreamEvent.REFERENCE,
                                                Map.of("count", refs.size(), "references", refs));
                                    }
                                    finishSuccessfully(emitter, messageId, conversationId, guard, answer,
                                            thinking, response, model, agentType, start, firstTokenAt[0],
                                            toolCallCount[0], Math.max(ragHitCount[0], turnContext.ragHits()), newConversation, user,
                                            sequence, turnLock);
                                });
                            } finally {
                                finishedLatch.countDown();
                            }
                        })
                        .onError(error -> {
                            try {
                                inTurn(turnContext, () -> {
                                    if (!finished.compareAndSet(false, true)) {
                                        return;
                                    }
                                    modelRegistry.reportFailure(choice.modelName(), error);
                                    finishWithError(emitter, messageId, conversationId, guard, answer,
                                            thinking, error, start, newConversation, user,
                                            toolCallCount[0], Math.max(ragHitCount[0], turnContext.ragHits()), choice.modelName(), sequence);
                                });
                            } finally {
                                finishedLatch.countDown();
                            }
                        })
                        .start();

                // 【H10 修复】注册中止钩子：stop() 拿到它就能立刻收口（见 stop 的说明）。
                // 放在 start() 之后、await 之前：此刻 choice/answer/sequence 都已就绪。
                abortHooks.put(messageId, () -> {
                    if (finished.compareAndSet(false, true)) {
                        finishInterrupted(emitter, messageId, conversationId, answer, thinking,
                                turnLock, start, newConversation, user, toolCallCount[0],
                                Math.max(ragHitCount[0], turnContext.ragHits()), choice.modelName(), sequence);
                    }
                });

                // 等真正的终态。超时也要收口，否则这条流会一直占着并发额度且前端永远转圈。
                boolean completed = finishedLatch.await(
                        Math.max(properties.getModel().getTurnTimeoutSeconds(), 10), TimeUnit.SECONDS);
                if (!completed) {
                    log.warn("[Agent] 本轮生成超过 {}s 未结束，执行超时收口 messageId={}",
                            properties.getModel().getTurnTimeoutSeconds(), messageId);
                    if (finished.compareAndSet(false, true)) {
                        // 【顺序很关键】必须先收口、再打取消标记。
                        // finishWithError 内部用 shouldStop()（= isCancelled）判断这是不是
                        // "用户主动停止"：若先 markCancelled，超时就会被误报并落库为
                        // INTERRUPTED「已停止生成。你可以继续追问…」，而 AI_TIMEOUT
                        // 那句"生成超时（超过 N 秒）"永远不可达 —— 用户看到的是
                        // "我停了它"，实际是系统超时，排障时会被完全带偏。
                        finishWithError(emitter, messageId, conversationId, guard, answer, thinking,
                                // 【括号很关键】必须把拼接后的整串再 formatted。
                                // 原写法 "A" + "B".formatted(x) 里，formatted 只作用于 B，
                                // 于是 A 里的 %d 原样出现在用户看到的提示里
                                // （实测超时提示显示为"生成超时（超过 %d 秒）"）。
                                // 这与本项目此前踩过的 "%s%s%0" + N + "d".formatted(...)
                                // 是同一类运算符优先级错误。
                                new BizException(ResultCode.AI_TIMEOUT,
                                        ("生成超时（超过 %d 秒），已中断本次回答。你可以重新提问，"
                                                + "或把问题拆得更具体一些。")
                                                .formatted(properties.getModel()
                                                        .getTurnTimeoutSeconds())),
                                start, newConversation, user, toolCallCount[0], Math.max(ragHitCount[0], turnContext.ragHits()),
                                lastModelName.get(), sequence);
                        // 再打取消标记：它的作用是让仍在跑的模型流与工具循环尽快停下来，
                        // 属于"收尾动作"，不能参与"失败原因"的判定。
                        streamBuffer.markCancelled(messageId);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Agent] 等待生成结束时被中断 messageId={}", messageId);
            } catch (BizException e) {
                log.warn("[Agent] 业务异常 messageId={}: {}", messageId, e.getMessage());
                finished.compareAndSet(false, true);
                finishWithError(emitter, messageId, conversationId, guard, answer, thinking,
                        e, start, newConversation, user, toolCallCount[0], Math.max(ragHitCount[0], turnContext.ragHits()),
                        lastModelName.get(), sequence);
            } catch (Exception e) {
                log.error("[Agent] 生成过程异常 messageId={}", messageId, e);
                finished.compareAndSet(false, true);
                finishWithError(emitter, messageId, conversationId, guard, answer, thinking,
                        e, start, newConversation, user, toolCallCount[0], Math.max(ragHitCount[0], turnContext.ragHits()),
                        lastModelName.get(), sequence);
            } finally {
                // 到这里才是"这一轮真的结束了"，此时释放资源、注销上下文才是安全的
                streamBuffer.decrementUserStreams(user.getUserId());
                streamBuffer.decrementGlobalStreams();
                AgentTurnContext.clear();
                abortHooks.remove(messageId);
                orphaned.remove(messageId);
                removeLocalSubscriber(messageId, subscriberId);
                locallyCancelled.remove(messageId);
                lastCancelCheckAt.remove(messageId);
                // 释放上游模型闸门许可（与申请处配对；必须放在这里，
                // 因为这一轮真正结束的标志是终态回调，而不是方法返回）
                modelGuard.release();
                // 兜底：正常终态路径已经 closeChannel 了，这里只处理异常/遗漏路径，
                // 避免 channels 表随着运行时间无限增长。
                //
                // 【多连接】这里必须按 messageId 把该消息上的所有连接都收尾：
                // 走到这里说明本轮已经结束，此刻还挂在上面的续传连接同样应该收到终态
                // 并关闭连接（原来只 remove 单个通道，多连接下会漏掉其它连接，
                // 它们只能等 90 秒的假死回收 —— 用户在那之前一直转圈）。
                closeChannel(messageId);
                // 会话变长时异步生成摘要：用独立的 agentTaskExecutor，
                // 既不占用流式线程，也绝不影响这一轮的结果（内部还有 try/catch 兜底）
                try {
                    agentTaskExecutor.execute(() -> memoryService.summarizeIfNeeded(conversationId));
                } catch (Exception e) {
                    log.debug("[Agent] 摘要任务提交失败（不影响本轮）conversationId={}", conversationId, e);
                }
            }
        }));
    }

    /** 当前轮次已收集的 RAG 引用；没有上下文（单测/异常路径）时返回空列表。 */
    private List<Map<String, Object>> currentReferences() {
        AgentTurnContext context = AgentTurnContext.current();
        return context == null ? List.of() : context.references();
    }

    /**
     * 正常收尾。
     *
     * {@code answer} 累加的是模型产出的<b>原始 token</b>，其中已经包含了
     * 被护栏滑窗扣留的那部分（扣留只是延迟推送，不是丢弃）。
     * 因此收尾时把 {@code guard.finish()} 放行的 tail 再追加进 {@code answer}，
     * 会让落库内容比实际推送给用户的文本<b>多出一个窗口</b>（默认 60 字）。
     *
     * <p>表现是"页面上看到的回答比数据库里短一截"，
     * 断点续传的判定条件（拼接文本 == 落库内容）也永远无法成立。
     * 正确做法：<b>tail 只负责推送，不再回写 answer</b>。
     */
    private void finishSuccessfully(SseEmitter emitter, Long messageId, Long conversationId,
                                    ContentGuardrail.StreamGuard guard, StringBuilder answer,
                                    StringBuilder thinking, ChatResponse response, String model,
                                    AgentType agentType, long start, long firstTokenMs,
                                    int toolCalls, int ragHits, boolean newConversation, LoginUser user,
                                    AtomicLong sequence,
                                    java.util.concurrent.locks.ReentrantLock turnLock) {
        // 【H10 修复】取消兜底：如果用户在生成过程中点了停止，这一轮不是成功。
        // 覆盖的窗口：取消恰好落在"最后一个 token"与 onCompleteResponse 之间
        // （此时中止钩子可能还没抢到 finished，或者钩子已收口但本回调抢先 CAS）。
        // 没有这条判断时，模型正常收流就会写出 DONE/COMPLETED/SUCCESS —— 实测就是这样丢掉半个回答的。
        if (shouldStop(messageId)) {
            finishInterrupted(emitter, messageId, conversationId, answer, thinking, turnLock,
                    start, newConversation, user, toolCalls, ragHits, model, sequence);
            return;
        }
        // 把护栏窗口里剩余的内容吐出（并做最后一次审核）
        ContentGuardrail.GuardResult tail = guard.finish();
        AgentEnums.MessageStatus status = AgentEnums.MessageStatus.COMPLETED;
        String blockReason = null;
        if (tail.blocked()) {
            status = AgentEnums.MessageStatus.BLOCKED;
            blockReason = tail.reason();
            // 必须与同一轮其它事件共用同一个序号源（本轮的 AtomicLong）。
            // 若这里改用 "Redis lastSeq + 1" 取号，就会与本轮 AtomicLong 形成两个序号源，
            // 导致客户端看到序号跳号/空洞 —— 实测表现为"序号存在空洞（间隔 34）"。
            publish(messageId, sequence, AgentEnums.StreamEvent.BLOCKED,
                    Map.of("reason", String.valueOf(blockReason)));
        } else if (!tail.emit().isEmpty()) {
            publish(messageId, sequence, AgentEnums.StreamEvent.DELTA, Map.of("text", tail.emit()));
        }

        // 【M5 修复】空回答不能被当作成功。
        // 实测：604 条 COMPLETED 的助手消息里有 32 条正文为空（5.3%），
        // 用户看到的是"成功但空白"的气泡 —— 既没有内容，也看不出失败，
        // 只能反复重问。典型来源是工具往返超限后"优雅收口"但模型没产出正文。
        // 这里把"非阻断且正文为空"明确记为失败，并给出可读原因。
        //
        // 注意判空要带上 tail.emit()：最后一段增量可能只存在于 tail 里而尚未并入 answer，
        // 只看 answer 会把"恰好内容都在末段"的正常回答误判为空。
        if (status == AgentEnums.MessageStatus.COMPLETED) {
            String body = answer == null ? "" : answer.toString();
            if (body.isBlank() && tail.emit() != null) {
                body = tail.emit();
            }
            if (body.isBlank()) {
                status = AgentEnums.MessageStatus.FAILED;
                blockReason = "本轮没有生成任何内容（可能是工具调用过多或模型返回为空），请重新提问。";
                log.warn("[Agent] 本轮正文为空，按失败收口 messageId={}", messageId);
            }
        }

        // 【真实 API 测试发现】模型返回 finish_reason=length 时，OpenAI 兼容实现仍会
        // 走 onCompleteResponse，状态看起来是 COMPLETED，但正文其实被 max-tokens 截断
        // （实测落库末尾停在表格的 "| "）。必须明确告诉用户，不能静默当成功。
        if (status == AgentEnums.MessageStatus.COMPLETED
                && response != null && response.finishReason() == FinishReason.LENGTH) {
            String notice = "\n\n> 注意：本次回答达到模型单轮输出上限（max-tokens="
                    + properties.getModel().getMaxTokens()
                    + "），内容可能不完整。可以让我继续，或把问题拆得更具体。";
            publish(messageId, sequence, AgentEnums.StreamEvent.DELTA, Map.of("text", notice));
            answer.append(notice);
            log.warn("[Agent] 模型因输出上限截断 messageId={} finishReason=LENGTH maxTokens={}",
                    messageId, properties.getModel().getMaxTokens());
        }

        int tokensIn = 0;
        int tokensOut = 0;
        if (response != null && response.tokenUsage() != null) {
            tokensIn = response.tokenUsage().inputTokenCount() == null
                    ? 0 : response.tokenUsage().inputTokenCount();
            tokensOut = response.tokenUsage().outputTokenCount() == null
                    ? 0 : response.tokenUsage().outputTokenCount();
        } else {
            // 部分兼容实现不返回 usage，用长度做粗略估算（宁可估也不留空，便于成本观测）
            tokensOut = Math.max(1, answer.length() / 2);
        }
        long latency = System.currentTimeMillis() - start;

        // 一轮结束的写入同样合并为一次提交：终态回写 + 会话计数 + 用量累加
        conversationService.finishTurn(messageId, conversationId, answer.toString(),
                thinking.toString(), status, blockReason, tokensIn, tokensOut, latency,
                firstTokenMs, user.getUserId(), toolCalls, ragHits,
                status == AgentEnums.MessageStatus.BLOCKED, newConversation, model,
                currentReferences());
        chatMemoryStore.endTurn(conversationId);

        streamBuffer.setState(messageId, status == AgentEnums.MessageStatus.BLOCKED
                ? com.overseas.dorm.common.enums.StreamState.BLOCKED
                : com.overseas.dorm.common.enums.StreamState.DONE);
        int finalTokensIn = tokensIn;
        int finalTokensOut = tokensOut;
        publish(messageId, sequence, AgentEnums.StreamEvent.DONE, Map.of(
                "messageId", messageId,
                "model", model,
                "agent", agentType.name(),
                "tokensIn", finalTokensIn,
                "tokensOut", finalTokensOut,
                "latencyMs", latency,
                "firstTokenMs", firstTokenMs,
                "toolCalls", toolCalls,
                "ragHits", ragHits,
                "status", status.name()));

        java.util.Map<String, Object> auditDetail = new java.util.LinkedHashMap<>();
        auditDetail.put("agent", agentType.name());
        auditDetail.put("model", model);
        auditDetail.put("status", status.name());
        auditDetail.put("tokens", finalTokensIn + finalTokensOut);
        auditDetail.put("toolCalls", toolCalls);
        auditDetail.put("ragHits", ragHits);
        auditDetail.put("latencyMs", latency);
        auditService.audit(true, AuditEnums.Module.AGENT, "AGENT_TURN",
                "AGENT_MESSAGE", messageId, null, auditDetail, latency);

        log.info("[Agent] 本轮完成 messageId={} agent={} model={} 耗时={}ms 首字={}ms tokens={}+{} 工具={} 引用={} 状态={}",
                messageId, agentType, model, latency, firstTokenMs, finalTokensIn, finalTokensOut,
                toolCalls, ragHits, status);
        closeChannel(messageId);
    }

    /**
     * 用户主动打断的收尾（【H10 修复】新增）。
     *
     * <p>与"超时/异常"路径的区别，也是这一条缺陷的要害：
     * <ul>
     *   <li>状态必须是 {@code INTERRUPTED}（不是 {@code COMPLETED}），否则用户看到一份"成功"的回答；</li>
     *   <li>已生成的部分要保留（用户等了几十秒的内容不能因为点了停止就丢掉），
     *       但要通过终态事件与落库状态<b>明确标记为已打断</b>，而不是静默截断；</li>
     *   <li>审计记 {@code AGENT_TURN_STOPPED}（不是 {@code AGENT_TURN} 的成功），
     *       原因串写"用户主动停止生成"，绝不能借用超时文案（否则排障被带偏）；</li>
     *   <li>不递增任何"失败/重试"指标：用户主动停止不是系统故障。</li>
     * </ul>
     *
     * <p>线程安全：快照在 {@code turnLock} 下读取，此刻可能仍有模型回调在追加正文，
     * 不互斥读取 {@link StringBuilder} 会读到撕裂的内容。
     */
    private void finishInterrupted(SseEmitter emitter, Long messageId, Long conversationId,
                                   StringBuilder answer, StringBuilder thinking,
                                   java.util.concurrent.locks.ReentrantLock turnLock,
                                   long start, boolean newConversation, LoginUser user,
                                   int toolCalls, int ragHits, String model, AtomicLong sequence) {
        String partial;
        String thinkingSnapshot;
        turnLock.lock();
        try {
            partial = answer == null ? "" : answer.toString();
            thinkingSnapshot = thinking == null ? "" : thinking.toString();
        } finally {
            turnLock.unlock();
        }
        long latency = System.currentTimeMillis() - start;
        String reason = "用户主动停止生成";
        log.info("[Agent] 用户主动停止，立即收口 messageId={} 已生成 {} 字 耗时={}ms",
                messageId, partial.length(), latency);

        conversationService.finishTurn(messageId, conversationId, partial, thinkingSnapshot,
                AgentEnums.MessageStatus.INTERRUPTED, reason,
                // token 用量按已生成内容粗估（真实 usage 在模型尚未收流时拿不到）
                0, Math.max(1, partial.length() / 2), latency, 0L,
                user.getUserId(), toolCalls, ragHits, false, newConversation, model,
                currentReferences());
        chatMemoryStore.endTurn(conversationId);

        streamBuffer.setState(messageId, com.overseas.dorm.common.enums.StreamState.INTERRUPTED);
        publish(messageId, sequence, AgentEnums.StreamEvent.INTERRUPTED, Map.of(
                "messageId", messageId,
                "status", AgentEnums.MessageStatus.INTERRUPTED.name(),
                "model", model == null ? "" : model,
                "partialChars", partial.length(),
                "latencyMs", latency,
                "toolCalls", toolCalls,
                "ragHits", ragHits,
                // 这句会显示给用户：必须让"已停止"和"已完成"在界面上可区分
                "notice", "已停止生成。已生成的部分保留在下方，你可以继续追问或让我重新回答。"));
        auditService.audit(false, AuditEnums.Module.AGENT, "AGENT_TURN_STOPPED",
                "AGENT_MESSAGE", messageId, reason);
        closeChannel(messageId);
    }

    /** 在轮次锁下执行（取消收口 与 模型回调追加正文 之间的互斥，见 turnLock 的说明）。 */
    private static void underTurnLock(java.util.concurrent.locks.ReentrantLock lock, Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /** 异常收尾：保留半成品内容，便于用户重连查看与后续追问。 */
    private void finishWithError(SseEmitter emitter, Long messageId, Long conversationId,
                                 ContentGuardrail.StreamGuard guard, StringBuilder answer,
                                 StringBuilder thinking, Throwable error, long start,
                                 boolean newConversation, LoginUser user, int toolCalls, int ragHits,
                                 String modelName, AtomicLong sequence) {
        long latency = System.currentTimeMillis() - start;
        boolean cancelled = shouldStop(messageId);
        String userMessage;
        AgentEnums.MessageStatus status;

        if (cancelled) {
            status = AgentEnums.MessageStatus.INTERRUPTED;
            userMessage = "已停止生成。你可以继续追问，或让我重新回答。";
            // 【M12(a) 修复】原先落库的 block_reason 用的是 error.getMessage()，
            // 于是"用户主动停止"会带着超时文案（实测：「生成超时（超过 8 秒）…」）落库，
            // 与 status=INTERRUPTED 自相矛盾，排障时被完全带偏。
            // 取消就是取消：原因串固定，与任何 error 无关。
        } else if (isToolLoopExceeded(error)) {
            // 弱模型常见行为：反复换关键词调同一个工具，直到撞上往返轮数上限。
            // 这对用户来说不该表现为"系统异常" —— 我们已经拿到了部分工具结果，
            // 应该就地收口，把已有的信息交付出去，并说清楚可以怎么继续。
            status = AgentEnums.MessageStatus.COMPLETED;
            userMessage = "我在查询时反复调整了检索条件，已经拿到部分结果。"
                    + "为避免继续消耗时间，先就现有信息回答；如果你需要更精确的结果，"
                    + "可以把条件说得更具体一些（例如明确的区域、日期或单号）。";
            log.warn("[Agent] 工具调用往返超出上限，已优雅收口 messageId={}", messageId);
            // 【M5 修复（第二条路径）】"优雅收口"只有在确实有正文时才算成功。
            // 独立验证指出这个分支与 finishSuccessfully 都会在 answer 为空时写 COMPLETED：
            // 实测 604 条 COMPLETED 里 32 条正文为空（5.3%），用户看到"成功但空白"的气泡，
            // 既没内容也看不出失败，只能反复重问。
            // 弱模型反复调工具直到撞上限、却什么正文都没产出，就是这种情形 ——
            // 那不叫"优雅收口"，应当如实告诉用户没拿到内容。
            // 此处 answer 已包含全部原始 token（<c>guard.finish()</c> 尚未调用），
            // 因此只判它即可，不需要也无法在此处预取待发送的末段。
            if (answer == null || answer.toString().isBlank()) {
                status = AgentEnums.MessageStatus.FAILED;
                userMessage = "我在查询时反复调整了检索条件，但没能得到可用内容。"
                        + "可以把条件说得更具体一些（例如明确的区域、日期或单号）再试一次。";
                log.warn("[Agent] 工具往返超限且正文为空，按失败收口 messageId={}", messageId);
            }
        } else if (error instanceof BizException biz) {
            status = AgentEnums.MessageStatus.FAILED;
            userMessage = biz.getMessage();
        } else {
            status = AgentEnums.MessageStatus.FAILED;
            userMessage = "生成过程中出现异常，请稍后重试。如果反复失败，可联系系统管理员并提供追踪号。";
        }

        try {
            ContentGuardrail.GuardResult tail = guard.finish();
            // 只推送、不追加到 answer —— answer 已经包含这些原始 token，
            // 再追加会让落库内容多出一个安全窗口（详见 finishSuccessfully 的说明）。
            if (!tail.blocked() && !tail.emit().isEmpty()) {
                publish(messageId, sequence, AgentEnums.StreamEvent.DELTA, Map.of("text", tail.emit()));
            }
        } catch (Exception ignored) {
            // 收尾阶段的护栏异常不应覆盖原始错误
        }

        conversationService.finishTurn(messageId, conversationId, answer.toString(),
                thinking.toString(), status,
                cancelled ? "用户主动停止生成"
                        : (error == null ? null : String.valueOf(error.getMessage())),
                0, Math.max(1, answer.length() / 2), latency, 0L,
                user.getUserId(), toolCalls, ragHits, false, newConversation, modelName,
                currentReferences());
        chatMemoryStore.endTurn(conversationId);

        // 终态事件要与真实状态一致：
        // 「工具往返超限但已优雅收口」属于正常结束，必须发 DONE；
        // 如果这里发成 ERROR，前端会把一次可用的回答渲染成红色的错误提示，
        // 而重连逻辑也会认为这一轮失败了。
        boolean graceful = status == AgentEnums.MessageStatus.COMPLETED;
        StreamState state = cancelled ? StreamState.INTERRUPTED
                : (graceful ? StreamState.DONE : StreamState.FAILED);
        streamBuffer.setState(messageId, state);
        AgentEnums.StreamEvent terminalEvent = cancelled ? AgentEnums.StreamEvent.INTERRUPTED
                : (graceful ? AgentEnums.StreamEvent.DONE : AgentEnums.StreamEvent.ERROR);
        Map<String, Object> terminalPayload = new LinkedHashMap<>();
        terminalPayload.put("messageId", messageId);
        terminalPayload.put("message", userMessage);
        terminalPayload.put("status", status.name());
        terminalPayload.put("graceful", graceful);
        terminalPayload.put("partialLength", answer.length());
        terminalPayload.put("latencyMs", latency);
        terminalPayload.put("toolCalls", toolCalls);
        terminalPayload.put("ragHits", ragHits);
        terminalPayload.put("traceId", TraceContext.getOrCreate());
        if (graceful) {
            // 优雅收口本质上是一次成功回答，必须给出与正常路径一致的字段，
            // 否则前端要把同一件事分成两套渲染逻辑（很容易漏掉统计信息）
            terminalPayload.put("model", modelName);
            terminalPayload.put("tokensIn", 0);
            terminalPayload.put("tokensOut", Math.max(1, answer.length() / 2));
        }
        publish(messageId, sequence, terminalEvent, terminalPayload);

        // 审计终态必须与 SSE/消息终态一致：优雅收口是“降级成功”，不是 FAIL。
        java.util.Map<String, Object> auditDetail = new java.util.LinkedHashMap<>();
        auditDetail.put("status", status.name());
        auditDetail.put("graceful", graceful);
        auditDetail.put("toolCalls", toolCalls);
        auditDetail.put("ragHits", ragHits);
        auditDetail.put("partialLength", answer.length());
        auditService.audit(graceful, AuditEnums.Module.AGENT,
                cancelled ? "AGENT_TURN_STOPPED"
                        : (graceful ? "AGENT_TURN_DEGRADED" : "AGENT_TURN_FAILED"),
                "AGENT_MESSAGE", messageId,
                error == null ? "用户打断" : error.getClass().getSimpleName() + ": " + error.getMessage(),
                auditDetail, latency);
        closeChannel(messageId);
    }

    /**
     * 判断是否为「工具调用往返轮数超限」。
     *
     * <p>LangChain4j 在超过 {@code maxToolCallingRoundTrips} 时抛的是普通
     * {@code RuntimeException}，消息形如
     * {@code Something is wrong, exceeded 3 tool calling round trips}。
     * 这里按关键词识别而不是按异常类型，是因为框架在版本间可能调整类型，
     * 但这类语义化文案相对稳定；同时再做一次大小写无关匹配以增强健壮性。
     */
    private boolean isToolLoopExceeded(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }
        String message = error.getMessage().toLowerCase();
        return message.contains("tool calling round trips")
                || message.contains("toolcallingroundtrips")
                || message.contains("maxsequentialtoolsinvocations");
    }

    /** 幂等重放：把已有回答按 buffer 格式重放一遍。 */
    /**
     * 起一条重放连接（不占配额：调用点在第 2 步幂等预检，早于限流与并发计数）。
     *
     * <p>重放的清理不递减 user/global 并发计数，因为这条路径从未申请过它们。
     */
    private SseEmitter startReplay(AgentMessage existing, LoginUser user, String notice) {
        log.info("[Agent] 检测到重复轮次 turnId={}，重放既有回答 messageId={}（不消耗限流配额）",
                existing.getTurnId(), existing.getId());
        SseEmitter emitter = newEmitter();
        SseChannel channel = new SseChannel(existing.getId(), emitter,
                properties.getStream().getChannelQueueCapacity(), "replay");
        registerChannel(existing.getId(), channel);
        // 重放同样跑在专用线程池上，必须显式带上身份上下文 ——
        // 否则线程里读 UserContext 会抛 401（表现为一个未捕获异常）。
        try {
            streamExecutor.execute(() -> UserContext.runAs(user, () ->
                    replayThenComplete(emitter, existing, notice)));
            return emitter;
        } catch (RuntimeException e) {
            // 提交失败（线程池已关闭 / 队列满）时回滚注册，避免重放通道泄漏
            unregisterChannel(existing.getId(), channel);
            channel.close();
            throw e;
        }
    }

    private void replayThenComplete(SseEmitter emitter, AgentMessage existing, String notice) {
        try {
            String content = existing.getContent() == null ? "" : existing.getContent();
            publish(existing.getId(), AgentEnums.StreamEvent.START, Map.of(
                    "messageId", existing.getId(),
                    "conversationId", existing.getConversationId(),
                    "replayed", true,
                    "notice", notice));
            if (existing.getReasoningContent() != null && !existing.getReasoningContent().isBlank()) {
                publish(existing.getId(), AgentEnums.StreamEvent.REASONING,
                        Map.of("text", existing.getReasoningContent()));
            }
            // 按 40 字符一段模拟流式重放，让前端体验与首次一致（且不重复计费）
            int step = 40;
            for (int i = 0; i < content.length(); i += step) {
                String slice = content.substring(i, Math.min(content.length(), i + step));
                publish(existing.getId(), AgentEnums.StreamEvent.DELTA, Map.of("text", slice));
            }
            publish(existing.getId(), AgentEnums.StreamEvent.DONE, Map.of(
                    "messageId", existing.getId(),
                    "replayed", true,
                    "status", existing.getStatus() == null ? "COMPLETED" : existing.getStatus().name()));
        } catch (Exception e) {
            log.warn("[Agent] 重放失败 messageId={}: {}", existing.getId(), e.getMessage());
        } finally {
            // 注意：这里不递减并发计数 —— 重放路径在限流/计数之前就返回了，
            // 从未 increment。递减会造成计数变成负数，进而放宽别人的并发上限
            // （一个很隐蔽的"越用越宽松"缺陷）。
            closeChannel(existing.getId());
        }
    }

    // ================================================================ 基础设施

    private SseEmitter newEmitter() {
        // 0 = 不主动超时：长连接的关闭由业务（终态事件）或客户端决定。
        // 心跳负责让中间的代理不因空闲而断开。
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onTimeout(() -> log.debug("[SSE] 连接超时"));
        emitter.onError(e -> log.debug("[SSE] 连接异常：{}", e.getMessage()));
        return emitter;
    }

    /**
     * 实例级心跳 tick。
     *
     * 改造前每条连接都会注册一个 {@code scheduleWithFixedDelay} 心跳任务，
     * 1000 条连接就是 1000 个任务对象挂在 4 个线程的调度器上；
     * 更要命的是任务体里直接 {@code emitter.send(comment)} 是<b>阻塞写</b>，* 4 个慢客户端就能占满 4 个调度线程，让<b>所有</b>连接的心跳停掉，
     * 进而被中间代理批量断连。
     *
     * <p>现在只有一个 tick：遍历在线连接，把 ping 帧<b>非阻塞入队</b>（各自通道负责写）。
     * 无论 1000 条还是 10000 条连接，任务数恒为 1，且 tick 本身永不阻塞。
     */
    @jakarta.annotation.PostConstruct
    public void startInstanceTicks() {
        int seconds = Math.max(properties.getStream().getHeartbeatSeconds(), 1);
        streamScheduler.scheduleWithFixedDelay(this::heartbeatTick, Duration.ofSeconds(seconds));
        // 看门狗独立频率：它决定"断线多久后中止生成"，不该被心跳周期牵着走
        streamScheduler.scheduleWithFixedDelay(this::orphanWatchTick,
                Duration.ofSeconds(Math.max(ORPHAN_GRACE_SECONDS, 5)));
        log.info("[SSE] 实例级 tick 已启动：心跳 {}s / 看门狗 {}s（每连接零定时任务）",
                seconds, Math.max(ORPHAN_GRACE_SECONDS, 5));
    }

    private void heartbeatTick() {
        for (Map.Entry<Long, Set<SseChannel>> entry : channels.entrySet()) {
            Long messageId = entry.getKey();
            // 续期 Redis 订阅键：长回答可能远超订阅键 TTL，不续期会被误判"无人接收"
            // （同一条消息上有多条连接时只续期一次：EXPIRE 作用于整个订阅集合键，
            //   对集合里的所有成员同时生效）
            if (hasLocalSubscriber(messageId)) {
                streamBuffer.touchSubscriber(messageId, SUBSCRIBER_TTL);
            }
            // 遍历快照：集合可能被注册/注销路径并发修改
            for (SseChannel channel : new ArrayList<>(entry.getValue())) {
                if (channel.closed()) {
                    unregisterChannel(messageId, channel);
                    if (channelsOf(messageId).isEmpty()) {
                        markOrphanedIfGenerating(messageId);
                    }
                    continue;
                }
                if (!channel.offerHeartbeat()) {
                    // 入队失败意味着该连接队列已溢出，通道内部已断开它。
                    // 只有"这是最后一条连接"时才标记孤儿；否则会把仍在看的
                    // 其它连接一起拖进 30 秒后的中止。
                    unregisterChannel(messageId, channel);
                    if (channelsOf(messageId).isEmpty()) {
                        markOrphanedIfGenerating(messageId);
                    }
                }
            }
        }
    }

    /**
     * 实例级看门狗 tick：断线超过宽限期且无人重连时中止生成，避免白烧额度。
     *
     * <p>同样从"每连接一个任务"改为单 tick，并通过 Redis 订阅数判断
     * "是否还有人在接收"（这样客户端重连到另一个实例也能被正确识别）。
     */
    private void orphanWatchTick() {
        reapIdleChannels();
        for (Map.Entry<Long, AtomicBoolean> entry : orphaned.entrySet()) {
            Long messageId = entry.getKey();
            if (!entry.getValue().get()) {
                continue;
            }
            if (streamBuffer.isTerminal(streamBuffer.state(messageId))) {
                orphaned.remove(messageId);
                continue;
            }
            if (streamBuffer.subscriberCount(messageId) == 0) {
                log.info("[SSE] messageId={} 断线超过 {}s 且无人重连，中止生成以节省额度",
                        messageId, ORPHAN_GRACE_SECONDS);
                streamBuffer.markCancelled(messageId);
                orphaned.remove(messageId);
            }
        }
    }

    /**
     * 断线看门狗。
     *
     * <p>客户端断开后，我们不会立刻停止生成（用户可能只是切了个后台），
     * 但也不能无限生成下去。这里每隔一段时间检查一次：
     * 若宽限期内始终没有新的订阅者，就设置取消标记，
     * 生成循环会在下一个 token 处停下来。
     */

    private void markOrphaned(Long messageId) {
        orphaned.computeIfAbsent(messageId, k -> new AtomicBoolean(false)).set(true);
    }

    /**
     * 在轮次上下文里执行回调体。
     *
     * <p>LangChain4j 的流式回调运行在模型 HTTP 客户端的读流线程，而不是我们发起调用的线程。
     * 本项目用 {@code VirtualThreadJdkHttpClient} 把它固定成专用虚拟线程
     * （{@code agent-model-stream-*}），不再落到 {@code ForkJoinPool.commonPool}；
     * 但“不是发起调用的线程”这一点没有变，所以每个回调仍要显式恢复
     * 「用户身份 + traceId + 轮次上下文」三样东西，否则：
     * 审计缺 trace、工具去重失效、事件里的 traceId 张冠李戴。
     */
    private void inTurn(AgentTurnContext context, Runnable body) {
        if (context == null) {
            body.run();
            return;
        }
        AgentTurnContext.runAs(context, () ->
                TraceContext.runWith(context.traceId(), () ->
                        UserContext.runAs(context.user(), body)));
    }

    /** 生成循环在每处理一个 token 前检查：是否被用户打断 / 是否已孤儿化。 */
    /**
     * 是否需要停止本轮。
     *
     * <p>每个 token 都查一次 Redis {@code EXISTS} 是 P1-6 里最容易被忽略的
     * 往返：1000 并发 × 20 token/s ≈ 2 万次/秒。这里做两级缓存：
     * <ul>
     *   <li>本实例 {@code stop()} 直接写 {@code locallyCancelled}，零延迟；</li>
     *   <li>跨实例的 Redis 标记最多每 {@link #CANCEL_CHECK_INTERVAL_MS} 查一次，
     *       停止指令的最坏延迟 500ms（远小于一个 token 的生成时间）。</li>
     * </ul>
     */
    private boolean shouldStop(Long messageId) {
        if (locallyCancelled.contains(messageId)) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long lastCheck = lastCancelCheckAt.get(messageId);
        if (lastCheck != null && now - lastCheck < CANCEL_CHECK_INTERVAL_MS) {
            return false;
        }
        lastCancelCheckAt.put(messageId, now);
        boolean cancelled = streamBuffer.isCancelled(messageId);
        if (cancelled) {
            locallyCancelled.add(messageId);
        }
        return cancelled;
    }

    private void publish(Long messageId, AgentEnums.StreamEvent event, Object payload) {
        long seq = streamBuffer.lastSeq(messageId) + 1;
        publish(messageId, seq, event, payload);
    }

    private void publish(Long messageId, AtomicLong sequence,
                         AgentEnums.StreamEvent event, Object payload) {
        publish(messageId, sequence.incrementAndGet(), event, payload);
    }

    /**
     * 发布一帧：<b>先写 Redis 缓冲（保证可续传），再非阻塞入队到本连接的通道</b>。
     *
     * <p>注意这里不再直接写 socket。改造前这一步是阻塞写，一个不读数据的客户端
     * 会占住一个生成线程；现在只是入队，队列满则断开该客户端（可重连续传），
     * 生产者永远不会被拖住。
     */
    private void publish(Long messageId, long seq, AgentEnums.StreamEvent event, Object payload) {
        Map<String, Object> body = payload instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        body.put("messageId", messageId);
        body.put("traceId", TraceContext.get());
        streamBuffer.publish(messageId, seq, event.name(), body);
        pushFrame(messageId, seq, event.name(), JsonUtil.toJson(body));
    }

    /**
     * 把一帧推给该消息上的全部在线连接。
     *
     * <p>找不到通道（连接已关闭/从未建立）不是错误：事件已经进了 Redis 缓冲，
     * 客户端重连时按 lastEventId 补齐即可。
     *
     * <p>多连接并存后语义随之变化：初始/重放/续传连接都会收到这里的直投，
     * 续传连接还会从 Redis Pub/Sub 收到同一帧。重复与乱序由
     * {@link SseChannel#offer} 的 seq 判重 + {@code pendingBySeq} 重排兜住：
     * 较大的 seq 先到会先缓存，缺口补齐后按序写出。
     *
     * @return 是否至少有一条连接接收成功
     */
    private boolean pushFrame(Long messageId, long seq, String name, String data) {
        Set<SseChannel> set = channels.get(messageId);
        if (set == null || set.isEmpty()) {
            // 没有在线连接：如果还在生成，让看门狗在宽限期后中止，而不是干烧到 90s 超时
            markOrphanedIfGenerating(messageId);
            return false;
        }
        boolean anyAccepted = false;
        boolean overflowed = false;
        for (SseChannel channel : new ArrayList<>(set)) {
            if (channel.closed()) {
                continue;
            }
            if (channel.offer(String.valueOf(seq), name, data)) {
                anyAccepted = true;
            } else {
                // 入队失败 = 该客户端已经跟不上（队列溢出已由通道记录并断开）
                overflowed = true;
            }
        }
        if (overflowed && !anyAccepted) {
            // 一条都没投出去，说明这条流实际上已经没有可用的接收方
            markOrphaned(messageId);
        }
        return anyAccepted;
    }

    /**
     * 关闭该消息上的全部连接（先把各自队列里剩余的帧写完，保证用户能看到完整结果）。
     *
     * <p>轮次结束时调用：此刻所有还在看的连接都应该收到终态事件并正常关闭。
     * 幂等，重复调用只会遍历到一个空集合。
     */
    private void closeChannel(Long messageId) {
        for (SseChannel channel : channelsOf(messageId)) {
            unregisterChannel(messageId, channel);
            channel.drainAndComplete();
        }
    }

    // ---------------------------------------------------------------- 连接注册表

    /**
     * 登记一条在线连接。
     *
     * <p>用 {@code compute} 而不是"先 get 再 put"：后者在前两条连接同时接入时
     * 会丢掉其中一条（各建一个集合、后写的覆盖先写的），表现正是 H3 那类
     * "连接莫名其妙收不到东西"。
     */
    private void registerChannel(Long messageId, SseChannel channel) {
        channels.compute(messageId, (id, existing) -> {
            Set<SseChannel> set = existing == null ? ConcurrentHashMap.newKeySet() : existing;
            set.add(channel);
            return set;
        });
    }

    /** 注销一条在线连接；该消息上已无连接时移除键，避免注册表无限增长。 */
    private void unregisterChannel(Long messageId, SseChannel channel) {
        channels.computeIfPresent(messageId, (id, set) -> {
            set.remove(channel);
            return set.isEmpty() ? null : set;
        });
    }

    private void addLocalSubscriber(Long messageId, String subscriberId) {
        localSubscriberIds.computeIfAbsent(messageId, id -> ConcurrentHashMap.newKeySet())
                .add(subscriberId);
    }

    /**
     * 只移除本连接自己的订阅者 ID。
     *
     * <p>不能用 {@code localSubscriberIds.remove(messageId)}：同一 messageId 上可能同时
     * 有生成连接与多条续传连接，无条件删除会把其它仍在线的连接一起"遗忘"，
     * 心跳不再续期订阅键，看门狗 30 秒后误杀生成。
     */
    private void removeLocalSubscriber(Long messageId, String subscriberId) {
        localSubscriberIds.computeIfPresent(messageId, (id, subscribers) -> {
            subscribers.remove(subscriberId);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    private boolean hasLocalSubscriber(Long messageId) {
        Set<String> subscribers = localSubscriberIds.get(messageId);
        return subscribers != null && !subscribers.isEmpty();
    }

    /**
     * 最后一条连接消失后，如果本轮仍在生成，就打上"孤儿"标记。
     *
     * <p>真正的中止由 {@link #orphanWatchTick()} 在宽限期后执行；这里只负责把
     * "客户端已经干净断开"这个事实告诉看门狗。之前只在 pushFrame 投递失败时标记，
     * 而干净断开时 pushFrame 往往还能把帧塞进入队缓冲区，于是 30 秒宽限形同虚设，
     * 生成会一直跑到 90 秒超时。
     */
    private void markOrphanedIfGenerating(Long messageId) {
        if (Boolean.TRUE.equals(orphaned.get(messageId))) {
            return;
        }
        try {
            if (streamBuffer.state(messageId) == StreamState.GENERATING) {
                markOrphaned(messageId);
            }
        } catch (Exception e) {
            log.debug("[SSE] 判断孤儿状态失败 messageId={}: {}", messageId, e.getMessage());
        }
    }

    /** 取该消息上当前全部连接的时间点快照（遍历期间不受并发注册/注销影响）。 */
    private List<SseChannel> channelsOf(Long messageId) {
        Set<SseChannel> set = channels.get(messageId);
        return set == null || set.isEmpty() ? List.of() : new ArrayList<>(set);
    }


    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("[SSE] 资源关闭异常：{}", e.getMessage());
        }
    }

    /** 校验消息归属，防止通过猜 messageId 读取他人的回答。 */
    private AgentMessage findAssistantMessage(Long messageId, Long userId) {
        if (messageId == null) {
            return null;
        }
        AgentMessage message;
        try {
            message = messageMapper.selectById(messageId);
        } catch (Exception e) {
            log.warn("[Agent] 查询消息失败 messageId={}: {}", messageId, e.getMessage());
            return null;
        }
        if (message == null) {
            return null;
        }
        // 归属校验：只有会话主人才能续传/打断自己的对话
        AgentConversation conversation = conversationService.requireOwned(message.getConversationId(), userId);
        return conversation == null ? null : message;
    }

    /** 工具名 -> 面向用户的中文标签，用于前端展示"正在做什么"。 */
    /**
     * 回收"假死"连接。
     *
     * <p>心跳每 15 秒必然写出一帧，因此空闲超过阈值只可能是两种情况：
     * 客户端 TCP 已断但我们还没察觉，或者写入器卡在一个不再读取的 socket 上。
     * 两种情况都应该断开该连接（前端会重连续传），把虚拟线程与队列内存释放掉，* 否则 1000 条连接的场景下，假死连接会慢慢累积成资源泄漏。
     */
    private void reapIdleChannels() {
        long idleLimit = properties.getStream().getChannelIdleTimeoutSeconds() * 1000L;
        for (Map.Entry<Long, Set<SseChannel>> entry : channels.entrySet()) {
            Long messageId = entry.getKey();
            for (SseChannel channel : new ArrayList<>(entry.getValue())) {
                if (channel.closed()) {
                    // 已收尾的连接（客户端已断开等）：顺手从注册表摘掉，
                    // 否则多连接场景下注册表会被这些"僵尸条目"慢慢撑大
                    unregisterChannel(messageId, channel);
                    if (channelsOf(messageId).isEmpty()) {
                        markOrphanedIfGenerating(messageId);
                    }
                    continue;
                }
                if (channel.idleMillis() > idleLimit) {
                    log.info("[SSE] 连接 {} 已空闲 {}ms（超阈值 {}ms），判定为假死并断开",
                            messageId, channel.idleMillis(), idleLimit);
                    unregisterChannel(messageId, channel);
                    // close() 只投递哨兵，不打断可能正卡在 emitter.send 的写入器；
                    // 假死连接需要 completeWithError 去关闭底层响应，才能把虚拟线程
                    // 从 socket 写里解放出来。这是 best-effort，仍受内核 TCP 超时约束。
                    channel.completeWithError(new java.util.concurrent.TimeoutException(
                            "SSE 连接空闲超过 " + idleLimit + "ms"));
                    if (channelsOf(messageId).isEmpty()) {
                        markOrphanedIfGenerating(messageId);
                    }
                }
            }
        }
    }

    /** 工具名 → 中文标签；映射统一维护在 {@link com.overseas.dorm.agent.tool.ToolCatalog}。 */
    private static String toolLabel(String toolName) {
        return com.overseas.dorm.agent.tool.ToolCatalog.labelOf(toolName);
    }
}
