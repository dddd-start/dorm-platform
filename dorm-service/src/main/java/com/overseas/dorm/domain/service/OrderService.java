package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.domain.entity.DormBed;
import com.overseas.dorm.domain.entity.DormBuilding;
import com.overseas.dorm.domain.entity.DormRoom;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.entity.StayOrder;
import com.overseas.dorm.domain.mapper.StayOrderMapper;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.support.StatusSummaryBuilder;
import com.overseas.dorm.domain.support.PageMapper;
import com.overseas.dorm.domain.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 入住订单查询服务。
 *
 * <p>订单的写操作（生成、入住、退房、取消）都发生在 {@link ApplyService} 里 ——
 * 这是刻意的：订单状态与申请状态必须同事务变更，否则会出现
 * 「申请已退房但订单还在住」这种两边对不上的情况。
 * 本类只负责查询与统计，职责单一。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final StayOrderMapper orderMapper;
    private final com.overseas.dorm.domain.mapper.DormRoomMapper roomMapper;
    private final com.overseas.dorm.domain.mapper.DormBedMapper bedMapper;
    private final com.overseas.dorm.domain.mapper.DormBuildingMapper buildingMapper;
    private final com.overseas.dorm.domain.mapper.RegionMapper regionMapper;
    private final DataScope dataScope;

    public PageResult<OrderVO> page(long current, long size, OrderStatus status, Long regionId,
                                    Long employeeId, String keyword, LocalDate from, LocalDate to) {
        LoginUser user = UserContext.require();
        LambdaQueryWrapper<StayOrder> wrapper = new LambdaQueryWrapper<>();
        if (user.isEmployee()) {
            wrapper.eq(StayOrder::getEmployeeId, user.getUserId());
        } else if (user.isSuperAdmin()) {
            wrapper.eq(regionId != null, StayOrder::getRegionId, regionId);
            wrapper.eq(employeeId != null, StayOrder::getEmployeeId, employeeId);
        } else {
            wrapper.eq(StayOrder::getRegionId, user.getRegionId());
            wrapper.eq(employeeId != null, StayOrder::getEmployeeId, employeeId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(StayOrder::getOrderNo, keyword)
                    .or().like(StayOrder::getApplyNo, keyword)
                    .or().like(StayOrder::getEmployeeName, keyword));
        }
        wrapper.eq(status != null, StayOrder::getStatus, status)
                .ge(from != null, StayOrder::getCheckInDate, from)
                .le(to != null, StayOrder::getCheckOutDate, to)
                .orderByDesc(StayOrder::getCreateTime);

        var page = orderMapper.selectPage(PageMapper.page(current, size), wrapper);
        return PageMapper.of(page, o -> enrich(OrderVO.of(o)));
    }

    public OrderVO detail(Long id) {
        StayOrder order = requireOrder(id);
        LoginUser user = UserContext.require();
        if (user.isEmployee()) {
            dataScope.assertSelfOrAdmin(order.getEmployeeId());
        } else {
            dataScope.assertRegionAccess(order.getRegionId());
        }
        return enrich(OrderVO.of(order));
    }

    /** 员工端「我的订单」。 */
    public List<OrderVO> myOrders() {
        Long userId = UserContext.requireUserId();
        return orderMapper.selectList(new LambdaQueryWrapper<StayOrder>()
                        .eq(StayOrder::getEmployeeId, userId)
                        .orderByDesc(StayOrder::getCreateTime))
                .stream().map(OrderVO::of).map(this::enrich).toList();
    }

    private OrderVO enrich(OrderVO vo) {
        if (vo.getRegionId() != null) {
            Region region = regionMapper.selectById(vo.getRegionId());
            vo.setRegionName(region == null ? null : region.getName());
        }
        if (vo.getBuildingId() != null) {
            DormBuilding building = buildingMapper.selectById(vo.getBuildingId());
            vo.setBuildingName(building == null ? null : building.getName());
        }
        if (vo.getRoomId() != null) {
            DormRoom room = roomMapper.selectById(vo.getRoomId());
            if (room != null) {
                vo.setRoomNo(room.getRoomNo());
                if (vo.getBuildingName() == null && room.getBuildingId() != null) {
                    DormBuilding building = buildingMapper.selectById(room.getBuildingId());
                    vo.setBuildingName(building == null ? null : building.getName());
                }
            }
        }
        if (vo.getBedId() != null) {
            DormBed bed = bedMapper.selectById(vo.getBedId());
            vo.setBedNo(bed == null ? null : bed.getBedNo());
        }
        return vo;
    }

    public StayOrder requireOrder(Long id) {
        StayOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw BizException.of(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /** 已到退房日但未退房的订单（运营提醒）。 */
    public List<StayOrder> overdueOrders(int limit) {
        return orderMapper.selectOverdue(limit);
    }

    /** 统计各状态数量（看板 / Agent 工具）；汇总逻辑与申请侧共用同一实现。 */
    public Map<String, Long> statusSummary(Long regionId) {
        // 【H7 修复】与申请侧共用同一套粒度规则（DataScope.statisticsScope）：
        // 总管理员不限、区域管理员看本区域、员工只看自己。
        DataScope.StatsScope scope = dataScope.statisticsScope(regionId);
        return StatusSummaryBuilder.build(
                orderMapper.countByStatus(scope.regionId(), scope.employeeId()));
    }
}
