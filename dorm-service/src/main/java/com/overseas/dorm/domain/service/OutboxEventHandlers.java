package com.overseas.dorm.domain.service;

import com.overseas.dorm.common.enums.AuditEnums;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 领域事件的处理器注册。
 *
 * {@link OutboxService} 只负责"可靠地把事件投递出去"，具体做什么由处理器决定。
 * 在此之前<b>没有任何地方调用 {@code register()}</b>，于是处理器列表恒为空、
 * 事件被逐条标记为"已投递"，库里 90 条事件全部 SENT，而实际什么副作用都没发生。
 * 这是最危险的一类问题：监控全绿、日志干净、业务却少了通知。
 *
 * <p>现在（1）投递器在"无处理器"时改为标记死信让问题暴露，
 * （2）本类为每个事件类型注册真实处理器，端到端打通。
 *
 * 本项目<b>没有对接短信/邮件/IM 通道</b>（无外部凭据，也不该在演示环境里真发消息）。
 * <ol>
 *   <li>写一条结构化投递日志（带 traceId，可被日志采集系统消费）；</li>
 *   <li>写审计记录，使"通知已生成"这件事可追溯。</li>
 * </ol>
 * 将来接入真实通道时，只需在对应处理器里替换实现，事件的可靠投递、
 * 重试与死信机制无需改动。这一点在文档里如实说明，不宣称已发通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventHandlers {

    /** 事件类型常量与生产端保持一致（集中在此，避免两处字符串漂移）。 */
    private static final String EVENT_APPLY_CREATED = "APPLY_CREATED";
    private static final String EVENT_APPLY_APPROVED = "APPLY_APPROVED";
    private static final String EVENT_APPLY_REJECTED = "APPLY_REJECTED";
    private static final String EVENT_APPLY_CANCELLED = "APPLY_CANCELLED";
    private static final String EVENT_ORDER_CHECKED_IN = "ORDER_CHECKED_IN";
    private static final String EVENT_ORDER_CHECKED_OUT = "ORDER_CHECKED_OUT";
    /**
     * 申请自动失效（到入住日次日 23:59 仍未办理入住，由 {@code ApplyService.expireOverdueApplies()}
     * 定时扫描产生）。
     *
     * <p>【F1 修复】此前生产端会发这个事件，但本类没有为它注册处理器，* {@link OutboxService} 在"无处理器"时会抛错，并把事件重试 5 次后标记为 {@code DEAD}。
     * 也就是说：只要有申请走到自动失效，通知<b>必然进死信</b>。
     * 当时它只是"还没被触发"（库里尚无 EXPIRED 的申请），属于定时炸弹，而不是无害遗漏。
     *
     * <p>这类漏注册不会有编译错误、不会有启动报错，只有"出事时才发现没人处理"。
     * 因此下面把注册条数改成动态统计：每加一类事件都会反映在启动日志里，
     * 与生产端常量表对照即可发现缺口。
     */
    private static final String EVENT_APPLY_EXPIRED = "APPLY_EXPIRED";

    private final OutboxService outboxService;
    private final AuditService auditService;

    @PostConstruct
    public void registerAll() {
        int count = 0;
        outboxService.register(EVENT_APPLY_CREATED, event ->
                notify(AuditEnums.Module.APPLY, "申请已创建", event));
        count++;
        outboxService.register(EVENT_APPLY_APPROVED, event ->
                notify(AuditEnums.Module.APPLY, "申请已通过，请通知员工入住安排", event));
        count++;
        outboxService.register(EVENT_APPLY_REJECTED, event ->
                notify(AuditEnums.Module.APPLY, "申请已驳回，请通知员工原因", event));
        count++;
        outboxService.register(EVENT_APPLY_CANCELLED, event ->
                notify(AuditEnums.Module.APPLY, "申请已取消，床位已释放", event));
        count++;
        // 【F1 修复】自动失效同样要通知：床位已被释放，员工必须知道申请不再有效，
        // 否则他会按原计划去入住，到门口才发现没有床位。
        outboxService.register(EVENT_APPLY_EXPIRED, event ->
                notify(AuditEnums.Module.APPLY,
                        "申请已自动失效（超过入住日次日仍未办理入住），床位已释放，请通知员工重新申请",
                        event));
        count++;
        outboxService.register(EVENT_ORDER_CHECKED_IN, event ->
                notify(AuditEnums.Module.ORDER, "已办理入住", event));
        count++;
        outboxService.register(EVENT_ORDER_CHECKED_OUT, event ->
                notify(AuditEnums.Module.ORDER, "已办理退房，费用按实际晚数结算", event));
        count++;
        // 用真实条数而不是字面量 6：漏注册一类事件时，这行日志与生产端常量表的
        // 差额就是唯一线索（F1 正是这类漏注册）。
        log.info("[Outbox] 已注册 {} 个领域事件处理器", count);
    }

    /**
     * 生成一条"通知意图"记录。
     *
     * <p>刻意不叫 {@code sendNotification}：它没有真的发出去。
     * 命名要如实反映行为，否则下一个维护者会以为通知已经送达用户。
     *
     * <p>这里写审计是安全的：投递器运行在定时线程上，没有登录上下文，
     * 而 {@code AuditService.record} 对"用户为空"是容忍的（用户字段留空），* 审计主体记为"系统"，这正是事实。
     */
    private void notify(AuditEnums.Module module, String intent,
                        com.overseas.dorm.domain.entity.OutboxEvent event) {
        log.info("[Outbox] 通知意图已生成 type={} aggregate={}#{} 内容={}",
                event.getEventType(), event.getAggregateType(), event.getAggregateId(), intent);
        auditService.success(module, "OUTBOX_" + event.getEventType(),
                event.getAggregateType(), event.getAggregateId(), null,
                java.util.Map.of("eventType", event.getEventType(), "intent", intent,
                        "payload", event.getPayload() == null ? "" : event.getPayload()));
    }
}
