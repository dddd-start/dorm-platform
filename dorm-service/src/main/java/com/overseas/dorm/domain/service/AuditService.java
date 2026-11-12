package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.entity.AuditLog;
import com.overseas.dorm.domain.mapper.AuditLogMapper;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.support.PageMapper;
import com.overseas.dorm.domain.support.RequestInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计服务。
 *
 * <p>三条设计原则，都是为了「审计可用」而不是「审计存在」：
 * <ol>
 *   <li><b>不拖累主业务</b>：默认异步入队写库；队列满时由调用线程兜底同步写
 *       （CallerRunsPolicy），宁可慢一点也不能丢审计；</li>
 *   <li><b>失败不影响业务</b>：写审计异常只记日志，绝不向外抛 ——
 *       否则会因为「日志表满」导致「员工无法提交申请」这种荒谬的连锁故障；</li>
 *   <li><b>记录变更前后</b>：只记「改了什么」而不记「改成什么」的审计，
 *       在追责时几乎没用。</li>
 * </ol>
 */
@Slf4j
@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final DataScope dataScope;
    private final TaskExecutor auditExecutor;
    private final boolean async;

    public AuditService(AuditLogMapper auditLogMapper,
                        DataScope dataScope,
                        @Qualifier("auditExecutor") TaskExecutor auditExecutor,
                        @Value("${dorm.audit.async:true}") boolean async) {
        this.auditLogMapper = auditLogMapper;
        this.dataScope = dataScope;
        this.auditExecutor = auditExecutor;
        this.async = async;
    }

    /** 记录一次成功操作。 */
    public void success(AuditEnums.Module module, String action, String targetType, Object targetId,
                        Object before, Object after) {
        record(module, action, targetType, targetId, before, after,
                AuditEnums.Result.SUCCESS, null, null, null);
    }

    /** 记录一次成功操作并携带耗时。 */
    public void success(AuditEnums.Module module, String action, String targetType, Object targetId,
                        Object before, Object after, Long costMs) {
        record(module, action, targetType, targetId, before, after,
                AuditEnums.Result.SUCCESS, null, costMs, null);
    }

    /** 记录一次失败操作（用于「谁尝试越权」这类安全分析）。 */
    public void fail(AuditEnums.Module module, String action, String targetType, Object targetId,
                     String errorMsg) {
        record(module, action, targetType, targetId, null, null,
                AuditEnums.Result.FAIL, errorMsg, null, null);
    }

    private void record(AuditEnums.Module module, String action, String targetType, Object targetId,
                        Object before, Object after, AuditEnums.Result result,
                        String errorMsg, Long costMs, LoginUser explicitUser) {
        AuditLog entity = new AuditLog();
        entity.setTraceId(TraceContext.getOrCreate());
        entity.setModule(module.name());
        entity.setAction(action);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId == null ? null : String.valueOf(targetId));
        entity.setBeforeJson(JsonUtil.toJsonLimit(before, 4000));
        entity.setAfterJson(JsonUtil.toJsonLimit(after, 4000));
        entity.setResult(result.name());
        entity.setErrorMsg(truncate(errorMsg, 480));
        entity.setCostMs(costMs);
        entity.setCreateTime(LocalDateTime.now());

        LoginUser user = explicitUser != null ? explicitUser : UserContext.get();
        if (user != null) {
            entity.setUserId(user.getUserId());
            entity.setUserName(user.getRealName());
            entity.setRole(user.getRole() == null ? null : user.getRole().name());
        }
        // 异步线程里没有请求上下文，这两个为 null 是可接受的
        entity.setIp(RequestInfo.clientIp());
        entity.setUserAgent(RequestInfo.userAgent());

        if (async) {
            auditExecutor.execute(() -> persist(entity));
        } else {
            persist(entity);
        }
    }

    private void persist(AuditLog entity) {
        try {
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计写入失败绝不影响业务；但要留下痕迹以便运维发现
            log.error("审计日志写入失败 action={} target={}/{}: {}",
                    entity.getAction(), entity.getTargetType(), entity.getTargetId(), e.getMessage());
        }
    }

    /** 审计日志分页查询（仅管理员；区域管理员只能看本区域）。 */
    public PageResult<AuditLog> page(long current, long size, String module, String action,
                                     Long userId, String targetId, String traceId,
                                     LocalDateTime from, LocalDateTime to) {
        dataScope.assertAdmin();
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(module != null && !module.isBlank(), AuditLog::getModule, module)
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(userId != null, AuditLog::getUserId, userId)
                .eq(targetId != null && !targetId.isBlank(), AuditLog::getTargetId, targetId)
                .eq(traceId != null && !traceId.isBlank(), AuditLog::getTraceId, traceId)
                .ge(from != null, AuditLog::getCreateTime, from)
                .le(to != null, AuditLog::getCreateTime, to)
                .orderByDesc(AuditLog::getCreateTime);
        return PageMapper.of(auditLogMapper.selectPage(PageMapper.page(current, size), wrapper));
    }

    /** 按 traceId 取整条链路（用于问题定位）。 */
    public java.util.List<AuditLog> byTraceId(String traceId) {
        dataScope.assertAdmin();
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTraceId, traceId)
                .orderByAsc(AuditLog::getCreateTime));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
