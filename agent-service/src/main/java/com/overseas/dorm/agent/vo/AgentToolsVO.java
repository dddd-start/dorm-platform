package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 工具与编排的静态信息（管理端展示"助手具备哪些能力、怎么路由"）。
 */
public record AgentToolsVO(
        List<String> assembledAgents,
        List<ToolCatalogItem> tools,
        Map<String, List<String>> keywordRouting,
        int maxToolCallInvocations,
        int maxToolCallingRoundTrips,
        int turnTimeoutSeconds
) implements Serializable {

    /** 单个工具的能力描述。 */
    public record ToolCatalogItem(String name, String label, boolean readOnly) {
    }

}
