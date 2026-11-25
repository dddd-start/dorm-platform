package com.overseas.dorm.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起一轮对话的请求。
 *
 * @param conversationId 会话 ID；为空表示新建会话
 * @param turnId         客户端轮次幂等键。前端在<b>生成前</b>生成，
 *                       重试/重连时带上同一个值：服务端据此判断"这一轮已经答过了"，
 *                       直接重放既有回答而不是重新调用模型，*                       既保证语义一致，也避免用户为一次提问付两次费用。
 * @param message        用户消息
 */
public record AgentChatRequest(
        Long conversationId,
        @Size(max = 64, message = "轮次标识过长") String turnId,
        @NotBlank(message = "请输入你想咨询的内容")
        @Size(max = 4000, message = "单次提问不能超过 4000 字") String message
) {
}
