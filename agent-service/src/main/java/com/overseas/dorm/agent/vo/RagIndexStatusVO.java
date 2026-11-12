package com.overseas.dorm.agent.vo;

import java.io.Serializable;

/** 知识库索引状态与检索参数（管理端）。 */
public record RagIndexStatusVO(
        int indexSize,
        long indexVersion,
        boolean mmrEnabled,
        double mmrLambda,
        int candidateMultiplier,
        int topK,
        double minScore,
        KnowledgeLoadReportVO lastLoadReport
) implements Serializable {
}
