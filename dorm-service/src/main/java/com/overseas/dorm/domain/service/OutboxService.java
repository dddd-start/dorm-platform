package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.constant.BusinessLimits;
import com.overseas.dorm.common.enums.OutboxStatus;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.entity.OutboxEvent;
import com.overseas.dorm.domain.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 领域事件发件箱（Transactional Outbox）。
 *
 * <p>用法：在业务事务里调用 {@link #publish}，事件与业务数据同事务落库；
 * 事务提交后由 {@link #dispatch()} 定时投递。
 *
 * <p>为什么不在业务方法里直接"发消息"：
 * <pre>
 *   ❌ 事务内直接调用下游：下游慢 -> 事务被拉长 -> 连接池被占满 -> 全站雪崩
 *   ❌ 事务提交后直接调用：进程在两步之间崩溃 -> 事件永久丢失
 *   ✅ Outbox: 事件先落库（和业务同一个原子操作），再由独立投递器保证"至少一次"
 * </pre>
 * 「至少一次」意味着消费端必须幂等 —— 这是分布式事务里无法回避的代价，
 * 也是为什么本系统的业务操作都带幂等键。
 */
@Slf4j
@Service
public class OutboxService {

    /** 单次投递扫描的批大小。 */
    private static final int DISPATCH_BATCH_SIZE = 100;

    /** 自动重试上限，超过即转死信等待人工处理。 */
    private static final int DISPATCH_MAX_RETRY = 5;

    /** 指数退避的最大位移（2^6 = 64 秒），避免退避时间无限增长。 */
    private static final int RETRY_BACKOFF_MAX_SHIFT = 6;

    /** 人工重投单次允许的最大条数，防止误操作一次性灌爆下游。 */
    private static final int MAX_MANUAL_RETRY_LIMIT = 500;

    /** SENDING 超过多少分钟视为卡住（进程崩溃）并回收成 FAILED。 */
    private static final int STALE_SENDING_MINUTES = 5;

    private final OutboxEventMapper outboxEventMapper;
    private final TaskExecutor outboxExecutor;

    /** 事件类型 -> 处理器。同一个事件类型可以注册多个处理器。 */
    private final Map<String, List<Consumer<OutboxEvent>>> handlers = new ConcurrentHashMap<>();

    public OutboxService(OutboxEventMapper outboxEventMapper,
                         @Qualifier("outboxExecutor") TaskExecutor outboxExecutor) {
        this.outboxEventMapper = outboxEventMapper;
        this.outboxExecutor = outboxExecutor;
    }

    /** 注册事件处理器（各业务模块在启动时注册，避免 OutboxService 反向依赖业务）。 */
    public void register(String eventType, Consumer<OutboxEvent> handler) {
        handlers.computeIfAbsent(eventType, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * 发布事件（与调用方处于同一事务）。
     *
     * @param eventType     事件类型，如 APPLY_APPROVED
     * @param aggregateType 聚合类型，如 APPLY
     * @param aggregateId   聚合 ID
     * @param payload       事件载荷（会被序列化为 JSON）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, String aggregateType, Long aggregateId, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(JsonUtil.toJson(payload));
        event.setStatus(OutboxStatus.NEW);
        event.setRetryCount(0);
        event.setNextRetryTime(LocalDateTime.now());
        outboxEventMapper.insert(event);
        log.debug("Outbox 事件已登记: {} #{}/{}", eventType, aggregateType, aggregateId);
    }

    /** 投递器：每 2 秒扫一批待投递事件，逐条领取后才投递（多实例下同一事件只有一个实例能领到）。 */
    @Scheduled(fixedDelayString = "${dorm.outbox.interval-ms:2000}")
    public void dispatch() {
        List<OutboxEvent> pending = outboxEventMapper.selectPending(
                DISPATCH_BATCH_SIZE, DISPATCH_MAX_RETRY);
        if (pending.isEmpty()) {
            return;
        }
        for (OutboxEvent event : pending) {
            // 先领取再投递：扫描是"无锁读"，只有条件更新成功的实例才拥有这次投递权
            if (outboxEventMapper.claim(event.getId()) != 1) {
                continue;
            }
            outboxExecutor.execute(() -> deliver(event));
        }
    }

    /** 回收"投递中"卡住的事件（进程崩溃导致），默认超过 5 分钟视为卡住。 */
    @Scheduled(fixedDelayString = "${dorm.outbox.recover-interval-ms:60000}")
    public void recoverStale() {
        int recovered = outboxEventMapper.recoverStale(STALE_SENDING_MINUTES);
        if (recovered > 0) {
            log.warn("Outbox 回收 {} 条卡在 SENDING 的事件（进程异常退出或投递超时）", recovered);
        }
    }

    private void deliver(OutboxEvent event) {
        try {
            List<Consumer<OutboxEvent>> list = handlers.getOrDefault(event.getEventType(), List.of());
            if (list.isEmpty()) {
                // 关键：没有处理器**绝不能**标记为"已投递"。
                //
                // 这曾经是一个真实缺陷：deliver() 遍历空的处理器列表，
                // 循环体一次都不执行，于是事件被直接标记 SENT ——
                // 库里 90 条事件全部显示"已投递"，而实际上什么副作用都没发生。
                // "报告成功但什么都没做"是最危险的一类问题：监控全绿、日志干净、
                // 业务却少了通知。这里改为标记死信，让它在运维看板上暴露出来。
                throw new IllegalStateException(
                        "事件类型 " + event.getEventType() + " 没有注册任何处理器，无法投递");
            }
            for (Consumer<OutboxEvent> handler : list) {
                handler.accept(event);
            }
            event.setStatus(OutboxStatus.SENT);
            event.setLastError(null);
        } catch (Exception e) {
            int retry = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
            event.setRetryCount(retry);
            event.setLastError(truncate(e.getMessage()));
            // 超过重试上限转死信：继续重试只会刷日志，需要人工介入
            event.setStatus(retry >= DISPATCH_MAX_RETRY ? OutboxStatus.DEAD : OutboxStatus.FAILED);
            // 指数退避：1、2、4、8……秒（上限 64 秒）。固定间隔重试会在下游故障时形成持续压力。
            event.setNextRetryTime(LocalDateTime.now()
                    .plusSeconds(1L << Math.min(retry, RETRY_BACKOFF_MAX_SHIFT)));
            log.warn("Outbox 事件投递失败 type={} aggregate={}#{} retry={}",
                    event.getEventType(), event.getAggregateType(), event.getAggregateId(), retry, e);
        }
        event.setUpdateTime(LocalDateTime.now());
        try {
            outboxEventMapper.updateById(event);
        } catch (Exception e) {
            log.error("Outbox 状态回写失败 id={}", event.getId(), e);
        }
    }

    /** 人工重投（运维接口）。 */
    public int retryDead(int limit) {
        List<OutboxEvent> dead = outboxEventMapper.selectList(new LambdaQueryWrapper<OutboxEvent>()
                .in(OutboxEvent::getStatus, OutboxStatus.DEAD, OutboxStatus.FAILED)
                .last("LIMIT " + Math.clamp(limit, 1, MAX_MANUAL_RETRY_LIMIT)));
        for (OutboxEvent e : dead) {
            e.setStatus(OutboxStatus.NEW);
            e.setRetryCount(0);
            e.setNextRetryTime(LocalDateTime.now());
            outboxEventMapper.updateById(e);
        }
        return dead.size();
    }

    public Map<String, Long> stats() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        // 遍历枚举而不是手写字符串列表：新增状态时统计自动覆盖，
        // 不会出现"加了状态但看板上看不到"的情况
        for (OutboxStatus status : OutboxStatus.values()) {
            Long count = outboxEventMapper.selectCount(
                    new LambdaQueryWrapper<OutboxEvent>().eq(OutboxEvent::getStatus, status));
            result.put(status.name(), count);
        }
        return result;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > BusinessLimits.ERROR_MESSAGE_MAX_LENGTH
                ? s.substring(0, BusinessLimits.ERROR_MESSAGE_MAX_LENGTH) : s;
    }
}
