package com.overseas.dorm.domain.vo;

import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.domain.entity.StayOrder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入住订单视图。
 */
@Data
public class OrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String orderNo;
    private Long applyId;
    private String applyNo;
    private Long employeeId;
    private String employeeName;
    private Long regionId;
    private String regionName;
    private Long buildingId;
    private String buildingName;
    private Long roomId;
    private String roomNo;
    private Long bedId;
    private String bedNo;

    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private Integer nights;
    private LocalDateTime realCheckInTime;
    private LocalDateTime realCheckOutTime;

    private OrderStatus status;
    private String statusLabel;
    private String statusColor;
    private BigDecimal amount;
    private String currency;
    private ApplySource source;
    private String earlyCheckOutReason;
    private String remark;
    private LocalDateTime createTime;

    public static OrderVO of(StayOrder order) {
        OrderVO vo = new OrderVO();
        vo.id = order.getId();
        vo.orderNo = order.getOrderNo();
        vo.applyId = order.getApplyId();
        vo.applyNo = order.getApplyNo();
        vo.employeeId = order.getEmployeeId();
        vo.employeeName = order.getEmployeeName();
        vo.regionId = order.getRegionId();
        vo.buildingId = order.getBuildingId();
        vo.roomId = order.getRoomId();
        vo.bedId = order.getBedId();
        vo.checkInDate = order.getCheckInDate();
        vo.checkOutDate = order.getCheckOutDate();
        vo.nights = order.getNights();
        vo.realCheckInTime = order.getRealCheckInTime();
        vo.realCheckOutTime = order.getRealCheckOutTime();
        vo.status = order.getStatus();
        vo.statusLabel = order.getStatus() == null ? null : order.getStatus().getLabel();
        vo.statusColor = order.getStatus() == null ? null : order.getStatus().getColor();
        vo.amount = order.getAmount();
        vo.currency = order.getCurrency();
        vo.source = order.getSource();
        vo.earlyCheckOutReason = order.getEarlyCheckOutReason();
        vo.remark = order.getRemark();
        vo.createTime = order.getCreateTime();
        return vo;
    }
}
