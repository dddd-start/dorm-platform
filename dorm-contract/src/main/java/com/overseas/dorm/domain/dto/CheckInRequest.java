package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 办理入住。
 */
public record CheckInRequest(
        @NotNull(message = "缺少申请ID") Long applyId,
        @Size(max = 255) String remark
) {
}
