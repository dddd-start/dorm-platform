package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentUsageDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * AI 用量日聚合数据访问。
 *
 * <p>累加用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 的原子语义，
 * 不用"先查后更新"——并发下后者会丢增量，而用量是计费依据。
 */
@Mapper
public interface AgentUsageDailyMapper extends BaseMapper<AgentUsageDaily> {

    /**
     * 累加当日用量。
     *
     * <p>用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查后更新」：
     * 高并发下同一个用户可能同时有多个流在跑，先查后更新会丢增量。
     * 依赖 {@code uk_usage_date_user} 唯一索引保证只有一行。
     */
    @Insert("""
            INSERT INTO agent_usage_daily
              (id, stat_date, user_id, conversation_count, turn_count, prompt_tokens,
               completion_tokens, total_tokens, tool_call_count, rag_hit_count,
               blocked_count, cache_hit_count, est_cost)
            VALUES
              (#{id}, #{statDate}, #{userId}, #{conversationDelta}, 1, #{promptTokens},
               #{completionTokens}, #{totalTokens}, #{toolCallDelta}, #{ragHitDelta},
               #{blockedDelta}, #{cacheHitDelta}, #{estCost})
            ON DUPLICATE KEY UPDATE
               conversation_count = conversation_count + VALUES(conversation_count),
               turn_count         = turn_count + 1,
               prompt_tokens      = prompt_tokens + VALUES(prompt_tokens),
               completion_tokens  = completion_tokens + VALUES(completion_tokens),
               total_tokens       = total_tokens + VALUES(total_tokens),
               tool_call_count    = tool_call_count + VALUES(tool_call_count),
               rag_hit_count      = rag_hit_count + VALUES(rag_hit_count),
               blocked_count      = blocked_count + VALUES(blocked_count),
               cache_hit_count    = cache_hit_count + VALUES(cache_hit_count),
               est_cost           = est_cost + VALUES(est_cost)
            """)
    int accumulate(@Param("id") Long id,
                   @Param("statDate") LocalDate statDate,
                   @Param("userId") Long userId,
                   @Param("conversationDelta") int conversationDelta,
                   @Param("promptTokens") long promptTokens,
                   @Param("completionTokens") long completionTokens,
                   @Param("totalTokens") long totalTokens,
                   @Param("toolCallDelta") int toolCallDelta,
                   @Param("ragHitDelta") int ragHitDelta,
                   @Param("blockedDelta") int blockedDelta,
                   @Param("cacheHitDelta") int cacheHitDelta,
                   @Param("estCost") BigDecimal estCost);

    /** 某用户当日已消耗的 token（预算守卫用）。 */
    @Select("""
            SELECT COALESCE(total_tokens, 0) FROM agent_usage_daily
             WHERE stat_date = #{statDate} AND user_id = #{userId}
            """)
    Long selectDailyTokens(@Param("statDate") LocalDate statDate, @Param("userId") Long userId);

    /** 全平台用量汇总（管理看板）。 */
    @Select("""
            SELECT stat_date,
                   SUM(turn_count) AS turns,
                   SUM(total_tokens) AS tokens,
                   SUM(tool_call_count) AS tool_calls,
                   SUM(est_cost) AS cost
              FROM agent_usage_daily
             WHERE stat_date >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
             GROUP BY stat_date
             ORDER BY stat_date
            """)
    List<com.overseas.dorm.domain.vo.UsageTrendVO> dailySummary(@Param("days") int days);
}
