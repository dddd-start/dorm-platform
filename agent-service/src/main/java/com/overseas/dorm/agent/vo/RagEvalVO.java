package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.List;

/** RAG 评测报告（含逐题明细与 Markdown 版本，供直接归档）。 */
public record RagEvalVO(
        int indexSize,
        RagEvalMetricsVO metrics,
        List<ItemResultVO> missed,
        List<ItemResultVO> details,
        String markdown
) implements Serializable {

    /** 单题评测结果。 */
    public record ItemResultVO(
            String id,
            String question,
            String expectedDocCode,
            String topDocCode,
            Integer rank,
            Double topScore,
            Long latencyMs,
            Boolean keywordHit,
            String category
    ) implements Serializable {
    }
}
