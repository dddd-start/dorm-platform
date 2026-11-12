package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.List;

/**
 * 审计日志的模块与结果。
 */
public final class AuditEnums {

    private AuditEnums() {
    }

    @Getter
    public enum Module {
        AUTH("认证"), REGION("区域"), BUILDING("公寓"), ROOM("房间"), BED("床位"),
        APPLY("入住申请"), ORDER("入住订单"), USER("用户"), AGENT("AI 助手"), ADMIN("系统管理");

        private final String label;

        Module(String label) {
            this.label = label;
        }
    }

    @Getter
    public enum Result {
        SUCCESS("成功"), FAIL("失败");

        private final String label;

        Result(String label) {
            this.label = label;
        }
    }

    // ================================================================ 审计动作与被审计对象

    /**
     * 审计动作名与对象类型的常量。
     *
     * <p>为什么要把这些字符串收拢到常量里：审计动作是<b>运营查询的依据</b>
     * （"谁在什么时候关过房"就是按 {@code action} 过滤的）。散落的字面量一旦
     * 有一处拼写不同（{@code ROOM_STATUS_} 写成 {@code ROOM_STATE_}），
     * 那条记录在按动作检索时就永远查不到 —— 而日志本身看起来完全正常。
     *
     * <p>这里把"前缀"与"对象类型"都定义成常量；需要拼上枚举名的地方
     * （如 {@code ROOM_STATUS_ + status.name()}）统一引用前缀常量，
     * 保证同族动作在检索时能被一个前缀全部覆盖。
     */
    public static final String ROOM_STATUS_ACTION_PREFIX = "ROOM_STATUS_";
    public static final String BED_STATUS_ACTION_PREFIX = "BED_STATUS_";

    public static final String TARGET_REGION = "REGION";
    public static final String TARGET_BUILDING = "BUILDING";
    public static final String TARGET_ROOM = "ROOM";
    public static final String TARGET_BED = "BED";
    public static final String TARGET_APPLY = "STAY_APPLY";
    public static final String TARGET_ORDER = "STAY_ORDER";
    public static final String TARGET_USER = "SYS_USER";
    public static final String TARGET_AGENT_CONVERSATION = "AGENT_CONVERSATION";

    /** 区域/公寓等状态的通用启停枚举（供前端下拉复用）。 */
    public static List<com.overseas.dorm.common.api.OptionVO> enableOptions() {
        return List.of(
                com.overseas.dorm.common.api.OptionVO.ofNumber(1, "启用"),
                com.overseas.dorm.common.api.OptionVO.ofNumber(0, "停用"));
    }
}
