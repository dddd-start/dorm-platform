package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 知识文档分类。
 */
@Getter
public enum DocCategory {

    POLICY("政策制度"),
    RULE("管理规定"),
    REGION_GUIDE("属地须知"),
    FAQ("常见问题");

    private final String label;

    DocCategory(String label) {
        this.label = label;
    }

    /** 字典（值 + 中文标签）。 */
    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values()).map(e -> com.overseas.dorm.common.api.OptionVO.ofEnum(e, e.label)).toList();
    }
}
