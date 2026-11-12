package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户对 AI 回答的反馈（点赞/点踩 + 原因）。
 *
 * <p>{@code uk_feedback_msg_user} 保证一个用户对一条消息只有一份反馈，
 * 重复点击是「修改」而不是「再记一条」，否则统计口径会被刷出来的
 * 重复数据污染。
 */
@Data
@TableName("agent_feedback")
public class AgentFeedback implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;
    private Long messageId;
    private Long userId;
    /** LIKE / DISLIKE。 */
    /** 评价结果（LIKE / DISLIKE）。 */
    private com.overseas.dorm.common.enums.FeedbackRating rating;
    private String reason;
    private LocalDateTime createTime;
}
