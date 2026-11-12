package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 反馈评价（回答点赞 / 点踩，以及检索日志的反馈字段）。
 *
 * <p>同一个枚举服务两处：{@code agent_feedback.rating} 与
 * {@code rag_retrieval_log.feedback}。两者语义相同（用户对某次回答/检索是否满意），
 * 用同一个枚举能避免"一边叫 LIKE 一边叫 GOOD"这类不一致 ——
 * 而这类不一致在统计时会静默少算一半数据。
 */
@Getter
public enum FeedbackRating {

    /** 未评价（检索日志的初始值）。 */
    NONE("未评价"),
    /** 有用。 */
    LIKE("有用"),
    /** 没用。 */
    DISLIKE("没用");

    private final String label;

    FeedbackRating(String label) {
        this.label = label;
    }

    /**
     * 从字符串解析；无法识别时返回 null 而不是抛异常。
     *
     * <p>为什么宽松解析：这个值可能来自 HTTP 查询参数或历史数据，
     * 调用方需要区分"没给"与"给错了"，因此由调用方做校验比在这里抛异常更合适。
     */
    public static FeedbackRating of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(e -> e.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }
}
