package com.overseas.dorm.agent.vo;

import java.io.Serializable;

/**
 * 助手的能力边界（前端展示，也便于运维核对实际生效的配置）。
 *
 * <p>把这些数字下发给前端而不是让前端各写一份：限额调整后
 * 界面提示会自动跟着变，不会出现"配置改了、提示还写着旧数字"。
 */
public record AgentLimitsVO(
        int turnsPerMinute,
        int maxStreamsPerUser,
        int maxMessageLength,
        long dailyTokenBudget,
        int turnTimeoutSeconds
) implements Serializable {
}
