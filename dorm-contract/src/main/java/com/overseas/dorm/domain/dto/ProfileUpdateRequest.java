package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改个人资料。
 *
 * <p>为什么手机号要单独一个字段而不是"顺手"接受昵称等字段：
 * 手机号属于 PII，走的是加密写入路径（见 PhoneCipher），
 * 与普通字段的处理链路完全不同，分开更不容易出事。
 */
public record ProfileUpdateRequest(
        @NotBlank(message = "姓名不能为空") @Size(max = 64) String realName,
        @Size(max = 64) String department,
        @Email(message = "邮箱格式不正确") @Size(max = 128) String email,
        @Pattern(regexp = "^$|^\\+?[0-9\\s-]{6,20}$", message = "手机号格式不正确") String phone
) {
}
