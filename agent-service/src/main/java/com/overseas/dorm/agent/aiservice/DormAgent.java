package com.overseas.dorm.agent.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 宿舍助手的统一接口（LangChain4j AiService）。
 *
 * LangChain4j 的 AiServices 是"声明式"的：你只写接口，框架负责
 * 拼 Prompt、调模型、解析工具调用、回灌工具结果、维护记忆。
 * 这里只声明一个接口，然后在 {@link AgentRegistry} 中用不同的
 * 系统提示词与工具子集创建<b>多个实例</b>，分别对应不同角色的专家 Agent。
 *
 * <p>为什么不在代码里定义 5 个接口（RoomAgent / PolicyAgent / …）：
 * 它们的<b>方法签名完全相同</b>，差别只在配置。用 5 个接口会带来
 * 5 份几乎一样的声明、5 处需要同步修改的地方，却不增加任何表达力。
 * 真正需要区分的是"配置"，配置就应该放在配置里。
 *
 * 它是多轮对话的隔离键。LangChain4j 会用它从 ChatMemoryProvider 取对应会话的记忆，
 * 从而让不同用户、不同会话的上下文互不串扰。本项目传入的是 conversationId，
 * 记忆的实际存储在 {@code DormChatMemoryStore}（MySQL）。
 *
 * 我们需要的不是"最终答案"，而是"逐字生成的流"，
 * 因为要在生成过程中做实时安全审核、把 token 写入 Redis 供断点续传、
 * 以及把工具调用过程推送给前端。这些能力都只有在 TokenStream 上才拿得到。
 */
public interface DormAgent {

    /**
     * 流式对话。
     *
     * @param conversationId 会话 ID（同时作为记忆隔离键）
     * @param message        用户消息
     * @return 令牌流；调用方需要自己 up 回调并 `start()`
     */
    TokenStream chat(@MemoryId Long conversationId, @UserMessage String message);
}
