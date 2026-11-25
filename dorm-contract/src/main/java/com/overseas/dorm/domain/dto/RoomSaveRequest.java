package com.overseas.dorm.domain.dto;

import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.RoomType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 房间新增/编辑。
 *
 * <p>床位数量由房型决定（见 {@code RoomService}），前端不要直接传 capacity，* 否则会出现「房型是单人间却有 4 张床」这种自相矛盾的数据。
 */
public record RoomSaveRequest(
        Long id,
        @NotNull(message = "请选择所属公寓") Long buildingId,
        @NotBlank(message = "房间号不能为空") @Size(max = 32) String roomNo,
        @Min(value = 1, message = "楼层不合法") Integer floorNo,
        @NotNull(message = "请选择房型") RoomType roomType,
        @NotNull(message = "请选择性别限制") GenderLimit genderLimit,
        @DecimalMin(value = "0", message = "面积不能为负") BigDecimal areaSqm,
        @DecimalMin(value = "0", message = "价格不能为负") BigDecimal pricePerNight,
        @Size(max = 8) String currency,
        @Size(max = 500) String facilities,
        @Size(max = 255) String remark
) {
}
