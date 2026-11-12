package com.overseas.dorm.common.statemachine;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.common.exception.BizException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 入住订单状态机。
 *
 * <pre>
 *   RESERVED   -> CHECKED_IN | CANCELLED
 *   CHECKED_IN -> CHECKED_OUT
 * </pre>
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(OrderStatus.RESERVED, EnumSet.of(OrderStatus.CHECKED_IN, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.CHECKED_IN, EnumSet.of(OrderStatus.CHECKED_OUT));
        TRANSITIONS.put(OrderStatus.CHECKED_OUT, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Collections.emptySet()).contains(to);
    }

    public static void assertTransition(OrderStatus from, OrderStatus to, String action) {
        if (!canTransition(from, to)) {
            throw BizException.of(ResultCode.ORDER_STATE_ILLEGAL,
                    "订单当前状态为「%s」，不允许执行「%s」".formatted(
                            from == null ? "未知" : from.getLabel(), action));
        }
    }

    public static Set<OrderStatus> nextStates(OrderStatus from) {
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)));
    }
}
