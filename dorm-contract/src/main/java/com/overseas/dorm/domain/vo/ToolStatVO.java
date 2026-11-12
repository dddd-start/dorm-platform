package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 单个工具的调用统计（SQL 聚合结果）。
 *
 * <p>失败率高的工具往往意味着参数校验缺失或上游数据有问题；
 * 平均耗时高的工具则是优化目标。这张表让这两类问题从"感觉"变成"数字"。
 */
@lombok.Data
public class ToolStatVO implements Serializable {

    /** 工具名。 */
    private String toolName;

    /** 调用总次数。 */
    private Long total;

    /** 失败次数。 */
    private Long failed;

    /** 平均耗时（毫秒）。 */
    private Long avgCostMs;
}
