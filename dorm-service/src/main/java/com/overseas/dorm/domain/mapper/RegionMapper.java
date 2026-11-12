package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.Region;
import org.apache.ibatis.annotations.Mapper;

/**
 * 区域数据访问。
 *
 * <p>区域是所有数据权限的载体：区域管理员只能看到自己的区域，
 * 因此按区域过滤的条件由 {@code DataScope} 统一注入，而不是各调用方自行拼接。
 */
@Mapper
public interface RegionMapper extends BaseMapper<Region> {
}
