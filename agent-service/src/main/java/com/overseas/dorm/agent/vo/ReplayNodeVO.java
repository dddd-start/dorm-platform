package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 会话回放的一个节点（一轮对话）。
 */
public record ReplayNodeVO(
        Integer seq,
        String role,
        String roleLabel,
        String content,
        String thinking,
        String status,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        Long latencyMs,
        Long firstTokenMs,
        String time,
        List<ToolCallVO> toolCalls
) implements Serializable {
}
