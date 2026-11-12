package com.overseas.dorm.domain.dto;

import com.overseas.dorm.common.enums.RoomType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 创建入住申请。
 *
 * @param idempotencyKey 客户端生成的幂等键。网页端用"表单会话 ID + 提交序号"，
 *                       AI 助手用"对话轮次 ID"。服务端据此保证重复提交/重试
 *                       只会产生一张申请单（唯一索引兜底）。
 */
public record ApplyCreateRequest(
        @NotNull(message = "请选择目的地区域") Long regionId,
        @Size(max = 64) String destCountry,
        @Size(max = 64) String destCity,
        Long buildingId,
        RoomType preferredRoomType,
        @NotNull(message = "请选择入住日期") LocalDate checkInDate,
        @NotNull(message = "请选择退房日期") LocalDate checkOutDate,
        @Size(max = 32) String tripNo,
        @Size(max = 500) String reason,
        @Size(max = 64) String idempotencyKey
) {
}
