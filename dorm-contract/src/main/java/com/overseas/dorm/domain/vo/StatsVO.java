package com.overseas.dorm.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 管理看板总览。
 */
@Data
public class StatsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 房间数 / 开放房间数 / 关闭房间数 / 维修房间数。 */
    private long roomCount;
    private long openRoomCount;
    private long closedRoomCount;
    private long maintenanceRoomCount;

    /** 床位数 / 空闲 / 占用 / 停用。 */
    private long bedCount;
    private long idleBedCount;
    private long occupiedBedCount;
    private long disabledBedCount;

    /** 入住率 = 占用 / 可用。 */
    private BigDecimal occupancyRate;

    /** 申请各状态数量。 */
    private Map<String, Long> applyStatusCount;

    /** 订单各状态数量。 */
    private Map<String, Long> orderStatusCount;

    /** 近 N 天申请趋势。 */
    private List<DailyCountVO> applyTrend;

    /** 各区域房源分布。 */
    private List<RoomStatsVO> regionDistribution;
}
