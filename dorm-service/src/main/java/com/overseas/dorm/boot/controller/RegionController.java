package com.overseas.dorm.boot.controller;

import com.overseas.dorm.domain.vo.BedSummaryVO;
import com.overseas.dorm.domain.vo.RegionOptionVO;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.boot.security.RequireRole;
import com.overseas.dorm.domain.dto.RegionSaveRequest;
import com.overseas.dorm.domain.service.RegionService;
import com.overseas.dorm.domain.vo.RegionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 区域管理接口。
 */
@Tag(name = "03-区域管理", description = "海外区域维护；区域管理员只能看到自己的区域")
@RestController
@RequestMapping("/api/regions")
@RequiredArgsConstructor
public class RegionController {

    private final RegionService regionService;

    @Operation(summary = "区域列表", description = "withStats=true 时附带房间/床位/空闲床位统计")
    @GetMapping
    public Result<List<RegionVO>> list(@RequestParam(required = false) Integer status,
                                       @RequestParam(defaultValue = "true") boolean withStats) {
        return Result.ok(regionService.list(status, withStats));
    }

    @Operation(summary = "区域下拉选项", description = "登录后即可访问，用于选择出差目的地区域")
    @GetMapping("/options")
    public Result<List<RegionOptionVO>> options() {
        return Result.ok(regionService.options());
    }

    @Operation(summary = "区域详情")
    @GetMapping("/{id}")
    public Result<RegionVO> detail(@PathVariable Long id) {
        return Result.ok(regionService.detail(id));
    }

    @Operation(summary = "新增/编辑区域（仅总管理员）")
    @PostMapping
    @RequireRole(superAdminOnly = true)
    public Result<RegionVO> save(@Valid @RequestBody RegionSaveRequest request) {
        return Result.ok(regionService.save(request));
    }

    @Operation(summary = "启用/停用区域（仅总管理员）", description = "停用会级联关闭该区域所有开放房间")
    @PutMapping("/{id}/status")
    @RequireRole(superAdminOnly = true)
    public Result<Void> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        regionService.toggleStatus(id, status);
        return Result.ok();
    }

    @Operation(summary = "区域床位资源概览")
    @GetMapping("/{id}/beds/summary")
    public Result<BedSummaryVO> bedSummary(@PathVariable Long id) {
        return Result.ok(regionService.bedSummary(id));
    }
}
