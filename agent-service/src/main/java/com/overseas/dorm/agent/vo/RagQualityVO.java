package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * RAG 检索质量指标（生产侧持续追踪）。
 *
 * <p>原先字段名混用下划线（{@code avg_top_score}）与驼峰（{@code noHitRate}）：
 * 同一个响应里两种风格，是前端最容易写错、也最难肉眼发现的不一致
 * （少一个下划线就拿到 undefined）。现在统一为驼峰。
 */
public record RagQualityVO(
        int periodDays,
        long totalRetrievals,
        long noHitCount,
        long cacheHitCount,
        BigDecimal noHitRate,
        BigDecimal cacheHitRate,
        BigDecimal avgTopScore,
        Long avgLatencyMs,
        long likeCount,
        long dislikeCount,
        List<MissedQueryItem> topMissedQueries
) implements Serializable {

    /** 一条"一次都没检索到"的问题。 */
    public record MissedQueryItem(String query, long count) {
    }
}
