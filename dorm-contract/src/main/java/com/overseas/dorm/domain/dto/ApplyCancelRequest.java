package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 取消申请（员工本人或管理员）。
 */
public record ApplyCancelRequest(
        @NotNull(message = "缺少申请ID") Long applyId,
        @Size(max = 255) String reason
) {
}
