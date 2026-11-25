package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.RagRetrievalLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * RAG 检索日志数据访问（召回质量与知识库缺口的唯一数据来源）。
 */
@Mapper
public interface RagRetrievalLogMapper extends BaseMapper<RagRetrievalLog> {

    /**
     * 生产效果看板的核心查询。
     *
     * <p>指标含义：
     * <ul>
     *   <li>{@code no_hit_rate}，「一条都没检索到」的比例，
     *       直接反映知识库覆盖缺口，是最该盯的指标；</li>
     *   <li>{@code avg_top_score}，最高相似度均值，用于校准 minScore；</li>
     *   <li>{@code dislike_rate}，命中了但用户不满意，说明是答案组织问题而非召回问题。</li>
     * </ul>
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN hit_count = 0 THEN 1 ELSE 0 END) AS no_hit,
                   SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END) AS cache_hits,
                   ROUND(AVG(top_score), 4) AS avg_top_score,
                   ROUND(AVG(latency_ms)) AS avg_latency_ms,
                   SUM(CASE WHEN feedback = 'DISLIKE' THEN 1 ELSE 0 END) AS dislikes,
                   SUM(CASE WHEN feedback = 'LIKE' THEN 1 ELSE 0 END) AS likes
              FROM rag_retrieval_log
             WHERE create_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
            """)
    com.overseas.dorm.domain.vo.RagQualityMetricsVO qualityMetrics(@Param("days") int days);

    /** 未命中问题榜：知识库建设的最直接输入。 */
    @Select("""
            SELECT query_text, COUNT(*) AS cnt
              FROM rag_retrieval_log
             WHERE hit_count = 0 AND create_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
             GROUP BY query_text
             ORDER BY cnt DESC
             LIMIT #{limit}
            """)
    List<com.overseas.dorm.domain.vo.MissedQueryVO> topMissedQueries(@Param("days") int days,
                                                                    @Param("limit") int limit);

    /**
     * 回写用户反馈。
     *
     * <p>参数用枚举而不是字符串：{@code FeedbackRating} 与 {@code agent_feedback.rating}
     * 共用同一个枚举，避免"检索日志里写 GOOD、反馈表里写 LIKE"这类统计口径分裂
     * （分裂的后果是报表少算一半数据，而每条记录看起来都正常）。
     */
    @Update("UPDATE rag_retrieval_log SET feedback = #{feedback} WHERE id = #{id}")
    int updateFeedback(@Param("id") Long id,
                       @Param("feedback") com.overseas.dorm.common.enums.FeedbackRating feedback);

    /**
     * 按消息回写反馈，质量闭环的关键一环。
     *
     * <p>为什么必须有这个方法：运维看板的"点踩率"读的是
     * {@code rag_retrieval_log.feedback}，而用户的点赞/点踩此前只写进
     * {@code agent_feedback}，两张表之间没有打通，结果是
     * <b>点踩率永远是 0</b>，"哪个问题答得不好"这条最重要的运营线索彻底丢失。
     * 一条检索可能对应多条日志（同一轮多次检索），因此按 message_id 全量更新。
     */
    @Update("UPDATE rag_retrieval_log SET feedback = #{feedback} WHERE message_id = #{messageId}")
    int updateFeedbackByMessage(@Param("messageId") Long messageId,
                                @Param("feedback") com.overseas.dorm.common.enums.FeedbackRating feedback);
}
