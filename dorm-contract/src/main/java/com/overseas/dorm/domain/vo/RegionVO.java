package com.overseas.dorm.domain.vo;

import com.overseas.dorm.domain.entity.Region;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区域视图（含房源概要，便于前端直接渲染区域卡片）。
 */
@Data
public class RegionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String code;
    private String name;
    private String nameEn;
    private String country;
    private String countryCode;
    private String timezone;
    private String currency;
    private Long managerId;
    private String managerName;
    private Integer status;
    private String remark;

    /** 房源概要，列表查询时可为空。 */
    private Integer roomCount;
    private Integer bedCount;
    private Integer idleBedCount;

    public static RegionVO of(Region region) {
        RegionVO vo = new RegionVO();
        vo.id = region.getId();
        vo.code = region.getCode();
        vo.name = region.getName();
        vo.nameEn = region.getNameEn();
        vo.country = region.getCountry();
        vo.countryCode = region.getCountryCode();
        vo.timezone = region.getTimezone();
        vo.currency = region.getCurrency();
        vo.managerId = region.getManagerId();
        vo.status = region.getStatus();
        vo.remark = region.getRemark();
        return vo;
    }
}
