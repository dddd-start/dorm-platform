package com.overseas.dorm.domain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置。
 *
 * <p>每个池的拒绝策略都按用途选定，未使用默认值：
 * <ul>
 *   <li><b>auditExecutor</b>：队列满时 CallerRuns，审计宁可拖慢调用方也不能丢；</li>
 *   <li><b>agentStreamExecutor</b>：AbortPolicy，流式生成占资源，
 *       超出容量必须快速失败并告知用户「当前繁忙」，
 *       如果让调用线程（Tomcat 工作线程）去跑生成，会把整个 Web 层拖死；</li>
 *   <li><b>outboxExecutor</b>：CallerRuns，事件投递不允许丢。</li>
 * </ul>
 * 这几个池是「高并发下不雪崩」的关键：没有隔离，一个慢依赖就能拖垮全站。
 */
@Slf4j
@Configuration
public class ExecutorConfig {

    /**
     * 丢弃并计数的拒绝策略。
     *
     * <p>为什么不用 DiscardPolicy：那个策略是静默丢弃，出问题时没有任何痕迹，* 而"观测数据被丢了"这件事本身必须可见，否则运维会以为审计是完整的。
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
        // CallerRunsPolicy 在队列满时会把审计写入放到调用者线程上执行 ——
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

    /**
     * Agent 流式生成执行器：<b>每个任务一个虚拟线程</b>。
     *
     * 改造前是 {@code core=16 / max=64 / queue=16} 的平台线程池，它有两个致命问题：
     * <ol>
     *   <li><b>它成了并发上限</b>：80 个任务之后直接拒绝。要支撑 1000 并发对话，
     *       平台线程池就得开到 1000 个线程，每个线程 1MB 栈，仅栈内存就要 1GB，
     *       这才是"人数一多就把容器打挂"的直接原因；</li>
     *   <li><b>线程数被误当成保护手段</b>：真正该限制的是"同时对上游模型的调用数"
     *       （成本与配额问题），而这个限制已经由 Redis 里的并发流计数
     *       （每用户 3 / 全局 1000，多实例共享）承担，不该再由本地线程池重复限制。</li>
     * </ol>
     *
     * <p>生成的绝大部分时间在等上游模型返回，属于纯 I/O 等待。
     * 只需要很少的载体线程（默认 = CPU 核数），内存开销从"每线程 1MB"
     * 降到"每个虚拟线程几 KB"。
     *
     * <p>真正的并发上限由 {@code dorm.agent.stream.max-streams-global} 在业务层把守，
     * 而且它是<b>多实例共享</b>的，比本地线程池更准确。
     */
    @Bean(name = "agentStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService agentStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }


    @Bean("agentTaskExecutor")
    public ThreadPoolTaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("dorm-agent-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
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

    /**
     * SSE 专用的定时调度器：心跳、断线看门狗、生成超时看护。
     *
     * <p>为什么不复用 Spring 默认的 {@code taskScheduler}：
     * 默认调度器只有一个线程，且可能与其它定时任务（Outbox 投递、证书续期等）共用。
     * 一旦某个任务被阻塞，所有 SSE 心跳都会停摆，* 而心跳停摆的直接后果是「所有长连接被网关批量掐断」。
     * 给流式链路一个独立的、有多个线程的调度器，是廉价但关键的隔离。
     */
    @Bean("agentStreamScheduler")
    public ThreadPoolTaskScheduler agentStreamScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("dorm-sse-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
