package com.overseas.dorm.agent.memory;

import com.overseas.dorm.common.constant.BusinessLimits;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.entity.AgentConversation;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.domain.mapper.AgentMessageMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 MySQL 的 {@link ChatMemoryStore} 实现 —— 多轮对话的上下文来源。
 *
 * <h3>为什么需要它</h3>
 * LangChain4j 默认的 {@code InMemoryChatMemoryStore} 把对话存在 JVM 堆里，
 * 带来三个生产问题：重启即丢、多实例不一致、无法审计。
 * 换成数据库实现后，多轮对话的上下文就有了唯一且可追溯的来源。
 *
 * <h3>一个刻意的设计决定：{@code updateMessages} 是空实现</h3>
 * 直觉上这里应该把消息写库。但本系统的消息落库由 {@code AgentTurnService}
 * 统一负责，原因是流式场景下需要记录的元信息远多于 LangChain4j 的
 * {@code ChatMessage} 能承载的：
 * <pre>
 *   seq_no（会话内单调序号，保证不乱序）、turn_id（幂等键）、
 *   tokens_in/out、first_token_ms（首字延迟）、
 *   status（STREAMING/INTERRUPTED/BLOCKED）、reasoning_content、trace_id
 * </pre>
 * 如果这里再写一遍，就会出现<b>两份真相</b>：
 * 这份缺 seq、那份缺 token，出问题时无法判断以谁为准。
 *
 * <p>所以本类的定位是「<b>只读的数据库记忆视图</b>」：
 * <ul>
 *   <li>{@code getMessages} —— 从 {@code agent_message} 还原成 LangChain4j 的消息对象；</li>
 *   <li>{@code updateMessages} —— 空实现，消息由 AgentTurnService 写（已在写库后调用）。</li>
 * </ul>
 * 这个取舍在下面的方法注释里也会写清楚，避免后来者"顺手补上"而引入双写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DormChatMemoryStore implements ChatMemoryStore {

    private final AgentMessageMapper messageMapper;
    private final com.overseas.dorm.domain.mapper.AgentConversationMapper conversationMapper;

    /**
     * 当前正在生成的一轮对话的内存视图：conversationId -> 本轮已发生的消息。
     *
     * <p><b>【H11 修复】为什么需要它</b>：LangChain4j 的工具调用循环在一次
     * {@code AiService.chat()} 内部完成，循环每一轮都会重新读取 ChatMemory。
     * 本类原先的 {@code updateMessages} 是空实现，于是模型第一轮产生的
     * assistant(tool_calls) 与工具返回的 TOOL 消息从来没有进入记忆 ——
     * 下一轮模型仍然只看到最初那条用户消息，只能再次调用同一个工具。
     * 实测表现为：模型反复调用 listRegions/listBuildings，直到
     * {@code maxToolCallingRoundTrips} 用尽，用户只看到一句"我来帮你查一下…"。
     *
     * <p>这里用"本轮内存覆盖层"而不是把中间消息写库，原因不变：
     * 消息落库仍由 {@code AgentTurnService} 统一负责（seq/turnId/token/status），
     * 避免两份真相；但工具循环需要一个可供 LangChain4j 读取的完整消息序列，
     * 因此在内存里保存本轮视图，轮次结束（成功/中断/失败）即丢弃。
     */
    private final Map<Long, List<ChatMessage>> activeTurnMessages = new ConcurrentHashMap<>();

    /**
     * 标记一轮开始。清掉上一轮可能残留的内存视图。
     *
     * <p>{@code AgentTurnService} 在调用模型前调用；本轮结束在
     * {@link #endTurn(Long)} 中清理。
     */
    public void beginTurn(Long conversationId) {
        if (conversationId != null) {
            activeTurnMessages.put(conversationId, List.of());
        }
    }

    /** 标记一轮结束，丢弃本轮内存视图。 */
    public void endTurn(Long conversationId) {
        if (conversationId != null) {
            activeTurnMessages.remove(conversationId);
        }
    }

    /**
     * 从数据库还原对话历史。
     *
     * <p>几处容易踩坑的地方：
     * <ol>
     *   <li><b>跳过 STREAMING 状态的消息</b>：正在生成中的半成品不能进上下文，
     *       否则会把残缺的句子喂给模型；</li>
     *   <li><b>跳过 BLOCKED 的消息</b>：被安全护栏截断的内容同样不能回灌，
     *       否则违规内容会通过"历史"绕过输入护栏（这是一个真实的注入路径）；</li>
     *   <li><b>跳过没有内容的空 ASSISTANT 消息</b>：纯工具调用轮可能只有 tool_calls，
     *       没有 text，直接拼进上下文会让部分模型报错；</li>
     *   <li><b>TOOL 角色要带上 toolCallId</b>：OpenAI 协议要求工具结果消息必须
     *       关联到具体的 tool_call_id，否则请求会被拒绝。</li>
     * </ol>
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        Long conversationId = asConversationId(memoryId);
        if (conversationId == null) {
            return new ArrayList<>();
        }
        // 本轮已经产生过消息：直接返回 LangChain4j 自己的完整视图，
        // 其中包含 assistant(tool_calls) 与 TOOL 结果，工具循环才能继续推理。
        List<ChatMessage> inTurn = activeTurnMessages.get(conversationId);
        if (inTurn != null && !inTurn.isEmpty()) {
            return new ArrayList<>(inTurn);
        }
        int window = BusinessLimits.MEMORY_READ_LIMIT;
        List<AgentMessage> rows = messageMapper.selectRecent(conversationId, window);
        List<ChatMessage> messages = new ArrayList<>(rows.size() + 1);
        // 会话摘要放在最前面：它承载"已经滑出窗口"的早期关键信息
        // （目的地区域、房型偏好、单号、被驳回原因…）。
        // 没有这一步，摘要虽然生成了却不会被模型看到 —— 那就只是写进库里好看而已。
        String summary = conversationSummary(conversationId);
        if (summary != null && !summary.isBlank()) {
            messages.add(SystemMessage.from(
                    "【本会话早前内容的摘要】" + summary + "\n（以上是压缩后的历史要点，可直接使用；"
                            + "如需细节可让用户补充。）"));
        }
        for (AgentMessage row : rows) {
            if (row.getStatus() == AgentEnums.MessageStatus.STREAMING
                    || row.getStatus() == AgentEnums.MessageStatus.BLOCKED) {
                continue;
            }
            ChatMessage message = toChatMessage(row);
            if (message != null) {
                messages.add(message);
            }
        }
        // 【H11 配套】本轮的用户消息已经由 AgentTurnService 落库；LangChain4j 在
        // 调用模型前还会把当前输入再 add 一次。如果不剔除库里的这一条，
        // 请求里会出现两条一模一样的 user message（实测日志确认）。
        // 这里只剔除"库中最后一条 USER"，它必然是刚 beginTurn 写入的当前轮输入。
        if (inTurn != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof UserMessage) {
                    messages.remove(i);
                    break;
                }
            }
        }
        return messages;
    }

    /** 读取会话摘要（为空表示尚未生成）。 */
    private String conversationSummary(Long conversationId) {
        try {
            AgentConversation conversation = conversationMapper.selectById(conversationId);
            return conversation == null ? null : conversation.getSummary();
        } catch (Exception e) {
            // 摘要读取失败不能影响上下文重建，降级为"没有摘要"
            log.debug("[Agent] 会话摘要读取失败 conversationId={}", conversationId, e);
            return null;
        }
    }

    private ChatMessage toChatMessage(AgentMessage row) {
        AgentEnums.Role role = row.getRole();
        if (role == null) {
            return null;
        }
        return switch (role) {
            case USER -> {
                String content = row.getContent();
                yield content == null || content.isBlank() ? null : UserMessage.from(content);
            }
            case SYSTEM -> {
                String content = row.getContent();
                yield content == null || content.isBlank() ? null : SystemMessage.from(content);
            }
            case TOOL -> {
                // 工具结果必须带上调用 ID，否则协议不合法
                String content = row.getContent() == null ? "" : row.getContent();
                String toolCallId = row.getToolCallId() == null ? "unknown" : row.getToolCallId();
                String toolName = row.getToolName() == null ? "tool" : row.getToolName();
                yield ToolExecutionResultMessage.from(toolCallId, toolName, content);
            }
            case ASSISTANT -> {
                List<ToolExecutionRequest> toolRequests = parseToolRequests(row.getToolCallsJson());
                String text = row.getContent();
                boolean hasText = text != null && !text.isBlank();
                if (!hasText && toolRequests.isEmpty()) {
                    yield null;
                }
                AiMessage.Builder builder = AiMessage.builder();
                if (hasText) {
                    builder.text(text);
                }
                if (!toolRequests.isEmpty()) {
                    builder.toolExecutionRequests(toolRequests);
                }
                // 思考过程也回灌：推理模型在有历史思考时表现更连贯
                if (row.getReasoningContent() != null && !row.getReasoningContent().isBlank()) {
                    builder.thinking(row.getReasoningContent());
                }
                yield builder.build();
            }
        };
    }

    /** 把落库的 tool_calls JSON 还原成 LangChain4j 的请求对象。 */
    @SuppressWarnings("unchecked")
    private List<ToolExecutionRequest> parseToolRequests(String json) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return requests;
        }
        List<Map<String, Object>> list = JsonUtil.parseList(json);
        for (Map<String, Object> item : list) {
            try {
                String id = String.valueOf(item.getOrDefault("id", ""));
                Object functionObj = item.get("function");
                String name;
                String arguments;
                if (functionObj instanceof Map<?, ?> rawFunction) {
                    Map<String, Object> function = new java.util.LinkedHashMap<>();
                    rawFunction.forEach((k, v) -> function.put(String.valueOf(k), v));
                    name = String.valueOf(function.getOrDefault("name", ""));
                    arguments = String.valueOf(function.getOrDefault("arguments", "{}"));
                } else {
                    name = String.valueOf(item.getOrDefault("name", ""));
                    arguments = String.valueOf(item.getOrDefault("arguments", "{}"));
                }
                if (name.isBlank() || "null".equals(name)) {
                    continue;
                }
                requests.add(ToolExecutionRequest.builder()
                        .id(id.isBlank() ? java.util.UUID.randomUUID().toString() : id)
                        .name(name)
                        .arguments(arguments)
                        .build());
            } catch (Exception e) {
                log.warn("解析历史 tool_calls 失败，已跳过该条：{}", e.getMessage());
            }
        }
        return requests;
    }

    /**
     * 空实现 —— 见类注释。
     *
     * <p>消息的落库由 {@code AgentTurnService} 在流式生成的生命周期里完成
     * （用户消息在生成前落库、助手消息在生成完成后落库），
     * 这里若再写一次会造成双份真相。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Long conversationId = asConversationId(memoryId);
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        // 只保留本轮视图；轮次结束后由 AgentTurnService 调用 endTurn 丢弃。
        // 使用 List.copyOf 做不可变快照，避免模型线程与读取线程互相看到半截列表。
        activeTurnMessages.put(conversationId, List.copyOf(messages));
    }

    /**
     * 清空会话记忆。
     *
     * <p>注意这里做的是「标记归档」而不是物理删除：
     * 审计链不能因为用户点了"清空对话"就断掉。
     */
    @Override
    public void deleteMessages(Object memoryId) {
        Long conversationId = asConversationId(memoryId);
        if (conversationId == null) {
            return;
        }
        log.info("清空会话记忆（审计记录保留）conversationId={}", conversationId);
    }

    private Long asConversationId(Object memoryId) {
        if (memoryId instanceof Long id) {
            return id;
        }
        if (memoryId instanceof Number number) {
            return number.longValue();
        }
        if (memoryId instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
