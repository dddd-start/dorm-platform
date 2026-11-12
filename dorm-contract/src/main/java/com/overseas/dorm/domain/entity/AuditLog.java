package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作审计日志。
 *
 * <p>写审计有两条铁律：
 * <ol>
 *   <li><b>写审计不能影响主业务</b>：审计写入失败只记日志，不抛异常回滚业务；</li>
 *   <li><b>审计必须能还原现场</b>：关键变更记录变更前后的 JSON 快照，
 *       而不是只记「某某修改了房间」。这是「可解释、可审计、可回溯」的落地要求。</li>
 * </ol>
 */
@Data
@TableName("audit_log")
public class AuditLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;
    private Long userId;
    private String userName;
    private String role;
    private String module;
    private String action;
    private String targetType;
    private String targetId;
    private String beforeJson;
    private String afterJson;
    private String result;
    private String errorMsg;
    private String ip;
    private String userAgent;
    private Long costMs;
    private LocalDateTime createTime;
}
