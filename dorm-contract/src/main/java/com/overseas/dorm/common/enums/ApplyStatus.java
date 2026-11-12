package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 入住申请状态。
 *
 * <pre>
 *                    ┌──────────── reject ──────────┐
 *                    │                              ▼
 *   create ──▶ PENDING ──approve+assign──▶ APPROVED ──check-in──▶ CHECKED_IN ──check-out──▶ CHECKED_OUT
 *                 │                          │
 *                 └── cancel ──▶ CANCELLED ◀──┘
 *                                            │
 *                            (超过最晚入住日)  └──▶ EXPIRED
 * </pre>
 *
 * <p>状态流转的唯一权威是 {@code ApplyStateMachine}，
 * 任何绕过状态机的直接 UPDATE 都视为缺陷。
 */
@Getter
public enum ApplyStatus {

    /** 待审批。 */
    PENDING("待审批", "#E6A23C"),
    /** 已通过并已分配房间/床位。 */
    APPROVED("已通过", "#409EFF"),
    /** 已驳回。 */
    REJECTED("已驳回", "#F56C6C"),
    /** 已入住。 */
    CHECKED_IN("已入住", "#67C23A"),
    /** 已退房（终态）。 */
    CHECKED_OUT("已退房", "#909399"),
    /** 已取消（终态）。 */
    CANCELLED("已取消", "#909399"),
    /** 已失效：审批通过但超期未入住（终态）。 */
    EXPIRED("已失效", "#909399");

    private final String label;
    /** 前端时间轴配色。 */
    private final String color;

    ApplyStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    /** 是否为终态（不可再流转）。 */
    public boolean isFinal() {
        return this == CHECKED_OUT || this == CANCELLED || this == REJECTED || this == EXPIRED;
    }

    /** 占用床位资源的状态（用于判断冲突与统计占用率）。 */
    public boolean holdsBed() {
        return this == APPROVED || this == CHECKED_IN;
    }

    /** 状态字典（值 + 中文标签 + 展示色）。返回类型是具体记录，不再是 Map。 */
    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values())
                .map(e -> com.overseas.dorm.common.api.OptionVO.of(e.name(), e.label, e.color))
                .toList();
    }
}
