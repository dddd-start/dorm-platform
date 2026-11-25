package com.overseas.dorm.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 房源可用性行，房间维度的「可订视图」。
 *
 * <p>四个床位计数的区别，是房源检索最容易含糊的地方（H8/H9 的教训）：
 * <ul>
 *   <li>{@code totalBeds}，房间可用床位总数（排除停用床位）；</li>
 *   <li>{@code idleBeds}，<b>此刻</b>空闲的床位数（床位状态快照）；</li>
 *   <li>{@code availableBeds}，<b>此刻真正可被分配</b>的床位数，与写路径
 *       {@code ApplyService.approve → DormBedMapper.occupyBed}（{@code WHERE status='IDLE'}）
 *       逐字等价。<b>这是判断「能不能订」的唯一依据</b>：前端据此启用预订按钮，
 *       它不随查询日期变化，因为写路径也不随日期变化。</li>
 *   <li>{@code bookableBeds}，<b>时间维度</b>上不与既有申请冲突的床位数
 *       （指定日期区间内的"届时会有房"）。它只用于提示，<b>不能</b>当作可订判据：
 *       一张此刻被占用、对方下个月退房的床会算在这里，但审批时 {@code occupyBed}
 *       会因它不是 IDLE 而拒绝（旧实现正是把这两个概念混成一个字段，
 *       实测出现 76 vs 59 的"宣称可订、实际分配失败"）。</li>
 * </ul>
 *
 * <p>不传日期时 {@code bookableBeds} 退化为"没有任何生效申请"，与
 * {@code availableBeds} 一致；传日期时二者可能不同，此时以 {@code availableBeds} 为准。
 */
@Data
public class RoomAvailabilityRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long roomId;
    private Long buildingId;
    private String buildingName;
    private String buildingCode;
    private Long regionId;
    private String regionName;
    private String city;
    private String address;
    private String roomNo;
    private Integer floorNo;
    private String roomType;
    private String roomTypeLabel;
    private Integer capacity;
    private String genderLimit;
    private String status;
    private BigDecimal pricePerNight;
    private String currency;
    private String facilities;
    private String remark;

    /** 此刻空闲床位数。 */
    private Integer idleBeds;
    /** 此刻可被分配的床位数（= 写路径口径，判断"能不能订"的唯一依据）。 */
    private Integer availableBeds;
    /** 指定日期区间内无冲突的床位数（"届时会有房"的提示，不代表此刻可分配）。 */
    private Integer bookableBeds;
    /** 可用床位总数。 */
    private Integer totalBeds;

    /** 床位明细（仅在查询单房间时填充）。 */
    private List<BedDetail> beds;

    /** 住宿晚数（用于前端展示总价）。 */
    private Integer nights;

    /**
     * 预计总价 = 单晚价 × 晚数。
     *
     * <p>方法名必须是 {@code getXxx} 形式：Jackson 只识别标准 getter，
     * 写成 {@code estimatedAmount()} 会被静默忽略，前端拿到的字段直接消失，
     * 而且不会报任何错。这类"字段无声消失"的问题在联调时极其耗时。
     */
    public BigDecimal getEstimatedAmount() {
        if (pricePerNight == null || nights == null) {
            return null;
        }
        return pricePerNight.multiply(BigDecimal.valueOf(nights));
    }

    /**
     * 床位明细：让「分房界面」和「员工查房」都能看到每个床位被谁占用到什么时间，
     * 而不是只给一个「满/不满」的黑盒结论。
     */
    @Data
    public static class BedDetail implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long bedId;
        private String bedNo;
        private String status;
        /** 该床位在当前查询区间内是否可订。 */
        private Boolean availableForRange;
        private Long applyId;
        private String applyNo;
        private String employeeName;
        private LocalDate occupiedCheckIn;
        private LocalDate occupiedCheckOut;
    }
}
