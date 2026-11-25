package com.overseas.dorm.domain.support;

import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.util.DateUtil;
import com.overseas.dorm.domain.mapper.BizNoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务单号生成器。
 *
 * <p>格式：{@code 前缀 + yyyyMMdd + 4 位日内序号}，例如 {@code DA202610140001}。
 * 为什么不用 UUID 或纯雪花 ID 作为对客单号：
 * <ul>
 *   <li>客服与员工要口头/工单里报单号，可读性直接影响沟通效率；</li>
 *   <li>带日期的单号天然可排序、可按天分片；</li>
 *   <li>4 位日内序号（每天最多 9999 单）对本系统量级足够，
 *       溢出会立刻抛异常而不是悄悄错乱。</li>
 * </ul>
 *
 * 最初实现是「Redis INCR 取号 → 拼单号 → 插入」。压测时用独立 Redis db
 * 启动了一个实例，INCR 从 1 开始，拼出的 {@code AC202610140001} 与库里
 * 已存在的会话号撞上唯一索引，<b>所有"创建会话"的请求立刻全部失败</b>。
 *
 * <p>这暴露了一个很容易被忽视的风险：Redis 不是可靠存储，* 重启未持久化、key 被 LRU 淘汰（本项目配置了 maxmemory-policy allkeys-lru）、
 * 运维手工 flush、切库，都会让计数器归零。而一旦归零，业务会出现
 * "突然什么都提交不了"这种最严重级别的故障。
 *
 * <ol>
 *   <li><b>播种</b>：Redis 计数器不存在（或刚被重建）时，
 *       用数据库当天最大序号播种，而不是从 1 开始；</li>
 *   <li><b>校验</b>：拼出单号后先查库确认不存在，冲突则继续取下一个号；</li>
 *   <li><b>兜底</b>：数据库唯一索引仍在，作为最后一道防线。</li>
 * </ol>
 */
@Slf4j
@Component
public class BizNoGenerator {

    public static final String TYPE_APPLY = "DA";
    public static final String TYPE_ORDER = "DO";
    public static final String TYPE_CONVERSATION = "AC";
    public static final String TYPE_TASK = "AT";
    public static final String TYPE_APPROVAL = "AP";

    /** 单号冲突时的最大重试次数。 */
    private static final int MAX_ATTEMPT = 30;
    /**
     * 日内序号上限。
     *
     * <p>原为 4 位（每天 9999 单）。本项目的千级并发压测在一天内创建了 1.1 万个会话，
     * 直接把当日序号顶到上限，于是所有新会话创建失败（生成器抛"当日单号已用尽"）。
     * 对一个有数千员工的企业，每天上万次对话并非异常量级，
     * 因此位数必须留出余量：6 位 = 每天 999999 单。
     */
    private static final int MAX_SEQ = 999_999;

    /** 日语序号的位数（与 {@link #MAX_SEQ} 对应）。 */
    private static final int SEQ_WIDTH = 6;

    private final StringRedisTemplate stringRedisTemplate;
    private final BizNoMapper bizNoMapper;

    public BizNoGenerator(StringRedisTemplate stringRedisTemplate, BizNoMapper bizNoMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.bizNoMapper = bizNoMapper;
    }

    /** 生成带前缀的业务单号。 */
    public String next(String type) {
        String date = DateUtil.today().format(DateUtil.COMPACT_DATE);
        String key = RedisKeys.bizSeq(type, date);
        for (int attempt = 1; attempt <= MAX_ATTEMPT; attempt++) {
            long seq = nextSequence(key, type, date, attempt);
            if (seq > MAX_SEQ) {
                throw new IllegalStateException("当日单号已用尽（上限 %d）：%s %s"
                        .formatted(MAX_SEQ, type, date));
            }
            // 注意括号：格式串必须先拼好再 formatted，否则 .formatted 只作用在字面量 "d" 上，
            // 单号会变成 "%s%s%06d" 这个字面量（本项目刚踩过，且因为所有单号相同
            // 会立刻撞唯一索引，症状看起来像"单号生成器坏了"）
            String no = ("%s%s%0" + SEQ_WIDTH + "d").formatted(type, date, seq);
            // 校验：单号可能因 Redis 归零而与库中历史数据冲突
            if (!exists(type, no)) {
                return no;
            }
            // 【关键修复】冲突时不能只"取下一个号"——那只能处理"库里零散占用"的情况。
            // 如果 Redis 计数器整体落后于数据库（典型场景：另一个实例用了独立 Redis db，
            // 分配了大量单号；或本实例的 Redis 被清空后重新播种到了一个偏小的值），
            // 那么接下来的 30 个号全都已被占用，逐个重试必然全部失败，
            // 结果是"创建会话直接 500"。本项目在千级并发压测后真实触发过这个问题。
            // 正确做法：把计数器一次性对齐到库内当天最大值，然后重试。
            long dbMax = maxSequenceFromDb(type, type + date);
            if (dbMax >= seq) {
                log.warn("[单号] {} 冲突且计数器（seq={}）落后于库内最大值（{}），"
                        + "已将计数器对齐并重试", no, seq, dbMax);
                stringRedisTemplate.opsForValue()
                        .set(key, String.valueOf(dbMax + 1), Duration.ofDays(2));
                continue;
            }
            log.warn("[单号] {} 已存在（被历史数据零散占用），继续取下一个号", no);
        }
        throw new IllegalStateException("业务单号生成失败：连续 %d 次冲突，请检查 Redis 与数据库状态"
                .formatted(MAX_ATTEMPT));
    }

    /**
     * 取下一个序号。
     *
     * <p>关键点：区分「首次创建」与「正常自增」。
     * 首次创建时必须用数据库当天的最大值播种，否则会从 1 开始而与历史冲突。
     * 这里用 {@code setIfAbsent} 的返回值判断"是不是我创建的"，
     * 保证并发下只有一个人做播种动作。
     */
    private long nextSequence(String key, String type, String date, int attempt) {
        if (attempt == 1) {
            String prefix = type + date;
            Boolean created = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(maxSequenceFromDb(type, prefix)),
                            Duration.ofDays(2));
            if (Boolean.FALSE.equals(created) && stringRedisTemplate.opsForValue().get(key) == null) {
                // 极端竞态：key 恰好在这一瞬间过期，重新播种
                stringRedisTemplate.opsForValue().set(key,
                        String.valueOf(maxSequenceFromDb(type, prefix)), Duration.ofDays(2));
            }
        }
        Long seq = stringRedisTemplate.opsForValue().increment(key);
        if (seq == null) {
            throw new IllegalStateException("业务单号生成失败：Redis INCR 返回空");
        }
        if (seq == 1L) {
            // 计数器刚被创建（或被重置）后第一次自增：立刻对齐到库内最大值，
            // 避免在"播种值恰好为 0"的情况下继续从 1 递增
            long dbMax = maxSequenceFromDb(type, type + date);
            if (dbMax > 0) {
                stringRedisTemplate.opsForValue().set(key, String.valueOf(dbMax + 1),
                        Duration.ofDays(2));
                return dbMax + 1;
            }
        }
        if (seq == 1L || attempt == 1) {
            // 续期：single key 的 TTL 只需要覆盖当天即可，这里统一给 2 天
            stringRedisTemplate.expire(key, Duration.ofDays(2));
        }
        return seq;
    }

    /** 从数据库取当天该类型的最大序号（Redis 丢失时的播种依据）。 */
    private long maxSequenceFromDb(String type, String prefix) {
        try {
            int prefixLen = prefix.length();
            return switch (type) {
                case TYPE_APPLY -> bizNoMapper.maxApplySeq(prefix, prefixLen);
                case TYPE_ORDER -> bizNoMapper.maxOrderSeq(prefix, prefixLen);
                case TYPE_CONVERSATION -> bizNoMapper.maxConversationSeq(prefix, prefixLen);
                case TYPE_TASK -> bizNoMapper.maxTaskSeq(prefix, prefixLen);
                case TYPE_APPROVAL -> bizNoMapper.maxApprovalSeq(prefix, prefixLen);
                default -> 0L;
            };
        } catch (Exception e) {
            log.warn("[单号] 播种查询失败（将退化为从 Redis 当前值继续）：{}", e.getMessage());
            return 0L;
        }
    }

    /** 检查单号是否已存在。 */
    private boolean exists(String type, String no) {
        try {
            int count = switch (type) {
                case TYPE_APPLY -> bizNoMapper.countApplyNo(no);
                case TYPE_ORDER -> bizNoMapper.countOrderNo(no);
                case TYPE_CONVERSATION -> bizNoMapper.countConversationNo(no);
                case TYPE_TASK -> bizNoMapper.countTaskNo(no);
                case TYPE_APPROVAL -> bizNoMapper.countApprovalNo(no);
                default -> 0;
            };
            return count > 0;
        } catch (Exception e) {
            // 查询失败时按"不存在"处理，由数据库唯一索引兜底
            log.warn("[单号] 唯一性校验失败，交由唯一索引兜底：{}", e.getMessage());
            return false;
        }
    }
}
