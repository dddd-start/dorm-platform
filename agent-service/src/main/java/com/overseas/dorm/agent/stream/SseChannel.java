package com.overseas.dorm.agent.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条 SSE 连接的有界出站通道（write-behind）。
 *
 * <h2>为什么必须有这一层</h2>
 * 改造前，SSE 帧是**由生产者线程直接写出去的**：
 * <ul>
 *   <li>生成线程（原线程池上限 64）在每次 publish 时调用 {@code emitter.send(...)}；</li>
 *   <li>Redis Pub/Sub 的监听回调也直接调用 {@code emitter.send(...)}；</li>
 *   <li>心跳任务同样直接写。</li>
 * </ul>
 * {@code emitter.send()} 是<b>阻塞写</b>：客户端不读（弱网、切后台、假死）时，
 * TCP 发送窗口填满，写线程就一直卡在那里。后果是<b>队头阻塞（head-of-line blocking）</b>：
 * <ol>
 *   <li>一个慢客户端占住一个生成线程 —— 64 个慢客户端就能让整个实例停止生成；</li>
 *   <li>Pub/Sub 监听容器默认只有一个分发线程 —— <b>一个慢客户端就能让全实例的续传全部停摆</b>；</li>
 *   <li>心跳任务共用一个 4 线程调度器 —— 4 个慢客户端就能让所有连接的心跳停掉。</li>
 * </ol>
 * 这三条在 30 并发下完全看不出来，到 1000 并发就是"请求把容器 hang 住"。
 *
 * <h2>改造后的模型</h2>
 * <pre>
 *   生产者（生成 / Pub-Sub / 心跳）── offer() ──▶ 有界队列 ──▶ 本连接专属写入器（虚拟线程）──▶ emitter.send()
 *                 非阻塞、永不等待                  满则丢弃连接
 * </pre>
 * 关键性质：
 * <ul>
 *   <li><b>生产者永不阻塞</b>：{@link #offer} 是纯内存入队，队列满时立刻返回 false，
 *       由调用方决定"放弃这条连接"（而不是拖垮整台机器）；</li>
 *   <li><b>连接之间互不影响</b>：每个连接有自己的写入器，一个慢客户端只卡住它自己的
 *       虚拟线程（约几 KB 内存），不影响其它 999 条连接；</li>
 *   <li><b>写入器用虚拟线程</b>：1000 条连接 = 1000 个虚拟线程，而不是 1000 个平台线程
 *       （平台线程每个 1MB 栈，1000 个就是 1GB 栈内存，这才是"容器被打挂"的直接原因）；</li>
 *   <li><b>内存有界</b>：队列容量固定，最坏内存 = 连接数 × 容量 × 单帧大小，可精确估算。</li>
 * </ul>
 *
 * <h2>慢客户端为什么要"丢弃"而不是等待</h2>
 * 断线可续传（Redis Stream 保存了完整事件历史，前端带 lastEventId 重连即可补齐），
 * 因此<b>放弃一条卡住的连接是安全的</b>，而让它拖住共享资源是不安全的。
 * 这就是高并发长连接服务的核心权衡：宁可断开一个用户让他重连，
 * 也不能让一个用户影响其余 999 个。
 */
@Slf4j
public final class SseChannel implements AutoCloseable {

    /** 队列中的一帧。comment 用于心跳注释帧（不触发前端 onmessage）。 */
    private record Frame(String id, String name, String data, String comment, boolean poison) {

        static Frame event(String id, String name, String data) {
            return new Frame(id, name, data, null, false);
        }

        static Frame heartbeat() {
            return new Frame(null, null, null, "ping", false);
        }

        /**
         * 收尾哨兵：由 {@link #close()} 入队。
         *
         * <p>用它而不是"给写入器线程发 interrupt"，是因为 interrupt 会让写入器
         * 立即退出循环，**队列里还没写出的帧会被丢掉** —— 这正是"客户端收不到终态事件"
         * 那类失败的根因（DONE 已入队，但连接先被关掉了）。
         * 哨兵保证写入器按顺序把哨兵之前的帧全部写完后才退出。
         */
        static Frame terminator() {
            return new Frame(null, null, null, null, true);
        }
    }

    private final Long messageId;
    private final SseEmitter emitter;
    private final BlockingQueue<Frame> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * 判重、乱序缓冲、入队、更新 lastWrittenSeq 必须在同一把锁里。
     *
     * <p>两条投递路径（生成线程直投、Redis Pub/Sub 转发）可能并发进入。
     * 改造前是 check-then-act：A 读到 lastWrittenSeq=5，B 也读到 5，
     * 两个都通过判重；更糟的是直投的 seq=10 可能晚于 Pub/Sub 的 seq=11，
     * 11 先入队后 10 会被判重丢弃，前端永久缺一段。
     *
     * <p>现在按 seq 连续入队：seq 大于下一个期望值时先放进 pending，
     * 等缺口补齐再按序写出；close() 时把 pending 按序强制刷出，保证终态不丢。
     */
    private final Object offerLock = new Object();
    /** 已收到但还不能写的乱序帧：seq → frame。 */
    private final java.util.TreeMap<Long, Frame> pendingBySeq = new java.util.TreeMap<>();
    /** 下一个期望写出的 seq；-1 表示等待第一帧来确定。 */
    private long nextSeq;
    /** 是否因为队列溢出而被判定为"跟不上"（用于看门狗与统计）。 */
    private final AtomicBoolean overflowed = new AtomicBoolean(false);
    private final AtomicLong written = new AtomicLong();

    /**
     * 本连接已写出的最大事件序号（防御性去重）。
     *
     * <h3>为什么还要保留判重</h3>
     * 改造前，同一实例的续传连接既有生成线程直投、又有 Redis Pub/Sub 转发，
     * 两条路径指向同一个连接，事件被投递两次、两个写者交错写 socket ——
     * 实测拼接文本翻倍（落库 137 字、拼接 274 字）。
     *
     * <p>现在两条路径仍然都存在（跨实例续传必须走 Pub/Sub，首次连接必须走直投），
     * 但 {@link #offer} 用 {@link #pendingBySeq} 按 seq 重排：
     * 较大的 seq 先到不会挤掉较小的 seq，缺口补齐后按序写出。
     * 判重仍必须保留：
     * <ul>
     *   <li>两条路径会把同一 seq 投递两次；</li>
     *   <li>Pub/Sub 在重连、跨实例切换时也可能重投；</li>
     *   <li>{@link #suppressedDuplicates()} 是"投递路径重复"的观测信号，
     *       静默丢弃比重复更可怕。</li>
     * </ul>
     *
     * <p>事件序号在一条流内单调且唯一，因此"序号不大于已写出的最大值"即为重复。
     */
    private final AtomicLong lastWrittenSeq = new AtomicLong(-1);

    /** 被去重抑制掉的重复帧数（暴露出来，避免"静默丢弃"）。 */
    private final AtomicLong suppressedDuplicates = new AtomicLong();

    /** 是否在队列写完后收尾（用于"溢出后优雅断开"：先让终端帧写出去，再关连接）。 */
    private final AtomicBoolean closingAfterDrain = new AtomicBoolean(false);
    private final AtomicLong droppedFrames = new AtomicLong();
    private volatile long lastWriteAt = System.currentTimeMillis();
    private final Thread writer;

    public SseChannel(Long messageId, SseEmitter emitter, int queueCapacity, String threadSuffix) {
        this(messageId, emitter, queueCapacity, threadSuffix, -1L);
    }

    /**
     * @param initialSeq 期望的第一帧序号；初始连接传 1，续传/重放传 -1（由第一帧决定）
     */
    public SseChannel(Long messageId, SseEmitter emitter, int queueCapacity,
                      String threadSuffix, long initialSeq) {
        this.messageId = messageId;
        this.emitter = emitter;
        this.nextSeq = initialSeq;
        this.queue = new ArrayBlockingQueue<>(Math.max(queueCapacity, 8));
        // 虚拟线程：栈在堆上、按需增长，1000 条连接的写入器总开销远小于平台线程
        this.writer = Thread.ofVirtual()
                .name("sse-writer-" + messageId + "-" + threadSuffix)
                .start(this::writeLoop);
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(e -> close());
    }

    /**
     * 非阻塞入队一帧。
     *
     * @return false 表示队列已满（调用方应放弃该连接，而不是等待）
     */
    public boolean offer(String id, String name, String data) {
        long seq = parseSeq(id);
        boolean accepted;
        synchronized (offerLock) {
            if (seq < 0) {
                // 心跳/注释帧不参与序号，直接入队
                accepted = enqueueLocked(Frame.event(id, name, data));
            } else if (nextSeq < 0) {
                // 续传/重放：第一帧决定起点
                nextSeq = seq;
                accepted = acceptInOrderLocked(seq, Frame.event(id, name, data));
            } else if (seq < nextSeq) {
                // 已经写过或已经被 close 的强制刷出跳过
                suppressedDuplicates.incrementAndGet();
                return true;
            } else if (seq > nextSeq) {
                // 乱序先缓存，等缺口补齐；同一 seq 重复到达时覆盖旧值
                pendingBySeq.put(seq, Frame.event(id, name, data));
                return true;
            } else {
                accepted = acceptInOrderLocked(seq, Frame.event(id, name, data));
            }
        }
        if (!accepted) {
            logOverflow();
        }
        return accepted;
    }

    /** 必须在 offerLock 内调用：写出 seq，并把后续连续的 pending 帧一起刷出。 */
    private boolean acceptInOrderLocked(long seq, Frame frame) {
        if (!enqueueLocked(frame)) {
            return false;
        }
        lastWrittenSeq.accumulateAndGet(seq, Math::max);
        nextSeq = seq + 1;
        while (true) {
            Frame next = pendingBySeq.remove(nextSeq);
            if (next == null) {
                break;
            }
            if (!enqueueLocked(next)) {
                break;
            }
            lastWrittenSeq.accumulateAndGet(nextSeq, Math::max);
            nextSeq++;
        }
        return true;
    }

    private static long parseSeq(String id) {
        if (id == null || id.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 被去重抑制的重复帧数（正常应为 0；持续增长说明有投递路径重复）。 */
    public long suppressedDuplicates() {
        return suppressedDuplicates.get();
    }

    /** 非阻塞入队一个心跳注释帧。 */
    public boolean offerHeartbeat() {
        boolean accepted;
        synchronized (offerLock) {
            accepted = enqueueLocked(Frame.heartbeat());
        }
        if (!accepted) {
            logOverflow();
        }
        return accepted;
    }

    /**
     * 必须在 {@link #offerLock} 内调用。
     *
     * <p>日志刻意放在锁外（{@link #logOverflow()}）：logback 的 appender 可能是
     * 同步写文件/网络，放在锁内会让所有生产者一起等 I/O。
     */
    private boolean enqueueLocked(Frame frame) {
        if (closed.get()) {
            return false;
        }
        if (queue.offer(frame)) {
            return true;
        }
        // 队列满 = 这个客户端已经跟不上生成速度（或根本没在读）。
        //
        // 保护动作必须做（不能让一个慢客户端拖垮生产者），但**不能静默截断**：
        // 改造前这里直接 close()，连接在没有终态事件的情况下被关掉 ——
        // 客户端只看到"流莫名其妙结束了"，不知道应该重连续传，
        // 表现为压测里"HTTP 200 但没有终态事件"的那一类失败。
        //
        // 现在的做法：丢弃最旧的一帧腾出位置，插入一个 INTERRUPTED 终端帧
        // （告知"你读得太慢，请带 lastEventId 重连"），然后让写入器把队列写完自然收尾。
        // 这样既保住了"不阻塞生产者"，也不再有静默截断。
        overflowed.set(true);
        droppedFrames.incrementAndGet();
        queue.poll();
        queue.offer(Frame.event(frame.id(), "INTERRUPTED", "{\"resumable\":true,"
                + "\"message\":\"客户端读取过慢，已停止推送；请带 lastEventId 重连以补齐内容。\"}"));
        // 用统一的优雅收尾：拒绝新帧 + 让写入器把 INTERRUPTED 写完后自行关闭
        close();
        // 返回 false 让调用方（生成线程）知道这条连接已经不可用，但生成会继续，
        // 完整回答仍会落库，用户重连即可续传拿到。
        return false;
    }

    private void logOverflow() {
        log.warn("[SSE] 连接出站队列已满 messageId={}，已丢弃 {} 帧并发送续传提示",
                messageId, droppedFrames.get());
    }

    private void writeLoop() {
        try {
            while (true) {
                Frame frame = queue.poll(30, TimeUnit.SECONDS);
                if (frame == null) {
                    // 空闲：若已请求收尾则退出，否则等下一帧（心跳由实例级 tick 入队）。
                    // 注意这里**不能**用 closed 作为退出条件 —— close() 会设置 closed
                    // 来拒绝新帧，但队列里已有的帧（尤其是终态 DONE）必须写完。
                    if (closingAfterDrain.get()) {
                        break;
                    }
                    continue;
                }
                if (frame.poison()) {
                    // 哨兵：它之前的帧都已写出，可以关连接了
                    break;
                }
                if (!write(frame)) {
                    break;
                }
                written.incrementAndGet();
                lastWriteAt = System.currentTimeMillis();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.debug("[SSE] 写入器异常终止 messageId={}: {}", messageId, t.getMessage());
        } finally {
            completeQuietly();
        }
    }

    private boolean write(Frame frame) {
        try {
            if (frame.comment() != null) {
                emitter.send(SseEmitter.event().comment(frame.comment()));
            } else {
                emitter.send(SseEmitter.event()
                        .id(frame.id())
                        .name(frame.name())
                        .data(frame.data()));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            // 客户端断开是常态（关页面、切网络），不作为错误上报
            log.debug("[SSE] 推送失败（连接可能已断开）messageId={}: {}", messageId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("[SSE] 推送异常 messageId={}: {}", messageId, e.getMessage());
            return false;
        }
    }

    /**
     * 本轮正常结束：请求优雅收尾。
     *
     * <p>刻意**不在这里排空队列** —— 队列的消费者只能是写入器线程。
     * 之前这个方法在调用方（生成）线程上轮询同一个队列，与写入器形成两个消费者，
     * 是"末帧丢失"竞态的另一半原因。
     */
    public void drainAndComplete() {
        close();
    }

    public void completeWithError(Throwable error) {
        abort();
        try {
            emitter.completeWithError(error);
        } catch (Exception ignored) {
            // 连接可能已经断开，忽略
        }
    }

    /**
     * 优雅收尾：拒绝新帧，并让写入器把队列里已有的帧（含终态 DONE）**按顺序写完后**再关闭连接。
     *
     * <h3>为什么不再 interrupt 写入器</h3>
     * 原实现 `close()` 直接 `writer.interrupt()` —— 写入器立刻退出循环，
     * 队列里尚未写出的帧被永久丢弃。表现是：服务端日志干干净净（没有任何异常），
     * 但客户端"流结束了却没有终态事件"，回答看起来被截断。
     * 实测在 1000 并发下每轮有 2~5 个请求命中这个竞态。
     *
     * <p>另外，原先的 `drainAndComplete()` 会在**调用方线程**上主动轮询同一个队列 ——
     * 与写入器形成"两个消费者"，既有交错写入风险，也会在 close 时丢末帧。
     * 现在队列只有一个所有者（写入器），调用方只负责"投递哨兵"。
     */
    @Override
    public void close() {
        synchronized (offerLock) {
            if (!closingAfterDrain.compareAndSet(false, true)) {
                return;
            }
            // 把还没等到缺口的乱序帧按 seq 强制刷出：此时不能再丢终态。
            // 如果确实缺了某一帧，这里只能按现有顺序写出；但 DONE/ERROR 不会因为
            // 等待一个永远不会来的 seq 而卡在 pending 里。
            while (!pendingBySeq.isEmpty()) {
                java.util.Map.Entry<Long, Frame> entry = pendingBySeq.pollFirstEntry();
                if (entry.getKey() <= lastWrittenSeq.get()) {
                    continue;
                }
                if (!enqueueLocked(entry.getValue())) {
                    break;
                }
                lastWrittenSeq.set(entry.getKey());
                nextSeq = entry.getKey() + 1;
            }
            closed.set(true);
            if (!queue.offer(Frame.terminator())) {
                // 队列已满：丢弃最旧一帧腾出位置，保证哨兵一定入队
                // （宁可少一帧中间内容，也不能丢掉终态与关闭信号）
                queue.poll();
                queue.offer(Frame.terminator());
            }
        }
    }

    /**
     * 立即中断（仅用于客户端已消失或内部错误的场景）。
     *
     * <p>与 {@link #close()} 的区别：这里不保证队列写完，语义上属于"放弃这条连接"。
     */
    public void abort() {
        synchronized (offerLock) {
            if (closed.compareAndSet(false, true)) {
                closingAfterDrain.set(true);
                pendingBySeq.clear();
                writer.interrupt();
            }
        }
    }

    private void completeQuietly() {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已经完成或已断开
        }
    }

    public boolean closed() {
        return closed.get();
    }

    public boolean overflowed() {
        return overflowed.get();
    }

    public long writtenFrames() {
        return written.get();
    }

    public long droppedFrames() {
        return droppedFrames.get();
    }

    /** 距上次成功写入的毫秒数（看门狗据此判断连接是否已经假死）。 */
    public long idleMillis() {
        return System.currentTimeMillis() - lastWriteAt;
    }

    public Long messageId() {
        return messageId;
    }

    public SseEmitter emitter() {
        return emitter;
    }
}
