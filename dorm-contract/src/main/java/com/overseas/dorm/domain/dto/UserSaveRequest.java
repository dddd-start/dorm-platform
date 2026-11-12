package com.overseas.dorm.domain.dto;

import com.overseas.dorm.common.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户新增/编辑（仅总管理员可用）。
 *
 * @param id       为空表示新增
 * @param password 新增时必填；编辑时留空表示不改密码
 */
public record UserSaveRequest(
        Long id,
        @NotBlank(message = "账号不能为空") @Size(max = 64) String username,
        @Size(max = 64) String password,
        @NotBlank(message = "姓名不能为空") @Size(max = 64) String realName,
        @Size(max = 32) String employeeNo,
        @NotNull(message = "请选择角色") UserRole role,
        Long regionId,
        @Size(max = 64) String department,
        @Email(message = "邮箱格式不正确") @Size(max = 128) String email,
        @Pattern(regexp = "^$|^\\+?[0-9\\s-]{6,20}$", message = "手机号格式不正确") String phone,
        Integer status
) {
}
