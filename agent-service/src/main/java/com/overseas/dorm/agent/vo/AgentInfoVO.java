package com.overseas.dorm.agent.vo;

import java.io.Serializable;

/** 一个专家 Agent 的描述（前端展示"助手能做哪些事"）。 */
public record AgentInfoVO(
        String type,
        String label,
        String description,
        String sample
) implements Serializable {
}
