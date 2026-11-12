package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户对回答的点赞/点踩数据访问。
 */
@Mapper
public interface AgentFeedbackMapper extends BaseMapper<AgentFeedback> {
}
