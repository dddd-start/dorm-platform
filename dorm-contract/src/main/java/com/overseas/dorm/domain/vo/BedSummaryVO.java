package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 区域床位资源概览。
 */
public record BedSummaryVO(
        Long regionId,
        long idleBeds,
        long totalBeds
) implements Serializable {
}
