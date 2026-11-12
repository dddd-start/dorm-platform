package com.overseas.dorm.common.enums;

import lombok.Getter;

/**
 * AI 会话状态。
 */
@Getter
public enum ConversationStatus {

    /** 进行中，会出现在会话列表里。 */
    ACTIVE("进行中"),
    /** 已归档（用户主动归档），仍可被审计查询，但不再出现在活跃列表。 */
    ARCHIVED("已归档");

    private final String label;

    ConversationStatus(String label) {
        this.label = label;
    }
}
