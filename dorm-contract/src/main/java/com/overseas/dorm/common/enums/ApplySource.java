package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 申请来源。用于区分「网页自助申请」与「通过 AI 助手代提交」，
 * 便于运营分析 Agent 的实际转化率，也是审计取证的重要维度。
 */
@Getter
public enum ApplySource {

    WEB("网页端"),
    AGENT("AI 助手");

    private final String label;

    ApplySource(String label) {
        this.label = label;
    }

    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values())
                .map(e -> com.overseas.dorm.common.api.OptionVO.ofEnum(e, e.label))
                .toList();
    }
}
