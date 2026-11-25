package com.overseas.dorm.boot.controller;

import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.domain.service.OutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查（公开）。
 *
 * <p>返回的信息有意保持克制：只暴露"服务是否可用 + 版本"，
 * 不暴露数据库地址、连接池状态等内部细节，* 健康检查接口经常被放在公网且无需认证，是最容易被当成信息收集入口的地方。
 */
@Tag(name = "11-健康检查", description = "存活探针")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final OutboxService outboxService;

    /** 应用版本号。硬编码在这里是有意的：它随发布变更，且健康检查要能立刻反映"当前跑的是哪一版"。 */
    private static final String APP_VERSION = "1.0.0";

    @Value("${spring.application.name:dorm-server}")
    private String appName;

    /** 健康检查结果。 */
    public record HealthVO(String application, String status, String time, String version) {
    }

    /**
     * Outbox 积压情况。
     *
     * <p>四个字段对应 {@code OutboxStatus} 的四种状态。之所以显式列出而不是下发
     * {@code Map<状态名, 数量>}：这是运维告警要读的接口，
     * 固定字段能让"DEAD 积压"这类告警配置一眼看懂，也不会因为新增状态而悄悄改变形状。
     */
    public record OutboxStatsVO(long newCount, long sentCount, long failedCount, long deadCount) {
    }

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Result<HealthVO> health() {
        return Result.ok(new HealthVO(appName, "UP", LocalDateTime.now().toString(), APP_VERSION));
    }

    @Operation(summary = "人工重投死信事件",
            description = """
                    把 DEAD / FAILED 状态的事件重置为待投递并立即可重试。
                    用于下游恢复后补发 —— 死信不会自动重试，必须人工确认过原因再重投。
                    """)
    @PostMapping("/health/outbox/retry")
    @com.overseas.dorm.boot.security.RequireRole({
            com.overseas.dorm.common.enums.UserRole.SUPER_ADMIN})
    public Result<Integer> retryDeadOutbox(
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(outboxService.retryDead(limit));
    }

    @Operation(summary = "Outbox 事件积压情况", description = "管理员排查「通知没发出去」时使用")
    @GetMapping("/health/outbox")
    public Result<OutboxStatsVO> outbox() {
        Map<String, Long> stats = outboxService.stats();
        return Result.ok(new OutboxStatsVO(
                countOf(stats, com.overseas.dorm.common.enums.OutboxStatus.NEW),
                countOf(stats, com.overseas.dorm.common.enums.OutboxStatus.SENT),
                countOf(stats, com.overseas.dorm.common.enums.OutboxStatus.FAILED),
                countOf(stats, com.overseas.dorm.common.enums.OutboxStatus.DEAD)));
    }

    private static long countOf(Map<String, Long> stats, com.overseas.dorm.common.enums.OutboxStatus status) {
        Long value = stats.get(status.name());
        return value == null ? 0L : value;
    }
}
