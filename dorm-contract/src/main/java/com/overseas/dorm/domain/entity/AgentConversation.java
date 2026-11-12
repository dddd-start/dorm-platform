package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.overseas.dorm.common.enums.AgentType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话。
 *
 * <p>{@code summary} 是长期记忆的载体：当会话轮次超过阈值时，
 * 把早期对话压缩成摘要写回这里，从而在不丢失上下文的前提下控制 token 消耗。
 */
@Data
@TableName("agent_conversation")
public class AgentConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String conversationNo;
    private Long userId;
    private String title;
    /** 当前主导的专家 Agent。 */
    private AgentType agentType;
    private Integer messageCount;
    private LocalDateTime lastMessageTime;
    /** 会话状态。枚举而非字符串：状态名写错时编译期即可发现，不会被静默写进库。 */
    private com.overseas.dorm.common.enums.ConversationStatus status;
    private String summary;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
