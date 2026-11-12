package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 入住订单状态。
 *
 * <p>订单在「审批通过并完成分房」时生成，之后贯穿真实入住与退房：
 * <pre>
 *   RESERVED ──check-in──▶ CHECKED_IN ──check-out──▶ CHECKED_OUT
 *       │
 *       └──cancel──▶ CANCELLED
 * </pre>
 */
@Getter
public enum OrderStatus {

    RESERVED("已预订", "#409EFF"),
    CHECKED_IN("已入住", "#67C23A"),
    CHECKED_OUT("已退房", "#909399"),
    CANCELLED("已取消", "#F56C6C");

    private final String label;
    private final String color;

    OrderStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public boolean isFinal() {
        return this == CHECKED_OUT || this == CANCELLED;
    }

    /** 状态字典（值 + 中文标签 + 展示色）。 */
    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values())
                .map(e -> com.overseas.dorm.common.api.OptionVO.of(e.name(), e.label, e.color))
                .toList();
    }
}
