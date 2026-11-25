package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * RAG 检索质量的聚合指标（SQL 单行聚合结果）。
 *
 * <p>字段名由原来的下划线别名（{@code avg_top_score}）统一为驼峰：
 * 同一个响应里混用 {@code avg_top_score} 与 {@code noHitRate} 两种风格，
 * 是前端最容易写错、也最难靠肉眼发现的一类不一致（少一个下划线就拿到 undefined）。
 */
@lombok.Data
public class RagQualityMetricsVO implements Serializable {

    /** 检索总次数。 */
    private Long total;

    /** 一条都没命中的次数，知识库缺口的直接指标。 */
    private Long noHit;

    /** 命中缓存次数。 */
    private Long cacheHits;

    /** 平均最高相似度，用于校准 minScore 阈值。 */
    private Double avgTopScore;

    /** 平均检索耗时（毫秒）。 */
    private Long avgLatencyMs;

    /** 点踩数。 */
    private Long dislikes;

    /** 点赞数。 */
    private Long likes;
}
