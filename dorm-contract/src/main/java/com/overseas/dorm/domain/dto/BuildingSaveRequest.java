package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 公寓新增/编辑。
 */
public record BuildingSaveRequest(
        Long id,
        @NotNull(message = "请选择所属区域") Long regionId,
        @NotBlank(message = "公寓编码不能为空") @Size(max = 32) String code,
        @NotBlank(message = "公寓名称不能为空") @Size(max = 128) String name,
        @Size(max = 64) String country,
        @Size(max = 64) String city,
        @Size(max = 255) String address,
        @Size(max = 64) String contactName,
        @Size(max = 64) String contactPhone,
        Integer status,
        @Size(max = 255) String remark
) {
}
