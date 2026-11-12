package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 公寓下拉选项（固定形状，故用 record）。
 */
public record BuildingOptionVO(
        Long id,
        Long regionId,
        String code,
        String name,
        String city,
        String address
) implements Serializable {
}
