package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 公寓维度的房源统计（SQL 聚合结果）。
 *
 * <p>它替代了原来"先查房间聚合、再对每栋楼单独查一次空闲床位"的写法。
 * 那种写法是典型的 N+1：一页 10 栋楼就要多打 10 次数据库，
 * 在管理看板上表现为"房间越多、列表越慢"，而单看代码很难察觉。
 */
@lombok.Data
public class BuildingStatsVO implements Serializable {

    private Long buildingId;

    private Integer roomCount;

    private Integer bedCount;

    /** 空闲床位数（仅统计 IDLE，用于判断"这栋楼还有没有位置"）。 */
    private Integer idleBedCount;
}
