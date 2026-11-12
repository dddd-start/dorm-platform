package com.overseas.dorm.boot.controller;

import com.overseas.dorm.domain.vo.UserOptionVO;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.OptionVO;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.boot.security.RequireRole;
import com.overseas.dorm.domain.dto.UserSaveRequest;
import com.overseas.dorm.domain.service.UserService;
import com.overseas.dorm.domain.vo.UserVO;
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
 * 用户管理接口（总管理员）。
 */
@Tag(name = "02-用户管理", description = "账号维护，仅总管理员可写")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户分页")
    @GetMapping
    @RequireRole(superAdminOnly = true)
    public Result<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) UserRole role,
                                          @RequestParam(required = false) Long regionId,
                                          @RequestParam(required = false) Integer status) {
        return Result.ok(userService.page(current, size, keyword, role, regionId, status));
    }

    @Operation(summary = "用户详情")
    @GetMapping("/{id}")
    public Result<UserVO> detail(@PathVariable Long id) {
        return Result.ok(userService.detail(id));
    }

    @Operation(summary = "新增/编辑用户")
    @PostMapping
    @RequireRole(superAdminOnly = true)
    public Result<UserVO> save(@Valid @RequestBody UserSaveRequest request) {
        return Result.ok(userService.save(request));
    }

    @Operation(summary = "启用/禁用账号")
    @PutMapping("/{id}/status")
    @RequireRole(superAdminOnly = true)
    public Result<Void> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.toggleStatus(id, status);
        return Result.ok();
    }

    @Operation(summary = "重置密码")
    @PutMapping("/{id}/password")
    @RequireRole(superAdminOnly = true)
    public Result<Void> resetPassword(@PathVariable Long id, @RequestParam String password) {
        userService.resetPassword(id, password);
        return Result.ok();
    }

    @Operation(summary = "用户下拉选项", description = "管理员分房、指派负责人时使用")
    @GetMapping("/options")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<List<UserOptionVO>> options(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) UserRole role) {
        return Result.ok(userService.options(keyword, role));
    }

    @Operation(summary = "角色选项")
    @GetMapping("/roles")
    public Result<List<OptionVO>> roles() {
        return Result.ok(UserRole.options());
    }

    @Operation(summary = "账号状态选项")
    @GetMapping("/statuses")
    public Result<List<OptionVO>> statuses() {
        return Result.ok(AuditEnums.enableOptions());
    }
}
