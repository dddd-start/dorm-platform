package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RAG 检索日志 —— 「如何追踪 RAG 生产实际效果」的数据来源。
 *
 * <p>每次检索都记录：原始问题、TopK、命中数与最高分、命中的切片 ID、
 * 耗时、是否缓存命中、答案是否引用了检索结果、用户反馈。
 * 有了这些，才能回答三个运营问题：
 * <ol>
 *   <li>有多少问题其实「一条都没命中」——说明知识库有缺口；</li>
 *   <li>命中但用户点了「没用」——说明召回对了但答案组织得不好；</li>
 *   <li>相似问题的分数分布如何 —— 用于校准 minScore 阈值。</li>
 * </ol>
 */
@Data
@TableName("rag_retrieval_log")
public class RagRetrievalLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;
    private Long conversationId;
    private Long messageId;
    private Long userId;
    private String queryText;
    private Integer topK;
    private Integer hitCount;
    private BigDecimal topScore;
    private String hitChunkIds;
    private String scores;
    private Long latencyMs;
    private Integer cacheHit;
    private Integer answerUsed;
    /** 该次检索对应的用户反馈。 */
    private com.overseas.dorm.common.enums.FeedbackRating feedback;
    private LocalDateTime createTime;
}
