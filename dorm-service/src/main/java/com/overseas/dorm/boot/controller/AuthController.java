package com.overseas.dorm.boot.controller;

import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.domain.dto.LoginRequest;
import com.overseas.dorm.domain.dto.PasswordChangeRequest;
import com.overseas.dorm.domain.dto.ProfileUpdateRequest;
import com.overseas.dorm.domain.service.AuthService;
import com.overseas.dorm.domain.support.RequestInfo;
import com.overseas.dorm.domain.vo.LoginVO;
import com.overseas.dorm.domain.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与个人中心接口。
 */
@Tag(name = "01-认证", description = "登录、登出、个人资料")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录", description = "成功后返回 JWT token，后续请求放在 Authorization: Bearer <token>")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request, RequestInfo.clientIp()));
    }

    @Operation(summary = "登出", description = "使当前 token 立即失效（会话白名单移除）")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(UserContext.get());
        return Result.ok();
    }

    @Operation(summary = "当前登录用户", description = "不返回明文手机号，只有掩码")
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.currentUser(false));
    }

    @Operation(summary = "我的完整资料", description = "仅本人可见，手机号解密返回（用于资料编辑页回显）")
    @GetMapping("/me/profile")
    public Result<UserVO> myProfile() {
        return Result.ok(authService.currentUser(true));
    }

    @Operation(summary = "修改个人资料")
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return Result.ok(authService.updateProfile(request));
    }

    @Operation(summary = "修改密码", description = "修改后建议前端引导用户重新登录")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        authService.changePassword(request);
        return Result.ok();
    }

    @Operation(summary = "心跳", description = "前端用于检测登录态是否仍然有效")
    @GetMapping("/ping")
    public Result<LoginUser> ping() {
        return Result.ok(UserContext.require());
    }
}
