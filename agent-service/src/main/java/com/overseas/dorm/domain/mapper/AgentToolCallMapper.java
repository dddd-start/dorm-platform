package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentToolCall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工具调用审计数据访问（谁调了哪个工具、参数、结果、耗时）。
 */
@Mapper
public interface AgentToolCallMapper extends BaseMapper<AgentToolCall> {


    @Select("SELECT * FROM agent_tool_call WHERE message_id = #{messageId} ORDER BY seq_in_turn")
    List<AgentToolCall> selectByMessage(@Param("messageId") Long messageId);

    /** 工具调用排行（运维看板：哪些工具最常被调用、失败率如何）。 */
    @Select("""
            SELECT tool_name,
                   COUNT(*) AS total,
                   SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed,
                   ROUND(AVG(cost_ms)) AS avg_cost_ms
              FROM agent_tool_call
             WHERE create_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
             GROUP BY tool_name
             ORDER BY total DESC
            """)
    List<com.overseas.dorm.domain.vo.ToolStatVO> statistics(@Param("days") int days);
}
