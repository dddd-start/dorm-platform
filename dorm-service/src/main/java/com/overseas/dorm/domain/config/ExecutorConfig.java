package com.overseas.dorm.domain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置。
 *
 * <p>每个池的拒绝策略都是刻意选的，不是默认值：
 * <ul>
 *   <li><b>auditExecutor</b>：队列满时 CallerRuns —— 审计宁可拖慢调用方也不能丢；</li>
 *   <li><b>outboxExecutor</b>：CallerRuns —— 事件投递不允许丢。</li>
 * </ul>
 * 这几个池是「高并发下不雪崩」的关键：没有隔离，一个慢依赖就能拖垮全站。
 */
@Slf4j
@Configuration
public class ExecutorConfig {

    /**
     * 丢弃并计数的拒绝策略。
     *
     * <p>为什么不用 DiscardPolicy：那个策略是**静默**丢弃，出问题时没有任何痕迹 ——
     * 而"观测数据被丢了"这件事本身必须可见，否则运维会以为审计是完整的。
     * 这里把丢弃次数暴露到日志（按 1000 条聚合一次，避免刷屏）。
     */
    static final class CountingDiscardHandler implements java.util.concurrent.RejectedExecutionHandler {
        private final String what;
        private final java.util.concurrent.atomic.AtomicLong dropped =
                new java.util.concurrent.atomic.AtomicLong();

        CountingDiscardHandler(String what) {
            this.what = what;
        }

        @Override
        public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor e) {
            long n = dropped.incrementAndGet();
            if (n % 1000 == 1) {
                org.slf4j.LoggerFactory.getLogger(ExecutorConfig.class)
                        .warn("[{}] 队列已满，已累计丢弃 {} 条（过载保护：宁可丢观测数据，也不让业务请求等库）",
                                what, n);
            }
        }

        long droppedCount() {
            return dropped.get();
        }
    }

    @Bean("auditExecutor")
    public ThreadPoolTaskExecutor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("dorm-audit-");
        // 【为什么从 CallerRunsPolicy 改为 DiscardPolicy + 计数】
        // CallerRunsPolicy 在队列满时会把审计写入**放到调用者线程**上执行 ——
        // 而调用者通常是模型回调线程（关键路径）。1000 并发下每轮产生若干条
        // 工具调用审计，队列一旦打满，审计写库就直接与业务请求抢数据库连接，
        // 实测出现 total=128/active=128/waiting=192 的连接耗尽。
        // 审计是"尽力而为"的观测数据：过载时丢弃并计数，好过把业务请求拖死。
        executor.setRejectedExecutionHandler(new CountingDiscardHandler("审计"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean("outboxExecutor")
    public ThreadPoolTaskExecutor outboxExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("dorm-outbox-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

}
