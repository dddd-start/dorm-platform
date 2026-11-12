package com.overseas.dorm.boot.controller;

import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.domain.service.OrderService;
import com.overseas.dorm.domain.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 入住订单查询接口。
 *
 * <p>订单的写操作在 {@code ApplyService} 中（与申请状态同事务变更），
 * 这里只提供查询与统计 —— 职责单一，也让「订单不会被谁随意改动」这件事
 * 在接口层面就成立。
 */
@Tag(name = "07-入住订单", description = "订单查询与统计")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "订单分页", description = "员工仅见自己的订单；区域管理员仅见本区域")
    @GetMapping
    public Result<PageResult<OrderVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(orderService.page(current, size, status, regionId, employeeId, keyword, from, to));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.ok(orderService.detail(id));
    }

    @Operation(summary = "我的订单")
    @GetMapping("/mine")
    public Result<List<OrderVO>> mine() {
        return Result.ok(orderService.myOrders());
    }

    @Operation(summary = "订单状态统计")
    @GetMapping("/statistics")
    public Result<Map<String, Long>> statistics(@RequestParam(required = false) Long regionId) {
        return Result.ok(orderService.statusSummary(regionId));
    }

    @Operation(summary = "已到期未退房的订单", description = "运营提醒用，管理员可见")
    @GetMapping("/overdue")
    public Result<List<OrderVO>> overdue(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(orderService.overdueOrders(limit).stream().map(OrderVO::of).toList());
    }
}
