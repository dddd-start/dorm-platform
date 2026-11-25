package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 知识库装载报告。
 *
 * <p>为什么要把"新增/更新/跳过"分开统计：如果只报"装载了 N 篇"，
 * 运维无法判断这次启动到底有没有真的重建索引，* 而"改了文档但索引没更新"是 RAG 最常见的静默故障。
 */
public record KnowledgeLoadReportVO(
        int documentCount,
        int created,
        int updated,
        int unchanged,
        int chunkCount,
        int indexSize,
        List<String> details
) implements Serializable {

    /** 尚未装载过时的初始值（避免接口返回 null 让前端渲染报错）。 */
    public static KnowledgeLoadReportVO empty() {
        return new KnowledgeLoadReportVO(0, 0, 0, 0, 0, 0, List.of());
    }
}
