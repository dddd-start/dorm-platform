package com.overseas.dorm.agent.vo;

import java.io.Serializable;

/**
 * 会话列表项。
 *
 * <p>替换原来的 {@code Map<String, Object>}：会话列表是前端每次打开助手页都要调的接口，
 * 字段名一旦被某个 {@code put} 改掉，前端只会拿到 undefined 而不会报错。
 * 用 record 把字段固定下来，接口契约就由编译器守着。
 */
public record ConversationVO(
        Long id,
        String conversationNo,
        String title,
        String agentType,
        String agentTypeLabel,
        Integer messageCount,
        String lastMessageTime,
        boolean hasSummary
) implements Serializable {
}
