package com.overseas.dorm.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 审批通过 + 分配房间床位。
 *
 * <p>把"通过"和"分房"合成一个接口，是因为业务上两者不可分割：
 * 一张 APPROVED 但没有床位的申请，会让员工以为已经安排好住宿，
 * 实际到店却无床可住。要么一起成功，要么都不做。
 */
public record ApplyApproveRequest(
        @NotNull(message = "缺少申请ID") Long applyId,
        @NotNull(message = "请选择房间") Long roomId,
        @NotNull(message = "请选择床位") Long bedId,
        @Size(max = 255) String remark
) {
}
