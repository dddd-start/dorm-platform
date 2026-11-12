package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 床位状态。
 */
@Getter
public enum BedStatus {

    IDLE("空闲", "#67C23A"),
    OCCUPIED("已占用", "#F56C6C"),
    DISABLED("已停用", "#909399");

    private final String label;
    private final String color;

    BedStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    /** 状态字典（值 + 中文标签 + 展示色）。 */
    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values())
                .map(e -> com.overseas.dorm.common.api.OptionVO.of(e.name(), e.label, e.color))
                .toList();
    }
}
