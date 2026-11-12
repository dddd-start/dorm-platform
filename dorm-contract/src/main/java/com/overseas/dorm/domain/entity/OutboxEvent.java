package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 领域事件发件箱（Transactional Outbox）。
 *
 * <p>解决的问题：审批通过后要「发通知、刷统计、写审计」，
 * 如果这些动作和主事务写在一起，任何一个失败都会拖垮审批这个核心动作；
 * 如果异步发，又可能因为进程崩溃而丢失。
 *
 * <p>Outbox 的做法是：<b>把要发的消息和业务数据放进同一个本地事务</b>写库，
 * 再由独立的投递器（{@code OutboxPublisher}）扫描并投递，投递成功后标记 SENT。
 * 这样「业务提交」与「事件不丢」解耦，配合重试即达到最终一致。
 */
@Data
@TableName("outbox_event")
public class OutboxEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String eventType;
    private String aggregateType;
    private Long aggregateId;
    private String payload;
    /** NEW / SENT / FAILED / DEAD。 */
    /** 投递状态。用枚举而非字符串：状态名拼错时编译期就能发现，不会被静默写入库。 */
    private com.overseas.dorm.common.enums.OutboxStatus status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
