package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.overseas.dorm.common.enums.UserRole;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户。
 *
 * <p>敏感信息处理：手机号<b>不落明文</b>。
 * <ul>
 *   <li>{@code phoneCipher} —— AES-GCM 密文，仅在需要向本人展示/编辑时解密；</li>
 *   <li>{@code phoneMask} —— 掩码（138****8000），列表页与 AI Prompt 只用它；</li>
 *   <li>{@code phoneHash} —— HMAC-SHA256 摘要，用于按手机号精确查询。</li>
 * </ul>
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;
    /** BCrypt 哈希，永不出现在任何响应体中。 */
    private String password;
    private String realName;
    private String employeeNo;
    private UserRole role;
    /** 所属/负责区域；总管理员为 null。 */
    private Long regionId;
    private String department;

    private String phoneCipher;
    private String phoneMask;
    private String phoneHash;

    private String email;
    /** 1-正常 0-禁用。 */
    private Integer status;
    private Integer loginFailCount;
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;

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
