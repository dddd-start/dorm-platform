package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 区域新增/编辑。
 */
public record RegionSaveRequest(
        Long id,
        @NotBlank(message = "区域编码不能为空") @Size(max = 32) String code,
        @NotBlank(message = "区域名称不能为空") @Size(max = 64) String name,
        @Size(max = 64) String nameEn,
        @Size(max = 64) String country,
        @Size(max = 8) String countryCode,
        @Size(max = 64) String timezone,
        @Size(max = 8) String currency,
        Long managerId,
        Integer status,
        @Size(max = 255) String remark
) {
}
