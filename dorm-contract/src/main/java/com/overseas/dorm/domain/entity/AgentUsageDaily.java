package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 用量与成本日聚合。
 *
 * <p>「生产中如何控制令牌消耗和模型成本」的度量基础：
 * 有了按用户按天的真实用量，才能做预算告警、超额降级、
 * 以及「哪个部门的 AI 成本最高」这类运营分析。
 */
@Data
@TableName("agent_usage_daily")
public class AgentUsageDaily implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private LocalDate statDate;
    private Long userId;
    private Integer conversationCount;
    private Integer turnCount;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Integer toolCallCount;
    private Integer ragHitCount;
    private Integer blockedCount;
    private Integer cacheHitCount;
    private BigDecimal estCost;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
