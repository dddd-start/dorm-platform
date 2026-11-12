package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 会话消息（前端渲染历史用）。
 */
public record AgentMessageVO(
        Long id,
        Integer seqNo,
        String role,
        String content,
        String thinking,
        List<ToolCallVO> toolCalls,
        List<Map<String, Object>> references,
        String status,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        Long latencyMs,
        Long firstTokenMs,
        String blockReason,
        String time,
        String turnId
) implements Serializable {
}
