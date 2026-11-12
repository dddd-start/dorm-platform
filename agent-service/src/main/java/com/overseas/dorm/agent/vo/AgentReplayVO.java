package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.List;

/** 会话回放时间线。 */
public record AgentReplayVO(
        Long conversationId,
        Long messageId,
        int nodeCount,
        List<ReplayNodeVO> nodes
) implements Serializable {
}
