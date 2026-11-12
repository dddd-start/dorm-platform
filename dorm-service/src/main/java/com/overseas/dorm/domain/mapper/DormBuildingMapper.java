package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.DormBuilding;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公寓数据访问。
 */
@Mapper
public interface DormBuildingMapper extends BaseMapper<DormBuilding> {
}
