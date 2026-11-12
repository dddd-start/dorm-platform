package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 按天计数（SQL 聚合结果，用于趋势折线图）。
 */
@lombok.Data
public class DailyCountVO implements Serializable {

    /** 日期，格式 yyyy-MM-dd。 */
    private String day;

    /** 当天数量。 */
    private Long cnt;
}
