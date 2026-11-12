package com.overseas.dorm.agent.model;

import com.overseas.dorm.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游模型并发闸门 —— 保护模型服务商，而不是保护我们自己。
 *
 * <h2>为什么必须有这一层（这是一个真实的设计缺口）</h2>
 * 之前只有"容器能撑多少条并发流"这一个上限（{@code max-streams-global}）。
 * 但每一条并发的流都会**立刻向模型服务商发起一次调用**，于是
 * "容器并发上限" 直接等于 "对上游的并发调用数"。当我们把上限提到 1000 时，
 * 实际上是在向 Command Code 发起 1000 个并发请求 ——
 * 结果是全链路 429 / rate_limit，日志实测达到 490 次 429、482 次熔断，
 * 熔断又会触发降级链在多个模型之间来回切换，进一步放大调用量。
 *
 * <p>这类"把自己的容量当成上游的容量"是接入第三方模型服务时最常见的事故：
 * 上游按账号限流，我们压力越大它拒得越多，而重试与降级会让压力更大 ——
 * 形成正反馈，最终把账号打到被临时封禁。
 *
 * <h2>为什么是"快速失败"而不是排队</h2>
 * 排队看起来更友好，但会把延迟拉到不可接受（用户等 30 秒才被告知繁忙），
 * 而且排队期间连接、内存、虚拟线程都被占着。这里给一个短暂的等待窗口
 * （默认 8 秒）作为削峰，超时就用**业务可读的提示**拒绝这一轮，
 * 让用户在几秒内得到明确的"当前咨询人数较多，请稍后重试"。
 *
 * <h2>与降级链的关系</h2>
 * 闸门限制的是"同时在途的上游调用数"（含降级链里的每次尝试），
 * 因此它同样能抑制"降级风暴"：如果上游整体限流，
 * 我们的在途调用数被压在一个较小值，重试与切换的放大效应也随之被限制住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelConcurrencyGuard {

    private final AgentProperties properties;

    private volatile Semaphore permits;

    /** 等待超时（毫秒）：作为削峰窗口，超时即快速失败。 */
    private static final long ACQUIRE_TIMEOUT_MS = 8000L;

    /** 因闸门饱和而被拒绝的轮次数（暴露给运维：持续增长说明该扩容或降并发上限）。 */
    private final AtomicLong rejected = new AtomicLong();

    private Semaphore permits() {
        if (permits == null) {
            synchronized (this) {
                if (permits == null) {
                    int max = Math.max(properties.getModel().getMaxConcurrentCalls(), 1);
                    permits = new Semaphore(max, true);
                    log.info("[模型闸门] 上游模型最大并发调用数 = {}（保护模型服务商，"
                            + "与容器并发上限解耦）", max);
                }
            }
        }
        return permits;
    }

    /**
     * 申请一次上游调用许可。
     *
     * @return true 表示拿到许可（调用方必须在 finally 里 {@link #release()}）
     */
    public boolean tryAcquire() {
        Semaphore semaphore = permits();
        try {
            if (semaphore.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        long n = rejected.incrementAndGet();
        if (n % 50 == 1) {
            log.warn("[模型闸门] 上游并发已达上限（{}），累计拒绝 {} 轮。"
                            + "若持续出现，应下调容器并发上限或申请更高的上游配额",
                    properties.getModel().getMaxConcurrentCalls(), n);
        }
        return false;
    }

    public void release() {
        permits().release();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** 当前在途的上游调用数（供运维面板展示）。 */
    public int inFlight() {
        Semaphore semaphore = permits();
        return Math.max(properties.getModel().getMaxConcurrentCalls() - semaphore.availablePermits(), 0);
    }
}
