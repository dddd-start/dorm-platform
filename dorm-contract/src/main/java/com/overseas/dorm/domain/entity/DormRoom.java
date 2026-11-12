package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.RoomStatus;
import com.overseas.dorm.common.enums.RoomType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 房间。
 *
 * <p>{@code regionId} 是冗余字段：房间归属最终由公寓决定，
 * 但数据权限过滤与「按区域查空房」是最高频的查询，
 * 冗余一列区域 ID 换来的是避免每次查询都 JOIN 公寓表。
 * 冗余带来的是一致性责任 —— 公寓换区域时必须同步刷房间（见 RoomService）。
 */
@Data
@TableName("dorm_room")
public class DormRoom implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long buildingId;
    /** 冗余：所属区域，用于数据权限与高频查询。 */
    private Long regionId;
    private String roomNo;
    private Integer floorNo;
    private RoomType roomType;
    /** 床位数，与房型容量一致。 */
    private Integer capacity;
    private GenderLimit genderLimit;
    private RoomStatus status;
    private BigDecimal areaSqm;
    private BigDecimal pricePerNight;
    private String currency;
    /** 设施，逗号分隔：WIFI,AC,DESK,BATHROOM。 */
    private String facilities;
    private String remark;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
