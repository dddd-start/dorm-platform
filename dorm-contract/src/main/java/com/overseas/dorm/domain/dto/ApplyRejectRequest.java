package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 驳回申请。
 */
public record ApplyRejectRequest(
        @NotNull(message = "缺少申请ID") Long applyId,
        @NotBlank(message = "请填写驳回原因") @Size(max = 255) String reason
) {
}
