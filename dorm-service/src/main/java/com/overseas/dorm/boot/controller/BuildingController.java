package com.overseas.dorm.boot.controller;

import com.overseas.dorm.domain.vo.BuildingOptionVO;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.boot.security.RequireRole;
import com.overseas.dorm.domain.dto.BuildingSaveRequest;
import com.overseas.dorm.domain.service.BuildingService;
import com.overseas.dorm.domain.vo.BuildingVO;
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
 * 公寓管理接口。
 */
@Tag(name = "04-公寓管理", description = "宿舍楼维护；区域管理员仅限本区域")
@RestController
@RequestMapping("/api/buildings")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildingService;

    @Operation(summary = "公寓分页")
    @GetMapping
    public Result<PageResult<BuildingVO>> page(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Long regionId,
                                              @RequestParam(required = false) Integer status) {
        return Result.ok(buildingService.page(current, size, keyword, regionId, status));
    }

    @Operation(summary = "公寓下拉选项", description = "员工查房源时按区域筛选公寓")
    @GetMapping("/options")
    public Result<List<BuildingOptionVO>> options(@RequestParam(required = false) Long regionId) {
        return Result.ok(buildingService.options(regionId));
    }

    @Operation(summary = "公寓详情")
    @GetMapping("/{id}")
    public Result<BuildingVO> detail(@PathVariable Long id) {
        return Result.ok(buildingService.detail(id));
    }

    @Operation(summary = "新增/编辑公寓")
    @PostMapping
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<BuildingVO> save(@Valid @RequestBody BuildingSaveRequest request) {
        return Result.ok(buildingService.save(request));
    }

    @Operation(summary = "启用/停用公寓", description = "停用会级联关闭该公寓所有开放房间")
    @PutMapping("/{id}/status")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<Void> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        buildingService.toggleStatus(id, status);
        return Result.ok();
    }
}
