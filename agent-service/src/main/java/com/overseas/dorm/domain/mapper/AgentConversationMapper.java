package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话数据访问。
 */
@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
}
