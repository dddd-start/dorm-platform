package com.overseas.dorm.domain.vo;

import com.overseas.dorm.domain.entity.DormBuilding;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 公寓视图。
 */
@Data
public class BuildingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long regionId;
    private String regionName;
    private String code;
    private String name;
    private String country;
    private String city;
    private String address;
    private String contactName;
    private String contactPhone;
    private Integer status;
    private String remark;

    /** 房源概要。 */
    private Integer roomCount;
    private Integer bedCount;
    private Integer idleBedCount;

    public static BuildingVO of(DormBuilding b) {
        BuildingVO vo = new BuildingVO();
        vo.id = b.getId();
        vo.regionId = b.getRegionId();
        vo.code = b.getCode();
        vo.name = b.getName();
        vo.country = b.getCountry();
        vo.city = b.getCity();
        vo.address = b.getAddress();
        vo.contactName = b.getContactName();
        vo.contactPhone = b.getContactPhone();
        vo.status = b.getStatus();
        vo.remark = b.getRemark();
        return vo;
    }
}
