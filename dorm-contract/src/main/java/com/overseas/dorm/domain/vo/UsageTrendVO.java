package com.overseas.dorm.domain.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日用量趋势（SQL 聚合结果）。
 *
 * <p>注意 {@code statDate} 用 {@link LocalDate} 而不是 String：日期是日期，
 * 让 Jackson 按 ISO 格式序列化比让 SQL 输出字符串更可靠，
 * 也避免"有的地方返回 {@code 2026-10-14}、有的地方返回 {@code 2026-10-14 00:00:00}"
 * 这种前端必须做兼容处理的情况。
 */
@lombok.Data
public class UsageTrendVO implements Serializable {

    /** 统计日期。 */
    private LocalDate statDate;

    /** 轮次总数。 */
    private Long turns;

    /** token 总数。 */
    private Long tokens;

    /** 工具调用总数。 */
    private Long toolCalls;

    /** 估算成本（元）。 */
    private BigDecimal cost;
}
