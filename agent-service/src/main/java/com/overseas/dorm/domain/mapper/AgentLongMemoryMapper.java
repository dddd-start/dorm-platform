package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentLongMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 长期记忆（用户画像、偏好、会话摘要）数据访问。
 */
@Mapper
public interface AgentLongMemoryMapper extends BaseMapper<AgentLongMemory> {

    /** 取用户高价值记忆（按重要度倒序），供拼装 System Prompt。 */
    @Select("""
            SELECT * FROM agent_long_memory
             WHERE user_id = #{userId}
               AND (expire_time IS NULL OR expire_time > NOW())
             ORDER BY importance DESC, update_time DESC
             LIMIT #{limit}
            """)
    List<AgentLongMemory> selectTop(@Param("userId") Long userId, @Param("limit") int limit);
}
