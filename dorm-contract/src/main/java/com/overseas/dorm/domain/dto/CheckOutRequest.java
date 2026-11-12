package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * 办理退房。
 *
 * @param orderId           订单 ID（与 applyId 二选一，orderId 优先）
 * @param earlyCheckOutReason 提前退房原因：早于原定退房日时必须填写
 */
public record CheckOutRequest(
        Long orderId,
        Long applyId,
        @Size(max = 255) String earlyCheckOutReason,
        @Size(max = 255) String remark
) {
}
