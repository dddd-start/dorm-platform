package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.RoomType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入住申请，系统的核心业务单据。
 *
 * <p>生命周期见 {@code ApplyStateMachine}。几个关键字段的用途：
 * <ul>
 *   <li>{@code idempotencyKey}：客户端生成的轮次/表单幂等键，
 *       配合唯一索引保证「重复提交只产生一张单」；</li>
 *   <li>{@code expireTime}：审批通过后若超期未入住则自动失效，
 *       释放床位（由定时任务扫描）；</li>
 *   <li>{@code applySource}：区分网页自助与 AI 助手代提交，用于运营分析。</li>
 * </ul>
 */
@Data
@TableName("stay_apply")
public class StayApply implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String applyNo;
    private Long employeeId;
    private String employeeName;
    private String employeeNo;

    private Long regionId;
    private String destCountry;
    private String destCity;
    /** 期望公寓（可为空，由管理员现场分配）。 */
    private Long buildingId;
    private RoomType preferredRoomType;

    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private Integer nights;

    private String tripNo;
    private String reason;

    private ApplyStatus status;

    private Long roomId;
    private Long bedId;
    private LocalDateTime assignTime;

    private Long approverId;
    private String approverName;
    private LocalDateTime approveTime;
    private String approveRemark;
    private String rejectReason;

    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private LocalDateTime cancelTime;
    private LocalDateTime expireTime;

    private ApplySource applySource;
    private String idempotencyKey;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
