package com.overseas.dorm.agent.vo;

import java.io.Serializable;
import java.util.Map;

/**
 * 一条工具调用记录。
 *
 * <p>两处使用同一结构，因此必须是同一个类型：
 * <ol>
 *   <li>助手消息上附着的历史工具调用（{@code agent_message.tool_calls_json}），
 *       刷新页面后前端据此重建工具卡片；</li>
 *   <li>管理员会话回放里的工具调用节点。</li>
 * </ol>
 * 此前回放用 {@code Map} 手写、消息历史干脆不写，结果是"刷新页面后工具卡片消失"，
 * 而回放里的字段名还得靠人去跟前端对齐。
 *
 * <p>{@code arguments} 保留为 {@code Map<String, Object>}：它是模型自由生成的工具参数，
 * 键集合随工具不同而完全不同，属于<b>动态键</b>场景。硬造固定类型只会得到一堆永远为 null 的字段。
 */
public record ToolCallVO(
        Integer seqInTurn,
        String tool,
        /** 工具的中文标签，供界面直接展示（无需前端再维护一份映射表）。 */
        String label,
        Map<String, Object> arguments,
        Boolean success,
        Boolean deduplicated,
        Boolean permissionChecked,
        String error,
        Long costMs
) implements Serializable {

}
