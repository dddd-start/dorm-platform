package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 换房 / 重新分配床位（管理员在员工入住前后都可能用到）。
 *
 * <p>实现要点：先占新床、再放旧床，且两步在同一事务内。
 * 顺序不能反 —— 反了会出现「旧床已释放、新床被别人抢走」导致员工无处可住。
 */
public record ApplyReassignRequest(
        @NotNull(message = "缺少申请ID") Long applyId,
        @NotNull(message = "请选择新的床位") Long bedId,
        @Size(max = 255) String reason
) {
}
