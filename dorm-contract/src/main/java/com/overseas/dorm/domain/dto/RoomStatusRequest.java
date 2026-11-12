package com.overseas.dorm.domain.dto;

import com.overseas.dorm.common.enums.RoomStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量开关房 / 置为维修。
 *
 * <p>为什么必须支持批量：一栋公寓淡季整层关闭是常见操作，
 * 逐间点一遍既慢又容易漏。批量接口一次事务内完成，
 * 并且对每一间都写审计日志（可回溯到"这次关闭了哪几间"）。
 */
public record RoomStatusRequest(
        @NotEmpty(message = "请选择房间") List<Long> roomIds,
        @NotNull(message = "请选择目标状态") RoomStatus status,
        @Size(max = 255) String remark
) {
}
