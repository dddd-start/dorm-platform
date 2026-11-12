package com.overseas.dorm.domain.dto;

import com.overseas.dorm.common.enums.BedStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量启用 / 停用床位。
 */
public record BedStatusRequest(
        @NotEmpty(message = "请选择床位") List<Long> bedIds,
        @NotNull(message = "请选择目标状态") BedStatus status
) {
}
