package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 床位占用统计（SQL 单行聚合结果）。
 *
 * <p>只承载"占用数 / 可用数"两个原始计数，入住率等派生值在服务层计算 ——
 * 让 SQL 只负责取数、Java 负责算，两者职责清晰，也便于单测。
 */
@lombok.Data
public class OccupancyRowVO implements Serializable {

    /** 已占用床位数（含已入住与已分配）。 */
    private Integer occupiedBeds;

    /** 可用床位数（总床位减去停用床位）。 */
    private Integer usableBeds;
}
