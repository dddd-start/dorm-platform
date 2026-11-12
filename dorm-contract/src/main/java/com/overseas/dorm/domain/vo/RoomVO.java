package com.overseas.dorm.domain.vo;

import com.overseas.dorm.common.enums.BedStatus;
import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.RoomStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.domain.entity.DormRoom;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 房间视图（房源管理页使用，含床位明细）。
 */
@Data
public class RoomVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long buildingId;
    private String buildingName;
    private Long regionId;
    private String regionName;
    private String roomNo;
    private Integer floorNo;
    private RoomType roomType;
    private String roomTypeLabel;
    private Integer capacity;
    private GenderLimit genderLimit;
    private String genderLimitLabel;
    private RoomStatus status;
    private String statusLabel;
    private BigDecimal areaSqm;
    private BigDecimal pricePerNight;
    private String currency;
    private List<String> facilities;
    private String remark;

    private Integer idleBeds;
    private Integer occupiedBeds;
    private Integer disabledBeds;
    private List<BedVO> beds;

    public static RoomVO of(DormRoom room) {
        RoomVO vo = new RoomVO();
        vo.id = room.getId();
        vo.buildingId = room.getBuildingId();
        vo.regionId = room.getRegionId();
        vo.roomNo = room.getRoomNo();
        vo.floorNo = room.getFloorNo();
        vo.roomType = room.getRoomType();
        vo.roomTypeLabel = room.getRoomType() == null ? null : room.getRoomType().getLabel();
        vo.capacity = room.getCapacity();
        vo.genderLimit = room.getGenderLimit();
        vo.genderLimitLabel = room.getGenderLimit() == null ? null : room.getGenderLimit().getLabel();
        vo.status = room.getStatus();
        vo.statusLabel = room.getStatus() == null ? null : room.getStatus().getLabel();
        vo.areaSqm = room.getAreaSqm();
        vo.pricePerNight = room.getPricePerNight();
        vo.currency = room.getCurrency();
        vo.facilities = room.getFacilities() == null || room.getFacilities().isBlank()
                ? List.of()
                : Arrays.stream(room.getFacilities().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        vo.remark = room.getRemark();
        return vo;
    }

    /**
     * 床位明细视图。
     */
    @Data
    public static class BedVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long id;
        private String bedNo;
        private BedStatus status;
        private String statusLabel;
        private Long currentApplyId;
    }
}
