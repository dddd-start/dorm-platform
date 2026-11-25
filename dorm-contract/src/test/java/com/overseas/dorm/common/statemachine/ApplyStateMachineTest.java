package com.overseas.dorm.common.statemachine;

import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机单元测试。
 *
 * <p>为什么状态机值得单独写这么细的测试：
 * 它是全系统唯一被允许修改业务状态的入口。一旦状态机有洞，
 * 就会产生「已驳回却被入住」「已退房又占了床位」这类脏数据，
 * 而这些脏数据会污染房源统计、费用结算与审计追溯，修复成本极高。
 */
class ApplyStateMachineTest {

    @ParameterizedTest(name = "{0} -> {1} 应当允许")
    @CsvSource({
            "PENDING,APPROVED",
            "PENDING,REJECTED",
            "PENDING,CANCELLED",
            "APPROVED,CHECKED_IN",
            "APPROVED,CANCELLED",
            "APPROVED,EXPIRED",
            "CHECKED_IN,CHECKED_OUT"
    })
    @DisplayName("合法流转")
    void shouldAllowLegalTransitions(ApplyStatus from, ApplyStatus to) {
        assertThat(ApplyStateMachine.canTransition(from, to)).isTrue();
        ApplyStateMachine.assertTransition(from, to, "测试动作");
    }

    @ParameterizedTest(name = "{0} -> {1} 应当拒绝")
    @CsvSource({
            // 不能跳步
            "PENDING,CHECKED_IN",
            "PENDING,CHECKED_OUT",
            "APPROVED,CHECKED_OUT",
            // 终态不可复活
            "REJECTED,APPROVED",
            "REJECTED,CHECKED_IN",
            "CANCELLED,APPROVED",
            "EXPIRED,APPROVED",
            "CHECKED_OUT,CHECKED_IN",
            "CHECKED_OUT,APPROVED",
            // 已入住不能直接取消（必须先退房）
            "CHECKED_IN,CANCELLED",
            "CHECKED_IN,APPROVED",
            "CHECKED_IN,REJECTED",
            // 不能驳回已通过的单
            "APPROVED,REJECTED"
    })
    @DisplayName("非法流转被拒绝")
    void shouldRejectIllegalTransitions(ApplyStatus from, ApplyStatus to) {
        assertThat(ApplyStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> ApplyStateMachine.assertTransition(from, to, "测试动作"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("空值安全：任何涉及 null 的判断都返回 false 而不是抛异常")
    void nullSafety() {
        assertThat(ApplyStateMachine.canTransition(null, ApplyStatus.APPROVED)).isFalse();
        assertThat(ApplyStateMachine.canTransition(ApplyStatus.PENDING, null)).isFalse();
        assertThat(ApplyStateMachine.nextStates(null)).isEmpty();
    }

    @Test
    @DisplayName("终态没有任何后继状态")
    void finalStatesHaveNoSuccessors() {
        for (ApplyStatus status : ApplyStatus.values()) {
            if (status.isFinal()) {
                assertThat(ApplyStateMachine.nextStates(status))
                        .as("终态 %s 不应有后继", status)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("holdsBed：只有 APPROVED 与 CHECKED_IN 占用床位资源")
    void onlyApprovedAndCheckedInHoldBed() {
        assertThat(ApplyStatus.APPROVED.holdsBed()).isTrue();
        assertThat(ApplyStatus.CHECKED_IN.holdsBed()).isTrue();
        assertThat(ApplyStatus.PENDING.holdsBed()).isFalse();
        assertThat(ApplyStatus.REJECTED.holdsBed()).isFalse();
        assertThat(ApplyStatus.CANCELLED.holdsBed()).isFalse();
        assertThat(ApplyStatus.EXPIRED.holdsBed()).isFalse();
        assertThat(ApplyStatus.CHECKED_OUT.holdsBed()).isFalse();
    }

    @Test
    @DisplayName("允许动作与下一状态一一对应")
    void allowedActionsMatchNextStates() {
        assertThat(ApplyStateMachine.allowedActions(ApplyStatus.PENDING))
                .containsExactlyInAnyOrder("APPROVE", "REJECT", "CANCEL");
        assertThat(ApplyStateMachine.allowedActions(ApplyStatus.APPROVED))
                .containsExactlyInAnyOrder("CHECK_IN", "CANCEL", "EXPIRE");
        assertThat(ApplyStateMachine.allowedActions(ApplyStatus.CHECKED_IN))
                .containsExactly("CHECK_OUT");
        assertThat(ApplyStateMachine.allowedActions(ApplyStatus.CHECKED_OUT)).isEmpty();
    }

    @Test
    @DisplayName("订单状态机：已取消/已退房是终态")
    void orderStateMachine() {
        assertThat(OrderStateMachine.canTransition(OrderStatus.RESERVED, OrderStatus.CHECKED_IN)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.RESERVED, OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.CHECKED_IN, OrderStatus.CHECKED_OUT)).isTrue();
        // 已入住不能取消（必须走退房）
        assertThat(OrderStateMachine.canTransition(OrderStatus.CHECKED_IN, OrderStatus.CANCELLED)).isFalse();
        // 已取消不能复活
        assertThat(OrderStateMachine.canTransition(OrderStatus.CANCELLED, OrderStatus.CHECKED_IN)).isFalse();
        assertThatThrownBy(() -> OrderStateMachine.assertTransition(
                OrderStatus.CHECKED_OUT, OrderStatus.CHECKED_IN, "重新入住"))
                .isInstanceOf(BizException.class);
    }
}
