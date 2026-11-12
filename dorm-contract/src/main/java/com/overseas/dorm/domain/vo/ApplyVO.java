package com.overseas.dorm.domain.vo;

import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.domain.entity.StayApply;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 入住申请视图（含进度时间轴）。
 *
 * <p>时间轴由服务端生成而不是前端拼装，原因有两个：
 * <ol>
 *   <li>前端拼装容易出现「状态是已驳回但时间轴里没有驳回节点」这类不一致；</li>
 *   <li>AI 助手也需要把进度讲给用户听，服务端生成的时间轴可以被直接复用为
 *       工具返回的结构化数据。</li>
 * </ol>
 */
@Data
public class ApplyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String applyNo;
    private Long employeeId;
    private String employeeName;
    private String employeeNo;

    private Long regionId;
    private String regionName;
    private String destCountry;
    private String destCity;
    private Long buildingId;
    private String buildingName;
    private RoomType preferredRoomType;

    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private Integer nights;
    private String tripNo;
    private String reason;

    private ApplyStatus status;
    private String statusLabel;
    private String statusColor;

    private Long roomId;
    private String roomNo;
    private Long bedId;
    private String bedNo;
    private LocalDateTime assignTime;

    private Long approverId;
    private String approverName;
    private LocalDateTime approveTime;
    private String approveRemark;
    private String rejectReason;

    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private LocalDateTime expireTime;
    private ApplySource applySource;
    private String applySourceLabel;
    private String currency;
    private BigDecimal pricePerNight;

    private LocalDateTime createTime;

    /** 当前登录用户对该申请可执行的动作（服务端判定，前端直接渲染按钮）。 */
    private List<String> allowedActions = new ArrayList<>();

    /** 进度时间轴。 */
    private List<TimelineItem> timeline = new ArrayList<>();

    /** 关联订单号（若有）。 */
    private String orderNo;

    @Data
    public static class TimelineItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String node;
        private String label;
        private String color;
        private LocalDateTime time;
        private String operator;
        private String remark;

        public static TimelineItem of(String node, String label, String color,
                                      LocalDateTime time, String operator, String remark) {
            TimelineItem item = new TimelineItem();
            item.node = node;
            item.label = label;
            item.color = color;
            item.time = time;
            item.operator = operator;
            item.remark = remark;
            return item;
        }
    }

    /**
     * 由实体构建基础信息（时间轴与动作由 Service 补充）。
     */
    public static ApplyVO of(StayApply apply) {
        ApplyVO vo = new ApplyVO();
        vo.id = apply.getId();
        vo.applyNo = apply.getApplyNo();
        vo.employeeId = apply.getEmployeeId();
        vo.employeeName = apply.getEmployeeName();
        vo.employeeNo = apply.getEmployeeNo();
        vo.regionId = apply.getRegionId();
        vo.destCountry = apply.getDestCountry();
        vo.destCity = apply.getDestCity();
        vo.buildingId = apply.getBuildingId();
        vo.preferredRoomType = apply.getPreferredRoomType();
        vo.checkInDate = apply.getCheckInDate();
        vo.checkOutDate = apply.getCheckOutDate();
        vo.nights = apply.getNights();
        vo.tripNo = apply.getTripNo();
        vo.reason = apply.getReason();
        vo.status = apply.getStatus();
        vo.statusLabel = apply.getStatus() == null ? null : apply.getStatus().getLabel();
        vo.statusColor = apply.getStatus() == null ? null : apply.getStatus().getColor();
        vo.roomId = apply.getRoomId();
        vo.bedId = apply.getBedId();
        vo.assignTime = apply.getAssignTime();
        vo.approverId = apply.getApproverId();
        vo.approverName = apply.getApproverName();
        vo.approveTime = apply.getApproveTime();
        vo.approveRemark = apply.getApproveRemark();
        vo.rejectReason = apply.getRejectReason();
        vo.checkInTime = apply.getCheckInTime();
        vo.checkOutTime = apply.getCheckOutTime();
        vo.expireTime = apply.getExpireTime();
        vo.applySource = apply.getApplySource();
        vo.applySourceLabel = apply.getApplySource() == null ? null : apply.getApplySource().getLabel();
        vo.createTime = apply.getCreateTime();
        return vo;
    }
}
