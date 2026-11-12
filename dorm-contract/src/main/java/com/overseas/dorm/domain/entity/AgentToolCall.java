package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 工具调用审计。
 *
 * <p>这张表是「Agent 可解释、可审计、可回放」的核心证据链：
 * 出了问题时，能回答「模型当时调用了哪个工具、传了什么参数、拿到什么结果、
 * 是否通过权限校验、是否被去重守卫拦截、耗时多少、traceId 是什么」。
 */
@Data
@TableName("agent_tool_call")
public class AgentToolCall implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;
    private Long messageId;
    private String turnId;
    private Integer seqInTurn;
    private String toolName;
    private String argumentsJson;
    private String resultJson;
    private Integer success;
    private String errorMsg;
    /** 是否通过权限校验（0 表示被拒绝，说明模型越权尝试过）。 */
    private Integer permissionChecked;
    /** 是否被「同轮重复调用守卫」命中并直接返回缓存结果。 */
    private Integer deduplicated;
    private Long costMs;
    private String traceId;
    private LocalDateTime createTime;
}
