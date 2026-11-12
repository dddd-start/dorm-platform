package com.overseas.dorm.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 床位占用率概览。
 *
 * <p>{@code occupancyRate} 用 {@link BigDecimal}（百分比数值）而不是形如 {@code "12.5%"} 的字符串：
 * 字符串把"数值"和"展示格式"混在了一起，前端要做比较、排序、变色时还得再解析一次；
 * 百分比符号属于展示层，应由前端决定。
 */
public record OccupancyVO(
        long occupiedBeds,
        long usableBeds,
        long idleBeds,
        BigDecimal occupancyRate,
        String scope
) implements Serializable {
}
