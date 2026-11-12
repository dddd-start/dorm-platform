package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * AI 会话中「当前主导的专家 Agent」。
 *
 * <p>对应「多 Agent 协作」：一个 Router 负责意图识别，
 * 再把对话交给某个专家 Agent（各自独立 Prompt 与模型配置）。
 */
@Getter
public enum AgentType {

    ROUTER("调度", "负责理解意图并把请求分派给专家 Agent"),
    ROOM("房源顾问", "查询房源、推荐房间、比较公寓"),
    POLICY("政策顾问", "回答入住政策、费用报销、属地注意事项"),
    APPLY("申请助手", "协助填写并提交入住申请，跟踪进度"),
    ORDER("订单助手", "查询订单、办理入住退房、退改"),
    OPS("运维助手", "面向管理员的运营分析（只读）");

    private final String label;
    private final String description;

    AgentType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public static AgentType of(String name) {
        return Arrays.stream(values())
                .filter(e -> e.name().equalsIgnoreCase(name == null ? "" : name))
                .findFirst()
                .orElse(ROUTER);
    }
}
