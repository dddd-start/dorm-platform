package com.overseas.dorm.common.statemachine;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.exception.BizException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 入住申请状态机 —— 全局唯一的流转权威。
 *
 * <p>为什么必须集中管理：申请单是本系统的核心资产，
 * 一旦出现「已驳回却又被入住」「已退房又被分配床位」这类脏状态，
 * 房源统计、费用结算、审计追溯就全部失去可信度。
 * 因此所有状态变更都必须经过 {@link #assertTransition}，
 * 任何直接 UPDATE status 的代码都视为缺陷（集成测试会覆盖）。
 *
 * <p>允许的流转：
 * <pre>
 *   PENDING    -> APPROVED | REJECTED | CANCELLED
 *   APPROVED   -> CHECKED_IN | CANCELLED | EXPIRED
 *   CHECKED_IN -> CHECKED_OUT
 *   （终态之间不可流转）
 * </pre>
 */
public final class ApplyStateMachine {

    private static final Map<ApplyStatus, Set<ApplyStatus>> TRANSITIONS = new EnumMap<>(ApplyStatus.class);

    static {
        TRANSITIONS.put(ApplyStatus.PENDING,
                EnumSet.of(ApplyStatus.APPROVED, ApplyStatus.REJECTED, ApplyStatus.CANCELLED));
        TRANSITIONS.put(ApplyStatus.APPROVED,
                EnumSet.of(ApplyStatus.CHECKED_IN, ApplyStatus.CANCELLED, ApplyStatus.EXPIRED));
        TRANSITIONS.put(ApplyStatus.CHECKED_IN,
                EnumSet.of(ApplyStatus.CHECKED_OUT));
        TRANSITIONS.put(ApplyStatus.REJECTED, EnumSet.noneOf(ApplyStatus.class));
        TRANSITIONS.put(ApplyStatus.CHECKED_OUT, EnumSet.noneOf(ApplyStatus.class));
        TRANSITIONS.put(ApplyStatus.CANCELLED, EnumSet.noneOf(ApplyStatus.class));
        TRANSITIONS.put(ApplyStatus.EXPIRED, EnumSet.noneOf(ApplyStatus.class));
    }

    private ApplyStateMachine() {
    }

    public static boolean canTransition(ApplyStatus from, ApplyStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Collections.emptySet()).contains(to);
    }

    /** 校验流转，非法则抛出带业务码的异常。 */
    public static void assertTransition(ApplyStatus from, ApplyStatus to, String action) {
        if (!canTransition(from, to)) {
            throw BizException.of(ResultCode.APPLY_STATE_ILLEGAL,
                    "申请当前状态为「%s」，不允许执行「%s」".formatted(
                            from == null ? "未知" : from.getLabel(), action));
        }
    }

    public static Set<ApplyStatus> nextStates(ApplyStatus from) {
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(from, Collections.emptySet()));
    }

    /** 供前端渲染操作按钮：根据当前状态给出可执行动作名。 */
    public static Set<String> allowedActions(ApplyStatus from) {
        return nextStates(from).stream().map(ApplyStateMachine::actionNameOf).collect(Collectors.toSet());
    }

    private static String actionNameOf(ApplyStatus target) {
        return switch (target) {
            case APPROVED -> "APPROVE";
            case REJECTED -> "REJECT";
            case CANCELLED -> "CANCEL";
            case CHECKED_IN -> "CHECK_IN";
            case CHECKED_OUT -> "CHECK_OUT";
            case EXPIRED -> "EXPIRE";
            default -> target.name();
        };
    }
}
