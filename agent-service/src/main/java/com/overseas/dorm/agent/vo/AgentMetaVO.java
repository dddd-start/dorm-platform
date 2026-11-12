package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.List;

/** 助手元信息：启用了哪些专家、当前模型、能力边界。 */
public record AgentMetaVO(
        boolean enabled,
        String primaryModel,
        boolean mockMode,
        boolean ragEnabled,
        List<AgentInfoVO> agents,
        List<AgentInfoVO> adminAgents,
        AgentLimitsVO limits
) implements Serializable {
}
