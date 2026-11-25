package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.overseas.dorm.common.enums.AgentEnums;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 消息。
 *
 * <p>几个字段专门服务于「流式可靠性」与「可回放」：
 * <ul>
 *   <li>{@code seqNo}，会话内单调递增序号，配合 {@code uk_msg_conv_seq}
 *       唯一索引，从根上保证多轮对话不会乱序，也不会有两条消息抢同一个位置；</li>
 *   <li>{@code reasoningContent}，思考过程与答案<b>分开存</b>。
 *       合在一起会导致「用户追问时把思考文本也当成上下文」以及「无法只回放答案」；</li>
 *   <li>{@code turnId}，客户端轮次幂等键，重复提交时用它找回同一个 messageId，
 *       直接重放流而不是重新生成（既省 token 又保证语义一致）；</li>
 *   <li>{@code firstTokenMs}，首字延迟，衡量流式体验的核心指标。</li>
 * </ul>
 */
@Data
@TableName("agent_message")
public class AgentMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;
    private Integer seqNo;
    private AgentEnums.Role role;
    private String content;
    private String reasoningContent;
    private String toolCallsJson;
    /** 本轮 RAG 引用快照（JSON 数组），刷新页面后仍能展示来源。 */
    private String referencesJson;
    private String toolCallId;
    private String toolName;
    private String turnId;
    private String model;
    private Integer tokensIn;
    private Integer tokensOut;
    private Long latencyMs;
    private Long firstTokenMs;
    private AgentEnums.MessageStatus status;
    private String blockReason;
    private String traceId;
    private LocalDateTime createTime;
}
