package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志数据访问。
 *
 * <p>审计表只增不改：没有 update / delete 方法，避免"事后修改审计记录"这种可能。
 * 保留期清理由归档任务按时间范围整段删除（而不是随手删单条）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
