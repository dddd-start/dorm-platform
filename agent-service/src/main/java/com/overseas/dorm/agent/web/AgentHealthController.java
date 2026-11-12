package com.overseas.dorm.agent.web;

import com.overseas.dorm.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 服务健康检查（无需登录）。
 */
@RestController
@RequestMapping("/api")
public class AgentHealthController {

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "service", "agent-service",
                "status", "UP"));
    }
}
