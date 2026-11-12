package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码。
 */
public record PasswordChangeRequest(
        @NotBlank(message = "请输入原密码") String oldPassword,
        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 64, message = "新密码长度需为 8-64 位") String newPassword
) {
}
