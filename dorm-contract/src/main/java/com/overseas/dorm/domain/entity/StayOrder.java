package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.OrderStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入住订单：审批通过并完成分房后生成，承载「真实入住 - 退房」的账务口径。
 *
 * <p>为什么申请与订单要分开：申请是「意图」，可能被驳回、取消、过期；
 * 订单是「已确认的履约」，与床位资源、费用结算一一对应。
 * 用 {@code uk_order_apply} 唯一索引保证一张申请最多生成一张订单，
 * 这在重试、并发审批场景下是必要的护栏。
 */
@Data
@TableName("stay_order")
public class StayOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;
    private Long applyId;
    private String applyNo;
    private Long employeeId;
    private String employeeName;
    private Long regionId;
    private Long buildingId;
    private Long roomId;
    private Long bedId;

    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private Integer nights;
    private LocalDateTime realCheckInTime;
    private LocalDateTime realCheckOutTime;

    private OrderStatus status;
    private BigDecimal amount;
    private String currency;
    private ApplySource source;
    private String earlyCheckOutReason;
    private String remark;

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
