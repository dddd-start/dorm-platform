package com.overseas.dorm.agent.vo;

import java.io.Serializable;

/**
 * 模型健康状态。
 *
 * <p>这是"回答质量突然变差"时第一个要看的接口：如果主模型被熔断、
 * 请求正在由降级链里的小模型应答，用户是察觉不到的，只有看这里才能发现。
 */
public record ModelHealthVO(
        String role,
        String model,
        boolean available,
        boolean breakerOpen,
        long breakerRemainingSeconds,
        long successCount,
        long failureCount,
        int consecutiveFailures
) implements Serializable {
}
