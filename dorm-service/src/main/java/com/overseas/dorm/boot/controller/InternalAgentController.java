package com.overseas.dorm.boot.controller;

import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.domain.dto.ApplyCancelRequest;
import com.overseas.dorm.domain.dto.ApplyCreateRequest;
import com.overseas.dorm.domain.dto.CheckInRequest;
import com.overseas.dorm.domain.dto.CheckOutRequest;
import com.overseas.dorm.domain.dto.RoomAvailabilityRow;
import com.overseas.dorm.domain.entity.DormBuilding;
import com.overseas.dorm.domain.entity.DormRoom;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.entity.StayOrder;
import com.overseas.dorm.domain.service.ApplyService;
import com.overseas.dorm.domain.service.AuditService;
import com.overseas.dorm.domain.service.AuthService;
import com.overseas.dorm.domain.service.BuildingService;
import com.overseas.dorm.domain.service.OrderService;
import com.overseas.dorm.domain.service.RegionService;
import com.overseas.dorm.domain.service.RoomService;
import com.overseas.dorm.domain.service.StatsService;
import com.overseas.dorm.domain.vo.ApplyVO;
import com.overseas.dorm.domain.vo.BedSummaryVO;
import com.overseas.dorm.domain.vo.BuildingOptionVO;
import com.overseas.dorm.domain.vo.OccupancyVO;
import com.overseas.dorm.domain.vo.OrderVO;
import com.overseas.dorm.domain.vo.RegionOptionVO;
import com.overseas.dorm.domain.vo.RegionVO;
import com.overseas.dorm.domain.vo.RoomStatsVO;
import com.overseas.dorm.domain.vo.StatsVO;
import com.overseas.dorm.domain.vo.UserVO;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 供 agent-service 调用的内部接口。
 *
 * <p>为什么单独开一组接口，而不是让 Agent 直接复用 {@code /api/**}：
 * <ul>
 *   <li>Agent 需要的是“工具形状”的数据（例如区域 + 床位汇总、房间 + 床位明细），
 *       与页面所需的 CRUD 形状不同，复用会造成前端契约被 Agent 需求拖拽；</li>
 *   <li>内部接口只允许携带内部令牌的调用方访问，攻击面比公开 API 小；</li>
 *   <li>身份仍来自用户 JWT（由 {@code InternalAuthInterceptor} 解析并做会话校验），
 *       Agent 无法伪造 userId 越权。</li>
 * </ul>
 */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalAgentController {

    private final RegionService regionService;
    private final BuildingService buildingService;
    private final RoomService roomService;
    private final ApplyService applyService;
    private final OrderService orderService;
    private final StatsService statsService;
    private final AuthService authService;
    private final AuditService auditService;

    // ---------------------------------------------------------------- 健康与身份

    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("ok");
    }

    @GetMapping("/agent/profile")
    public Result<UserVO> profile() {
        return Result.ok(authService.currentUser(false));
    }

    // ---------------------------------------------------------------- 区域 / 公寓

    @GetMapping("/agent/regions/options")
    public Result<List<RegionOptionVO>> regionOptions() {
        return Result.ok(regionService.options());
    }

    /** 服务级只读目录：只校验内部令牌，供 Agent 启动装载知识库时解析区域编码。 */
    @GetMapping("/catalog/regions/options")
    public Result<List<RegionOptionVO>> catalogRegionOptions() {
        return Result.ok(regionService.options());
    }

    @GetMapping("/agent/regions")
    public Result<List<RegionVO>> regions(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "false") boolean withStats) {
        return Result.ok(regionService.list(status, withStats));
    }

    @GetMapping("/agent/regions/{id}")
    public Result<Region> region(@PathVariable Long id) {
        return Result.ok(regionService.requireEnabled(id));
    }

    @GetMapping("/agent/regions/{id}/beds/summary")
    public Result<BedSummaryVO> regionBedSummary(@PathVariable Long id) {
        return Result.ok(regionService.bedSummary(id));
    }

    @GetMapping("/agent/buildings/options")
    public Result<List<BuildingOptionVO>> buildingOptions(
            @RequestParam(required = false) Long regionId) {
        return Result.ok(buildingService.options(regionId));
    }

    @GetMapping("/agent/buildings/map")
    public Result<List<DormBuilding>> buildingsMap(@RequestParam List<Long> ids) {
        return Result.ok(List.copyOf(buildingService.buildingMap(ids).values()));
    }

    // ---------------------------------------------------------------- 房间 / 房源

    @GetMapping("/agent/rooms/{id}")
    public Result<DormRoom> room(@PathVariable Long id) {
        return Result.ok(roomService.requireRoom(id));
    }

    @GetMapping("/agent/rooms/{id}/beds")
    public Result<List<RoomAvailabilityRow.BedDetail>> roomBeds(@PathVariable Long id) {
        return Result.ok(roomService.bedDetails(id));
    }

    @GetMapping("/agent/rooms/availability")
    public Result<List<RoomAvailabilityRow>> roomAvailability(
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) RoomType roomType,
            @RequestParam(required = false) GenderLimit gender,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam(defaultValue = "1") int minBeds) {
        return Result.ok(roomService.searchAvailability(regionId, buildingId, city, roomType,
                gender, checkIn, checkOut, minBeds));
    }

    @GetMapping("/agent/rooms/statistics")
    public Result<List<RoomStatsVO>> roomStatistics(@RequestParam(required = false) Long regionId) {
        return Result.ok(roomService.statisticsByRegion(regionId));
    }

    // ---------------------------------------------------------------- 申请

    @GetMapping("/agent/applies")
    public Result<PageResult<ApplyVO>> applies(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) ApplyStatus status,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(applyService.page(current, size, status, regionId, employeeId,
                keyword, from, to));
    }

    @GetMapping("/agent/applies/upcoming")
    public Result<List<ApplyVO>> upcomingApplies() {
        return Result.ok(applyService.myUpcoming());
    }

    @GetMapping("/agent/applies/{id}")
    public Result<ApplyVO> applyDetail(@PathVariable Long id) {
        return Result.ok(applyService.detail(id));
    }

    @PostMapping("/agent/applies")
    public Result<ApplyVO> createApply(@Valid @RequestBody ApplyCreateRequest request) {
        return Result.ok(applyService.create(request, ApplySource.AGENT));
    }

    @PostMapping("/agent/applies/{id}/cancel")
    public Result<ApplyVO> cancelApply(@PathVariable Long id,
                                       @RequestBody(required = false) CancelBody body) {
        String reason = body == null || body.reason() == null || body.reason().isBlank()
                ? "用户通过 AI 助手取消" : body.reason();
        return Result.ok(applyService.cancel(new ApplyCancelRequest(id, reason)));
    }

    @PostMapping("/agent/applies/check-in")
    public Result<ApplyVO> checkIn(@Valid @RequestBody CheckInRequest request) {
        return Result.ok(applyService.checkIn(request));
    }

    @PostMapping("/agent/applies/check-out")
    public Result<ApplyVO> checkOut(@Valid @RequestBody CheckOutRequest request) {
        return Result.ok(applyService.checkOut(request));
    }

    /** 取消申请的请求体。 */
    public record CancelBody(String reason) {
    }

    // ---------------------------------------------------------------- 订单 / 统计

    @GetMapping("/agent/orders/mine")
    public Result<List<OrderVO>> myOrders() {
        return Result.ok(orderService.myOrders());
    }

    @GetMapping("/agent/orders/{id}")
    public Result<OrderVO> orderDetail(@PathVariable Long id) {
        return Result.ok(orderService.detail(id));
    }

    @GetMapping("/agent/orders")
    public Result<PageResult<OrderVO>> orders(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(orderService.page(current, size, status, regionId, employeeId,
                keyword, from, to));
    }

    @GetMapping("/agent/orders/overdue")
    public Result<List<StayOrder>> overdueOrders(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(orderService.overdueOrders(limit));
    }

    @GetMapping("/agent/stats/overview")
    public Result<StatsVO> overview(@RequestParam(required = false) Long regionId,
                                    @RequestParam(defaultValue = "7") int trendDays) {
        return Result.ok(statsService.overview(regionId, trendDays));
    }

    @GetMapping("/agent/stats/occupancy")
    public Result<OccupancyVO> occupancy(@RequestParam(required = false) Long regionId) {
        return Result.ok(statsService.occupancy(regionId));
    }

    // ---------------------------------------------------------------- 审计

    /** Agent 轮次/工具的业务审计事件，由 dorm-service 统一写入 audit_log。 */
    public record AuditEvent(String module, String action, String targetType,
                             String targetId, String errorMsg, boolean success,
                             java.util.Map<String, Object> detail, Long costMs) {
    }

    @PostMapping("/agent/audit")
    public Result<Void> audit(@RequestBody AuditEvent event) {
        AuditEnums.Module module;
        try {
            module = event.module() == null ? AuditEnums.Module.AGENT
                    : AuditEnums.Module.valueOf(event.module());
        } catch (IllegalArgumentException e) {
            module = AuditEnums.Module.AGENT;
        }
        if (event.success()) {
            auditService.success(module, event.action(), event.targetType(),
                    event.targetId(), null, event.detail(), event.costMs());
        } else {
            auditService.fail(module, event.action(), event.targetType(),
                    event.targetId(), event.errorMsg());
        }
        return Result.ok();
    }
}
