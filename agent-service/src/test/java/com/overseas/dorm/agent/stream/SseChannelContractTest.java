package com.overseas.dorm.agent.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * SSE 出站通道的契约：<b>序号去重、心跳不占号、优雅收尾不丢末帧、溢出不静默截断</b>。
 *
 * 它们全部是"并发+慢客户端"才暴露的性质，正常开发时一条也看不出来：
 * <ul>
 *   <li><b>去重与重排</b>：同一条连接既有生成本地直投、又有 Redis Pub/Sub 转发。
 *       两条路径并发时，较大的 seq 可能先到；SseChannel 用 pendingBySeq 缓存乱序帧，
 *       缺口补齐后按序写出，并统计重复帧（suppressedDuplicates）。</li>
 *   <li><b>优雅收尾</b>：早先的 {@code close()} 直接 interrupt 写入器，
 *       队列里没写出的帧被永久丢弃，服务端日志干干净净，客户端却"流结束了没有终态事件"。
 *       1000 并发下每轮有 2~5 个请求命中这个竞态。</li>
 *   <li><b>溢出</b>：队列满必须保护生产者，但<b>不能静默</b>截断，*       客户端得知道"你读太慢，请带 lastEventId 重连"。</li>
 * </ul>
 *
 * 慢客户端在生产里最难复现（要弱网、要切后台、要假死）。这里用一个
 * <b>会阻塞的 SseEmitter 替身</b>把写入器卡住，于是"队列被填满"变成必然事件，* 不依赖 sleep、不依赖压测、不靠"跑 5000 次大概能撞上"。
 */
class SseChannelContractTest {

    private static final Long MESSAGE_ID = 8801L;

    /**
     * 会阻塞的 emitter 替身：第一次 {@code send} 就卡住，直到测试放行。
     *
     * <p>它模拟的是"客户端不读"：TCP 发送窗口填满 → 写线程阻塞。
     * 这正是队头阻塞的起点。
     */
    private static final class BlockingSseEmitter extends SseEmitter {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            entered.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitWriterBlocked() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        void release() {
            gate.countDown();
        }
    }

    /** 轮询等待某个条件成立；超时即失败（避免用固定 sleep 造成偶发失败）。 */
    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("等待超时：" + what);
    }

    // ==================================================================
    // 一、序号级去重
    // ==================================================================

    @Test
    @DisplayName("同一序号投递两次，只写出一次，且重复被计数（不静默丢弃）")
    void duplicateSequenceIsWrittenOnce() {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "dedup");
        try {
            assertThat(channel.offer("1", "DELTA", "你")).isTrue();
            assertThat(channel.offer("1", "DELTA", "你"))
                    .as("重复帧对调用方仍是'已受理'，不该当成错误")
                    .isTrue();

            await(() -> channel.writtenFrames() == 1, "第一帧写出");
            assertThat(channel.suppressedDuplicates())
                    .as("重复必须被计数：计数持续增长就意味着有投递路径在重复投递，"
                            + "这是排查'拼接文本翻倍'的唯一线索")
                    .isEqualTo(1);
        } finally {
            channel.abort();
        }
    }

    @Test
    @DisplayName("迟到的旧序号被丢弃而不是补写（去重是单调的）")
    void olderSequenceArrivingLateIsDropped() {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "monotonic");
        try {
            channel.offer("2", "DELTA", "b");
            assertThat(channel.offer("1", "DELTA", "a")).isTrue();

            await(() -> channel.writtenFrames() == 1, "第一帧写出");
            assertThat(channel.suppressedDuplicates())
                    .as("这是刻意保留的快速路径：如果旧序号到达时 nextSeq 已经推进，"
                            + "说明它要么已经写过、要么已经由 close 强制刷出。真正处理"
                            + "乱序的是 pendingBySeq；这条断言守住'不补写旧序号'的语义")
                    .isEqualTo(1);
        } finally {
            channel.abort();
        }
    }

    @Test
    @DisplayName("非数字 id 的帧不参与去重（心跳、注释帧靠它通过）")
    void nonNumericIdIsNotDeduplicated() {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "nonnumeric");
        try {
            channel.offer("notice", "REFERENCE", "x");
            channel.offer("notice", "REFERENCE", "x");

            await(() -> channel.writtenFrames() == 2, "两帧都写出");
            assertThat(channel.suppressedDuplicates()).isZero();
        } finally {
            channel.abort();
        }
    }

    @Test
    @DisplayName("心跳帧不消耗序号：它不该影响事件序的连续性")
    void heartbeatDoesNotConsumeSequence() throws Exception {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "heartbeat");
        try {
            assertThat(channel.offerHeartbeat()).isTrue();
            channel.offer("1", "DELTA", "a");

            await(() -> channel.writtenFrames() == 2, "心跳与首帧都写出");
            assertThat(channel.offer("2", "DONE", "{}"))
                    .as("心跳不占号，所以序号 2 仍然应当被接受")
                    .isTrue();
            await(() -> channel.writtenFrames() == 3, "末帧写出");
            assertThat(channel.suppressedDuplicates()).isZero();
        } finally {
            channel.abort();
        }
    }

    @Test
    @DisplayName("乱序到达的 seq 先缓存，缺口补齐后按序写出（不丢小序号）")
    void outOfOrderFramesAreBufferedAndFlushedInOrder() {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "reorder", 1L);
        try {
            // 模拟 Pub/Sub 的 seq=2 先于直投的 seq=1 到达
            assertThat(channel.offer("2", "DELTA", "b")).isTrue();
            assertThat(channel.offer("1", "DELTA", "a")).isTrue();

            await(() -> channel.writtenFrames() == 2, "两帧都写出");
            assertThat(channel.suppressedDuplicates())
                    .as("小序号不能被当成重复丢掉；它只是晚到")
                    .isZero();
        } finally {
            channel.abort();
        }
    }

    @Test
    @DisplayName("并发投递同一序号：判重+入队是原子的，只有一帧写出")
    void concurrentSameSequenceIsWrittenOnce() throws Exception {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "concurrent");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    channel.offer("7", "DELTA", "x");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        try {
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            await(() -> channel.writtenFrames() == 1, "同一序号只写出一帧");
            assertThat(channel.suppressedDuplicates())
                    .as("8 个并发投递只有 1 个通过判重，其余 7 个必须被计数")
                    .isEqualTo(threads - 1);
        } finally {
            channel.abort();
        }
    }

    // ==================================================================
    // 二、优雅收尾：队列里已有的帧必须写完
    // ==================================================================

    @Test
    @DisplayName("收尾不丢末帧：close() 之后，队里已排的终态事件仍会写出")
    void gracefulCloseStillWritesQueuedTerminalFrame() {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "drain");
        channel.offer("1", "DELTA", "a");
        channel.offer("2", "DONE", "{}");
        // 立刻收尾：如果 close 是 interrupt 写入器，这两帧就永远写不出去了
        channel.close();

        await(() -> channel.closed() && channel.writtenFrames() == 2,
                "收尾后队列里的两帧都写出（含终态 DONE）");
        assertThat(channel.writtenFrames())
                .as("客户端必须收到终态事件，否则它会以为回答被截断了")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("abort() 是立即中断：它与优雅收尾的语义不同")
    void abortStopsImmediately() {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "abort");
        channel.abort();

        assertThat(channel.closed()).isTrue();
        assertThat(channel.offer("1", "DELTA", "a"))
                .as("已放弃的连接不应再接受新帧")
                .isFalse();
    }

    // ==================================================================
    // 三、慢客户端：保护生产者，但不能静默截断
    // ==================================================================

    @Test
    @DisplayName("队列溢出：保护生产者不被拖住，同时给出可续传的提示而不是静默断开")
    void overflowProtectsProducerAndSignalsResumable() throws Exception {
        BlockingSseEmitter blocked = new BlockingSseEmitter();
        int capacity = 8;
        SseChannel channel = new SseChannel(MESSAGE_ID, blocked, capacity, "overflow");
        try {
            // 第一帧会让写入器卡在 send 上，从此队列只进不出
            channel.offer("1", "DELTA", "first");
            assertThat(blocked.awaitWriterBlocked())
                    .as("写入器应当已经卡在 send 上（慢客户端的起点）")
                    .isTrue();

            boolean rejected = false;
            for (int seq = 2; seq <= capacity + 5; seq++) {
                if (!channel.offer(String.valueOf(seq), "DELTA", "chunk-" + seq)) {
                    rejected = true;
                }
            }

            assertThat(rejected)
                    .as("队列满时必须让调用方知道这条连接已经不可用 —— "
                            + "而不是让生产者阻塞在那里等客户端读")
                    .isTrue();
            assertThat(channel.overflowed())
                    .as("溢出必须被标记：它是'这个客户端跟不上'的唯一可观测信号")
                    .isTrue();
            assertThat(channel.droppedFrames())
                    .as("丢弃帧数必须被计数：静默截断是无法排查的")
                    .isPositive();
            assertThat(channel.closed())
                    .as("溢出后应转入优雅收尾，让 INTERRUPTED 提示能写出去")
                    .isTrue();
        } finally {
            blocked.release();
            channel.abort();
        }
    }

    @Test
    @DisplayName("正向前提：客户端正常读取时，通道不会误判溢出")
    void healthyClientNeverOverflows() {
        SseChannel channel = new SseChannel(MESSAGE_ID, new SseEmitter(), 64, "healthy");
        try {
            for (int seq = 1; seq <= 30; seq++) {
                assertThat(channel.offer(String.valueOf(seq), "DELTA", "chunk-" + seq)).isTrue();
            }
            await(() -> channel.writtenFrames() == 30, "30 帧全部写出");

            assertThat(channel.overflowed())
                    .as("没有这一半，'溢出时标记'就可能只是因为溢出标志被无条件置位了")
                    .isFalse();
            assertThat(channel.droppedFrames()).isZero();
            assertThat(channel.suppressedDuplicates()).isZero();
        } finally {
            channel.abort();
        }
    }
}
