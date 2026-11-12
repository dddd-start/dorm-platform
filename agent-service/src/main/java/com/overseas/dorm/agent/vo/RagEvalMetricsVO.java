package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * RAG 召回评测指标。
 *
 * <p>比率用 {@link BigDecimal}（0–1 之间的小数）而不是 {@code "77.5%"} 字符串：
 * 字符串把数值与展示混在一起，前端要比较还得解析。百分比符号由前端决定。
 * 同时保留 {@code hitAt} 的原始命中数，便于核对分母。
 */
public record RagEvalMetricsVO(
        int totalQuestions,
        int hitAt1,
        int hitAt3,
        int hitAt5,
        BigDecimal recallAt1,
        BigDecimal recallAt3,
        BigDecimal recallAt5,
        BigDecimal mrr,
        int keywordHitCount,
        BigDecimal keywordHitRate,
        int noHitCount,
        BigDecimal noHitRate,
        BigDecimal avgTopScore,
        double avgLatencyMs
) implements Serializable {
}
