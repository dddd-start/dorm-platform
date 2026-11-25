package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 单轮流式生成的状态。
 *
 * <p>它同时被三个地方使用，因此必须是同一个枚举而不是三套字符串：
 * <ol>
 *   <li>Redis 里的流状态（{@code dorm:stream:state:{messageId}}），
 *       决定"重连时是补历史还是接实时流"；</li>
 *   <li>断点续传时的终态判定（{@link #terminal()}）；</li>
 *   <li>审计与运维看板。</li>
 * </ol>
 *
 * <p>此前这三处各写各的字符串字面量，一旦有人把 {@code "DONE"} 写成 {@code "COMPLETED"}，
 * 后果是"重连永远认为还在生成中"，客户端会一直等待直到超时，而日志里没有任何报错。
 */
@Getter
public enum StreamState {

    /** 正在生成。重连时应先补历史再切实时流。 */
    GENERATING("生成中"),
    /** 正常结束。 */
    DONE("已完成"),
    /** 被用户打断（已生成部分保留）。 */
    INTERRUPTED("已打断"),
    /** 被内容护栏截断。 */
    BLOCKED("已拦截"),
    /** 生成失败。 */
    FAILED("失败"),
    /** 未知（状态键已过期或从未写入）。 */
    UNKNOWN("未知");

    private final String label;

    StreamState(String label) {
        this.label = label;
    }

    /** 是否为终态：终态意味着不会再产生新事件，重连时补完历史即可关闭连接。 */
    public boolean terminal() {
        return this == DONE || this == INTERRUPTED || this == BLOCKED || this == FAILED;
    }

    /**
     * 从字符串解析，无法识别时返回 {@link #UNKNOWN}。
     *
     * <p>StreamState 存在 Redis 里，键可能因为 TTL 过期而读不到，
     * 因此这里必须容忍"读不到值"，不能抛异常。
     */
    public static StreamState of(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(e -> e.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
