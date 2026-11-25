package com.overseas.dorm.domain.service;

import com.overseas.dorm.common.enums.BedStatus;
import com.overseas.dorm.domain.mapper.DormRoomMapper;
import com.overseas.dorm.domain.mapper.StayApplyMapper;
import com.overseas.dorm.domain.mapper.StayOrderMapper;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.vo.DailyCountVO;
import com.overseas.dorm.domain.vo.OccupancyRowVO;
import com.overseas.dorm.domain.vo.OccupancyVO;
import com.overseas.dorm.domain.vo.RoomStatsVO;
import com.overseas.dorm.domain.vo.StatsVO;
import com.overseas.dorm.domain.vo.StatusCountVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计服务（管理看板）。
 *
 * <p>所有统计口径都以服务端为准，且强制注入数据权限，* 「区域管理员看到的入住率」必须只是他自己区域的入住率，
 * 这类越权往往不是通过"读某个对象"发生，而是通过"读一个聚合数字"泄露的。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    /** 趋势图允许查询的天数范围。低于下限画不出趋势，高于上限会让聚合查询变慢。 */
    private static final int MIN_TREND_DAYS = 1;
    private static final int MAX_TREND_DAYS = 90;

    private final StayApplyMapper applyMapper;
    private final StayOrderMapper orderMapper;
    private final DormRoomMapper roomMapper;
    private final DataScope dataScope;

    public StatsVO overview(Long regionId, int trendDays) {
        // 设施类统计（房间/床位）仍按"可见区域"：房源是公共资源，员工可以查任何区域。
        Long scoped = dataScope.visibleRegionId() != null ? dataScope.visibleRegionId() : regionId;
        // 【H7 修复】但业务量统计（申请/订单状态分布与趋势）必须按统计粒度收窄：
        // 员工不应看到本区域所有人的申请与订单数量。同一个收口点，避免两套口径漂移。
        DataScope.StatsScope statsScope = dataScope.statisticsScope(regionId);
        StatsVO vo = new StatsVO();

        List<RoomStatsVO> regionRows = roomMapper.statisticsByRegion(scoped);
        long rooms = 0;
        long openRooms = 0;
        long closedRooms = 0;
        long maintenance = 0;
        long beds = 0;
        long idle = 0;
        long occupied = 0;
        long disabled = 0;
        for (RoomStatsVO row : regionRows) {
            rooms += num(row.getRoomCount());
            openRooms += num(row.getOpenRoomCount());
            closedRooms += num(row.getClosedRoomCount());
            maintenance += num(row.getMaintenanceRoomCount());
            beds += num(row.getBedCount());
            idle += num(row.getIdleBedCount());
            occupied += num(row.getOccupiedBedCount());
            disabled += num(row.getDisabledBedCount());
        }
        vo.setRoomCount(rooms);
        vo.setOpenRoomCount(openRooms);
        vo.setClosedRoomCount(closedRooms);
        vo.setMaintenanceRoomCount(maintenance);
        vo.setBedCount(beds);
        vo.setIdleBedCount(idle);
        vo.setOccupiedBedCount(occupied);
        vo.setDisabledBedCount(disabled);
        long usable = beds - disabled;
        vo.setOccupancyRate(usable <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(occupied)
                    .divide(BigDecimal.valueOf(usable), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP));

        // 状态分布是动态键的典型场景（键集合由数据决定），因此这里保留 Map，
        // 但把值类型收紧为 Long —— 动态键用 Map 是对的，"值为 Object"才是问题。
        Map<String, Long> applyStatus = new LinkedHashMap<>();
        for (StatusCountVO row : applyMapper.countByStatus(statsScope.regionId(), statsScope.employeeId())) {
            applyStatus.put(row.getStatus(), num(row.getCnt()));
        }
        vo.setApplyStatusCount(applyStatus);

        Map<String, Long> orderStatus = new LinkedHashMap<>();
        for (StatusCountVO row : orderMapper.countByStatus(statsScope.regionId(), statsScope.employeeId())) {
            orderStatus.put(row.getStatus(), num(row.getCnt()));
        }
        vo.setOrderStatusCount(orderStatus);

        int days = Math.clamp(trendDays, MIN_TREND_DAYS, MAX_TREND_DAYS);
        List<DailyCountVO> trend = applyMapper.countDailyTrend(
                statsScope.regionId(), statsScope.employeeId(), days);
        vo.setApplyTrend(trend);
        vo.setRegionDistribution(regionRows);
        return vo;
    }

    /**
     * 入住率（单独接口，Agent 工具会调用）。
     *
     * <p>改用 {@link OccupancyVO}：原来返回 {@code Map<String, Object>}，
     * 其中 {@code occupancyRate} 是形如 {@code "12.5%"} 的字符串，* 把"数值"和"展示格式"混在一起，前端要比较或排序还得再解析一次。
     * 现在返回百分比数值，符号由前端决定。
     */
    public OccupancyVO occupancy(Long regionId) {
        Long scoped = dataScope.visibleRegionId() != null ? dataScope.visibleRegionId() : regionId;
        OccupancyRowVO row = orderMapper.occupancy(scoped,
                BedStatus.OCCUPIED.name(), BedStatus.DISABLED.name());
        long occupied = num(row == null ? null : row.getOccupiedBeds());
        long usable = num(row == null ? null : row.getUsableBeds());
        BigDecimal rate = usable <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(occupied * 100.0 / usable).setScale(2, RoundingMode.HALF_UP);
        return new OccupancyVO(occupied, usable, usable - occupied, rate,
                scoped == null ? "全部区域" : String.valueOf(scoped));
    }

    private static long num(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
