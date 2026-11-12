package com.overseas.dorm.boot.controller;

import com.overseas.dorm.domain.vo.OccupancyVO;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.boot.security.RequireRole;
import com.overseas.dorm.domain.service.StatsService;
import com.overseas.dorm.domain.vo.StatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 统计看板接口。
 *
 * <p>所有统计都自动受数据权限约束：区域管理员拿到的数字只覆盖自己的区域。
 * 「用聚合数字泄露其它区域信息」是一类容易被忽略的越权，因此这里
 * 不提供任何"传 regionId 就能跨区看"的旁路（总管理员除外）。
 */
@Tag(name = "08-统计看板", description = "房源与入住的运营指标")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "总览", description = "房间/床位/入住率/申请与订单状态分布/近 N 天趋势")
    @GetMapping("/overview")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<StatsVO> overview(@RequestParam(required = false) Long regionId,
                                    @RequestParam(defaultValue = "14") int trendDays) {
        return Result.ok(statsService.overview(regionId, trendDays));
    }

    @Operation(summary = "入住率", description = "占用床位 / 可用床位")
    @GetMapping("/occupancy")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<OccupancyVO> occupancy(@RequestParam(required = false) Long regionId) {
        return Result.ok(statsService.occupancy(regionId));
    }
}
