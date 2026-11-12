package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 海外区域。
 *
 * <p>区域是「数据权限」的划分单位：区域管理员只能看到自己区域的申请、订单、
 * 房间与统计；总管理员跨区域。员工提交申请时先选区域，再挑公寓。
 */
@Data
@TableName("region")
public class Region implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String code;
    private String name;
    private String nameEn;
    private String country;
    private String countryCode;
    /** IANA 时区，如 Asia/Singapore —— 仅用于展示，存储统一 Asia/Shanghai。 */
    private String timezone;
    private String currency;
    /** 区域管理员用户 ID。 */
    private Long managerId;
    /** 1-启用 0-停用。 */
    private Integer status;
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
