package com.overseas.dorm.common.enums;

import lombok.Getter;

/**
 * 知识库文档状态。
 *
 * <p>只有 {@link #PUBLISHED} 的文档会被向量化并参与检索，* 这条规则同时出现在索引装载与检索 SQL 里，用枚举可以避免两边写成不同的字符串。
 */
@Getter
public enum DocumentStatus {

    /** 草稿：不建索引、不参与检索。 */
    DRAFT("草稿"),
    /** 已发布：参与索引与检索。 */
    PUBLISHED("已发布"),
    /** 已下线：索引保留但不再参与检索（便于随时恢复）。 */
    OFFLINE("已下线");

    private final String label;

    DocumentStatus(String label) {
        this.label = label;
    }

}
