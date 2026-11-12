package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 按状态分组计数（SQL 聚合结果）。
 *
 * <p>为什么用 POJO 而不是 {@code Map<String, Object>}：SQL 聚合结果在 MyBatis 里
 * 用 Map 接是"最省事"的写法，但列别名一旦拼错，得到的是 {@code null} 而不是报错，
 * 于是统计数字静默变成 0，没人会发现。用具体类型接，字段名对不上时
 * 至少能在联调时立刻看出来。
 */
@lombok.Data
public class StatusCountVO implements Serializable {

    /** 状态名（与对应枚举的 name() 一致）。 */
    private String status;

    /** 该状态下的记录数。 */
    private Long cnt;
}
