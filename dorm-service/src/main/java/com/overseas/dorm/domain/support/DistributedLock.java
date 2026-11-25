package com.overseas.dorm.domain.support;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis 分布式锁。
 *
 * <p>三个必须做对的细节（做错就会变成「看起来能用、高并发下偶发超卖」）：
 * <ol>
 *   <li><b>加锁用 SET NX PX</b>，而不是先 SETNX 再 EXPIRE，*       后者在两条命令之间宕机就会留下永不过期的死锁；</li>
 *   <li><b>解锁必须校验持有者</b>：用 Lua 脚本「比对 value 再删除」，
 *       否则 A 把锁超时释放后、B 拿到锁，A 的 finally 会把 B 的锁删掉；</li>
 *   <li><b>必须有兜底</b>：锁只是「减少冲突」，真正的正确性由数据库的
 *       条件更新（{@code WHERE status='IDLE'}）保证，*       分布式锁失效时业务仍然不能错，这是分层的意义。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLock {

    /** 仅当 value 匹配时才删除，保证「解铃还须系铃人」。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    /** 尝试加锁，失败返回 null。 */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    public void unlock(String key, String token) {
        if (key == null || token == null) {
            return;
        }
        try {
            redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("释放分布式锁失败 key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 在锁保护下执行逻辑；拿不到锁直接失败（快速失败优于排队堆积）。
     *
     * @param key        锁键
     * @param waitMillis 最大等待时长；期间轮询重试
     * @param ttl        锁自动过期时间（必须大于业务最长耗时，否则会提前释放）
     */
    public <T> T executeWithLock(String key, long waitMillis, Duration ttl, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + Math.max(waitMillis, 0);
        String token = tryLock(key, ttl);
        while (token == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw BizException.of(ResultCode.LOCK_ACQUIRE_FAILED, "操作被中断");
            }
            token = tryLock(key, ttl);
        }
        if (token == null) {
            throw BizException.of(ResultCode.LOCK_ACQUIRE_FAILED,
                    "当前有其他人正在处理同一资源，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            unlock(key, token);
        }
    }

    public void executeWithLock(String key, long waitMillis, Duration ttl, Runnable action) {
        executeWithLock(key, waitMillis, ttl, () -> {
            action.run();
            return null;
        });
    }
}
