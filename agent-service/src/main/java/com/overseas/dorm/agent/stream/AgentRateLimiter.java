package com.overseas.dorm.agent.stream;

import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Agent 限流与预算守卫。
 *
 * 固定窗口计数有个众所周知的边界问题：限制"每分钟 20 次"时，
 * 用户在第 59 秒打 20 次、第 61 秒再打 20 次，两秒内实际打了 40 次。
 * 令牌桶通过"持续补充令牌"平滑了这个问题，同时允许短时突发
 * （用户连续追问几句是正常行为，不该被当成攻击）。
 *
 * 令牌桶需要"读当前令牌数 → 计算补充 → 判断是否够 → 扣减 → 写回"这一串操作。
 * 分成多条 Redis 命令时，两个并发请求可能都读到"还有 1 个令牌"，
 * 于是双双放行，限流形同虚设。Lua 脚本在 Redis 中是原子执行的，
 * 天然解决竞态，而且省掉了分布式锁。
 *
 * token 预算超了就把用户彻底挡在外面，体验极差（"系统坏了？"）。
 * 更合理的做法是降级：改用便宜的小模型继续服务，回答质量下降但功能可用，
 * 同时在界面上给出提示。这和"主模型故障降级"是同一套思路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRateLimiter {

    private final StringRedisTemplate redis;
    private final AgentProperties properties;
    private final AgentAuditService auditService;

    /**
     * 令牌桶 Lua 脚本。
     *
     * <pre>
     *   KEYS[1] 桶键
     *   ARGV[1] 容量（最大令牌数）
     *   ARGV[2] 补充速率（令牌/秒）
     *   ARGV[3] 当前时间（秒，由应用传入以保证多实例一致的服务端时间基准）
     *   返回：剩余令牌数（&gt;=0 表示放行；&lt;0 表示被限流，值为需要等待的秒数）
     * </pre>
     */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts = tonumber(bucket[2])

            if tokens == nil then
                tokens = capacity
                ts = now
            end

            -- 按流逝时间补充令牌，上限为容量
            local delta = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + delta * rate)

            if tokens < 1 then
                local wait = math.ceil((1 - tokens) / rate)
                redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
                redis.call('EXPIRE', KEYS[1], 3600)
                return -wait
            end

            tokens = tokens - 1
            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', KEYS[1], 3600)
            return math.floor(tokens)
            """, Long.class);

    /**
     * 尝试获取一轮对话的许可。
     *
     * @return 允许则返回 null；被限流则返回给用户看的提示
     */
    public String tryAcquireTurn(Long userId) {
        AgentProperties.Ratelimit config = properties.getRatelimit();
        int perMinute = config.getTurnsPerMinute();
        if (perMinute <= 0) {
            return null;
        }
        double ratePerSecond = perMinute / 60.0;
        int capacity = Math.max(config.getBurst(), 1);
        try {
            Long result = redis.execute(TOKEN_BUCKET,
                    Collections.singletonList(RedisKeys.agentTurnBucket(userId)),
                    String.valueOf(capacity),
                    String.valueOf(ratePerSecond),
                    String.valueOf(System.currentTimeMillis() / 1000.0));
            if (result != null && result < 0) {
                long wait = -result;
                log.info("[限流] 用户 {} 触发对话限流，需等待约 {} 秒", userId, wait);
                return "提问有点频繁，请等 %d 秒后再试。你也可以先浏览「房源查询」页面自助查看。"
                        .formatted(wait);
            }
            return null;
        } catch (Exception e) {
            // 限流组件故障时放行：宁可偶尔超限，也不能让所有人用不了助手
            log.warn("[限流] 令牌桶执行失败，本次放行：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 预算检查。
     *
     * @return null 表示未超预算；否则返回建议的动作（DEGRADE / REJECT）与提示
     */
    public BudgetDecision checkBudget(Long userId) {
        long budget = properties.getRatelimit().getDailyTokenBudget();
        if (budget <= 0) {
            return BudgetDecision.allowed();
        }
        long used = auditService.dailyTokens(userId);
        if (used < budget) {
            return BudgetDecision.allowed();
        }
        boolean degrade = "DEGRADE".equalsIgnoreCase(properties.getRatelimit().getOverBudgetAction());
        log.info("[预算] 用户 {} 今日已用 {} tokens，超出预算 {}（策略：{}）", userId, used, budget,
                degrade ? "降级" : "拒绝");
        if (degrade) {
            return new BudgetDecision(true, false,
                    "今日智能助手额度已用完，已为你切换到精简模式，回答可能更简短。");
        }
        return new BudgetDecision(true, true,
                "今日智能助手额度已用完。常规的房源查询、申请与订单功能不受影响，明天额度会重置。");
    }

    /** 预算决策。 */
    public record BudgetDecision(boolean overBudget, boolean reject, String message) {

        public static BudgetDecision allowed() {
            return new BudgetDecision(false, false, null);
        }
    }
}
