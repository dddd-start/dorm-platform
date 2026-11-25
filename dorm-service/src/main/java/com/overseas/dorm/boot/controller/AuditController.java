package com.overseas.dorm.boot.controller;

import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.boot.security.RequireRole;
import com.overseas.dorm.domain.entity.AuditLog;
import com.overseas.dorm.domain.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志接口（管理员）。
 *
 * <p>有两个入口，对应两类问题：
 * <ul>
 *   <li>按条件翻列表，「这个房间是谁在什么时候关闭的？」</li>
 *   <li>按 traceId 拉全链路，「这次申请失败到底经过了哪些步骤？」</li>
 * </ul>
 */
@Tag(name = "09-审计日志", description = "操作审计与全链路回溯")
@RestController
@RequestMapping("/api/audits")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @Operation(summary = "审计日志分页")
    @GetMapping
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<PageResult<AuditLog>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.ok(auditService.page(current, size, module, action, userId, targetId, traceId, from, to));
    }

    @Operation(summary = "按 traceId 查看完整链路", description = "问题定位用：一次请求产生的全部审计记录")
    @GetMapping("/trace/{traceId}")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<List<AuditLog>> byTrace(@PathVariable String traceId) {
        return Result.ok(auditService.byTraceId(traceId));
    }
}
