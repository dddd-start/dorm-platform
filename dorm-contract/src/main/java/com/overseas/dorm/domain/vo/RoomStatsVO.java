package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 房间维度的区域统计（用于管理看板与 Agent 运维工具）。
 *
 * <p>这个对象由 SQL 聚合直接映射，因此是可变 POJO 而不是 record：
 * MyBatis 通过 setter 注入结果列，record 需要构造函数参数名与列别名严格对齐，
 * 在别名较多时容易因为一个拼写差异静默变成 null。POJO + setter 更稳。
 */
@lombok.Data
public class RoomStatsVO implements Serializable {

    private Long regionId;
    private String regionName;

    private Integer roomCount;
    private Integer openRoomCount;
    private Integer closedRoomCount;
    private Integer maintenanceRoomCount;

    private Integer bedCount;
    private Integer idleBedCount;
    private Integer occupiedBedCount;
    private Integer disabledBedCount;

}
