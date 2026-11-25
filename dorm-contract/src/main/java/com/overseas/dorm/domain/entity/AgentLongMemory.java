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
 * Agent 长期记忆。
 *
 * <p>短期记忆（最近若干轮对话）放在 {@code agent_message} 里按窗口读取；
 * 长期记忆是「跨会话仍然有效的信息」，例如：
 * <ul>
 *   <li>PROFILE，常驻城市、所属部门；</li>
 *   <li>PREFERENCE，偏好房型、偏好公寓、是否介意合住（key 唯一，可覆盖更新）；</li>
 *   <li>SUMMARY，某次会话的结论摘要，供下次直接引用。</li>
 * </ul>
 * 设计上刻意把「画像」与「对话记录」分开：画像可被更新、可被用户清除，
 * 而对话记录是不可变的事实流水。
 */
@Data
@TableName("agent_long_memory")
public class AgentLongMemory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private AgentEnums.MemoryType memoryType;
    private String memoryKey;
    private String content;
    private Integer importance;
    private Long sourceConversationId;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
