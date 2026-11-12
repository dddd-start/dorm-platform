package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentPendingApproval;
import org.apache.ibatis.annotations.Mapper;

/**
 * 人工干预待办数据访问（需要管理员确认后才继续的操作）。
 */
@Mapper
public interface AgentPendingApprovalMapper extends BaseMapper<AgentPendingApproval> {
}
