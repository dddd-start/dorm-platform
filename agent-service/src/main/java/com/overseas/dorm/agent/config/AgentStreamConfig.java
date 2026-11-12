package com.overseas.dorm.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 流式相关基础设施配置。
 *
 * <p>这里只保留 Pub/Sub 监听容器这一个 bean。
 * 注意<b>不要</b>在本类里注入并配置 {@code AgentStreamBuffer} ——
 * 那会形成「Config 依赖 Buffer、Buffer 依赖本类定义的监听容器」的循环依赖，
 * 循环依赖只在启动时暴露，且 Spring 的报错信息很难直接指向根因。
 * Buffer 的配置改由它自己从 {@link AgentProperties} 读取（见其 @PostConstruct）。
 */
@Slf4j
@Configuration
public class AgentStreamConfig {

    /**
     * Pub/Sub 监听容器。
     *
     * <p>两个关键参数的取舍：
     * <ul>
     *   <li>{@code setRecoveryInterval}：Redis 短暂抖动后容器会自动重连。
     *       如果设为 0（不恢复），一次网络闪断就会让所有实时推送永久失效，
     *       而 SSE 连接看起来还"活着" —— 这是最难排查的一类故障；</li>
     *   <li>不显式设置订阅线程池时 Spring 会给出一个默认实现，
     *       本项目的事件量级（每 token 一条消息）用默认池足够，
     *       若将来并发流上万，应显式给定线程池并监控其队列深度。</li>
     * </ul>
     */
    /**
     * Pub/Sub 消息分发执行器。
     *
     * <p>容量按"监听器只做非阻塞入队"这个前提设定：单次回调是纯内存操作（微秒级），
     * 因此 32 个线程足以支撑千级连接的实时事件分发。
     * 队列给得较大是为了吸收突发（一次回答结束时会连续推若干帧）。
     */
    private java.util.concurrent.Executor listenerExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(20000);
        executor.setThreadNamePrefix("redis-pubsub-");
        // 队列满时由调用线程执行：宁可短暂拖慢订阅连接，也不能丢事件
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 订阅维护执行器：只负责 SUBSCRIBE 与重连，单线程足够。 */
    private java.util.concurrent.Executor subscriptionExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("redis-subscribe-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setRecoveryInterval(2000L);
        // 【关键】默认的监听任务执行器容量极小（Spring Data Redis 会退化成一个
        // 单线程调度器），而 Pub/Sub 的分发是在这个执行器上调用监听器的：
        // 只要有一个监听器回调阻塞，全实例所有连接的续传都会停摆。
        // 因此显式换成有界线程池，并把监听器实现成"只入队、不写 socket"
        // （见 SseChannel）—— 两道保险缺一不可。
        container.setTaskExecutor(listenerExecutor());
        // 订阅维护线程（负责 SUBSCRIBE 与断线重连）单独一个线程即可，
        // 不要和消息分发抢资源
        container.setSubscriptionExecutor(subscriptionExecutor());
        log.info("[SSE] Redis Pub/Sub 监听容器已就绪（断线自动恢复间隔 2s）");
        return container;
    }
}
