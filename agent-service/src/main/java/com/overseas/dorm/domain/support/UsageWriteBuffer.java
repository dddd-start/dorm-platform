package com.overseas.dorm.domain.support;

import com.overseas.dorm.domain.mapper.AgentUsageDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 用量聚合的写后置缓冲（write-behind）。
 *
 * <p>本机存储约 26 次 fsync/秒，而 MySQL 为完全持久化配置
 * （{@code innodb_flush_log_at_trx_commit=1} + {@code sync_binlog=1}），
 * 每次事务提交要 2 次 fsync。改造前每轮对话 6~7 次提交，高并发下 fsync 数量物理上不可行。
 *
 * <pre>
 *   每轮结束 → HINCRBY Redis Hash (dorm:usage:buf:{date}:{userId})
 *            → 每 5 秒 / 退出前批量 UPSERT 到 agent_usage_daily
 * </pre>
 * 提交数从"每轮一次"降为"每刷新周期每用户一次"。
 *
 * <p>用量是纯聚合数据，只被看板读取，不在请求关键路径上，延迟落库不影响正确性。
 * 作为对比，消息会被下一轮对话读取（上下文重建），必须同步可见，不能同样后置。
 * 缓冲放在 Redis 且开启 AOF，进程被 OOM Kill 后缓冲仍在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageWriteBuffer {

    private static final String KEY_PREFIX = "dorm:usage:buf:";
    /** 缓冲保留 3 天：即使刷新长时间失败，也不会丢数据。 */
    private static final Duration BUFFER_TTL = Duration.ofDays(3);

    private static final String F_TURNS = "turns";
    private static final String F_CONV = "conv";
    private static final String F_PROMPT = "prompt";
    private static final String F_COMPLETION = "completion";
    private static final String F_TOOLS = "tools";
    private static final String F_RAG = "rag";
    private static final String F_BLOCKED = "blocked";
    private static final String F_CACHE = "cache";

    /** 单次刷新最多处理多少个用户的缓冲（防止一次刷新过久）。 */
    private static final int MAX_KEYS_PER_FLUSH = 500;

    /**
     * 原子地"读出全部字段并删除键"。
     *
     * <p>为什么必须是 Lua：先 HGETALL 再 DEL 会丢掉两步之间新累加的增量；
     * 先 DEL 再读就没有值可读。只有在 Redis 单线程内一次执行才安全。
     * 返回值是扁平的 {@code [field, value, field, value...]}。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> READ_AND_DELETE = new DefaultRedisScript<>("""
            local vals = redis.call('HGETALL', KEYS[1])
            if #vals == 0 then return {} end
            redis.call('DEL', KEYS[1])
            return vals
            """, List.class);

    private final StringRedisTemplate redis;
    private final AgentUsageDailyMapper usageMapper;

    /** 记一次用量：纯 Redis 操作，不触碰数据库、不产生任何 fsync。 */
    public void accumulate(Long userId, int promptTokens, int completionTokens,
                           int toolCalls, int ragHits, boolean blocked, boolean newConversation) {
        if (userId == null) {
            return;
        }
        String key = KEY_PREFIX + LocalDate.now() + ":" + userId;
        try {
            redis.opsForHash().increment(key, F_TURNS, 1L);
            if (newConversation) {
                redis.opsForHash().increment(key, F_CONV, 1L);
            }
            if (promptTokens != 0) {
                redis.opsForHash().increment(key, F_PROMPT, promptTokens);
            }
            if (completionTokens != 0) {
                redis.opsForHash().increment(key, F_COMPLETION, completionTokens);
            }
            if (toolCalls != 0) {
                redis.opsForHash().increment(key, F_TOOLS, toolCalls);
            }
            if (ragHits != 0) {
                redis.opsForHash().increment(key, F_RAG, ragHits);
            }
            if (blocked) {
                redis.opsForHash().increment(key, F_BLOCKED, 1L);
            }
            redis.expire(key, BUFFER_TTL);
        } catch (Exception e) {
            // 缓冲失败不能影响对话本身：这轮用量会少计（可接受的降级），但必须可见
            log.warn("[用量缓冲] 写入失败，本轮用量将少计 userId={}", userId, e);
        }
    }

    /** 周期性批量落库。 */
    @Scheduled(fixedDelayString = "${dorm.agent.usage.flush-interval-ms:5000}")
    public void flush() {
        int flushed = flushOnce();
        if (flushed > 0) {
            log.debug("[用量缓冲] 落库 {} 个用户的用量", flushed);
        }
    }

    /** 优雅停机兜底：把剩余缓冲落库，避免重启丢掉最后几秒的用量。 */
    @jakarta.annotation.PreDestroy
    public void flushOnShutdown() {
        int total = 0;
        for (int i = 0; i < 20; i++) {
            int n = flushOnce();
            total += n;
            if (n == 0) {
                break;
            }
        }
        if (total > 0) {
            log.info("[用量缓冲] 退出前落库 {} 个用户的用量", total);
        }
    }

    /** @return 本次落库的用户数 */
    int flushOnce() {
        Set<String> keys;
        try {
            // 用 SCAN 而不是 KEYS：KEYS 会阻塞整个 Redis 实例
            // （本项目在另一处统计上踩过这个坑，见 AgentStreamBuffer.subscriberCount 的注释）
            keys = redis.execute((org.springframework.data.redis.core.RedisCallback<Set<String>>) connection -> {
                Set<String> found = new java.util.LinkedHashSet<>();
                try (var cursor = connection.scan(
                        org.springframework.data.redis.core.ScanOptions.scanOptions()
                                .match(KEY_PREFIX + "*").count(500).build())) {
                    while (cursor.hasNext() && found.size() < MAX_KEYS_PER_FLUSH) {
                        found.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                return found;
            });
        } catch (Exception e) {
            log.warn("[用量缓冲] 扫描缓冲键失败", e);
            return 0;
        }
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String key : keys) {
            try {
                if (flushOne(key)) {
                    count++;
                }
            } catch (Exception e) {
                // 单键失败不影响其它键；缓冲仍在 Redis 中，下轮重试
                log.warn("[用量缓冲] 落库失败，将在下轮重试 key={}", key, e);
            }
        }
        return count;
    }

    private boolean flushOne(String key) {
        List<?> flat = redis.execute(READ_AND_DELETE, Collections.singletonList(key));
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        LocalDate date = parseDate(key);
        Long userId = parseUserId(key);
        if (date == null || userId == null) {
            log.warn("[用量缓冲] 键格式不符合预期，已丢弃：{}", key);
            return false;
        }
        java.util.Map<String, Long> values = new java.util.HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            try {
                values.put(String.valueOf(flat.get(i)), Long.parseLong(String.valueOf(flat.get(i + 1))));
            } catch (NumberFormatException ignored) {
                // 字段值不是数字则跳过该字段
            }
        }
        long prompt = value(values, F_PROMPT);
        long completion = value(values, F_COMPLETION);
        usageMapper.accumulate(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                date, userId,
                (int) value(values, F_CONV), (int) prompt, (int) completion, prompt + completion,
                (int) value(values, F_TOOLS), (int) value(values, F_RAG),
                (int) value(values, F_BLOCKED), (int) value(values, F_CACHE),
                BigDecimal.ZERO);
        return value(values, F_TURNS) > 0;
    }

    private static long value(java.util.Map<String, Long> values, String field) {
        Long v = values.get(field);
        return v == null ? 0L : v;
    }

    private static LocalDate parseDate(String key) {
        try {
            return LocalDate.parse(key.substring(KEY_PREFIX.length()).split(":")[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseUserId(String key) {
        try {
            return Long.parseLong(key.substring(KEY_PREFIX.length()).split(":")[1]);
        } catch (Exception e) {
            return null;
        }
    }

    /** 供运维查看：当前缓冲里的键数量（用于确认是否有积压）。 */
    public int bufferedKeyCount() {
        return redis.keys(KEY_PREFIX + "*").size();
    }
}
