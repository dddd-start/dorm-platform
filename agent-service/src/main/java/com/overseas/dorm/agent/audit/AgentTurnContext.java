package com.overseas.dorm.agent.audit;

import com.overseas.dorm.agent.security.AgentTokenContext;
import com.overseas.dorm.common.security.LoginUser;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮对话的执行上下文（线程内）。
 *
 * 工具是由 LangChain4j 在自己的调用链里执行的，工具方法本身
 * 拿不到"我现在属于哪一轮对话、哪个用户、第几次工具调用"这些信息。
 * 但这些信息恰恰是审计、去重守卫、限流所必需的。
 *
 * <p>解决办法是在流式线程开始处理一轮对话时把上下文塞进 ThreadLocal，
 * 工具执行链上的任何位置都能读到。这与 {@code UserContext} 的思路一致，
 * 但要额外注意一点：<b>工具可能在别的线程执行</b>
 * （LangChain4j 支持 {@code executeToolsConcurrently()}）。
 * <ol>
 *   <li>ThreadLocal 上下文在并发执行时会丢；</li>
 *   <li>并发调用多个工具会让"同轮重复调用去重"变得不可判定，*       两个并发调用可能在彼此写入缓存之前都通过了检查。</li>
 * </ol>
 * 对于本系统的工具（单次查询几十毫秒），串行执行的延迟代价完全可以接受，
 * 换来的是行为可预测、可审计。这是一个明确的工程取舍。
 */
public final class AgentTurnContext {

    private static final ThreadLocal<AgentTurnContext> HOLDER = new ThreadLocal<>();

    /**
     * 会话 ID -> 当前轮次上下文。
     *
     * <p><b>为什么必须有这个注册表</b>：LangChain4j 的流式回调与工具执行
     * 并不发生在我们发起调用的线程上，而是在模型 HTTP 客户端的读流线程里
     * （本项目已固定为 {@code agent-model-stream-*} 虚拟线程）。这意味着：
     * <pre>
     *   ✗ ThreadLocal 在回调/工具里读不到上下文
     *   ✗ 工具审计记录的 conversationId 会变成 0、turnId 变成 null
     *   ✗ 同轮去重守卫失效（因为没有 turnId 就没有指纹键）
     * </pre>
     * 解决办法是按会话 ID 注册上下文，工具执行前用 chatMemoryId
     * （即 conversationId）把它取回来并显式恢复。
     */
    private static final java.util.Map<Long, AgentTurnContext> REGISTRY = new java.util.concurrent.ConcurrentHashMap<>();

    private final Long conversationId;
    private final Long messageId;
    private final String turnId;
    private final LoginUser user;
    private final String traceId;
    /** 当前用户的 JWT；工具通过 HTTP 调用业务服务时透传，绝不落库/打日志。 */
    private final String token;
    /** 本轮内工具调用的序号。 */
    private int toolSequence = 0;
    /** 本轮 RAG 实际命中的片段数（工具检索与自动检索都汇总到这里）。 */
    private final AtomicInteger ragHits = new AtomicInteger();
    /**
     * 本轮检索到的引用来源（title + snippet）。
     *
     * <p>工具式 RAG（{@code searchDormPolicy}）不会触发 LangChain4j 的
     * {@code onRetrieved} 回调，如果只在那里发 REFERENCE，前端永远拿不到来源。
     * 因此把工具检索结果也汇总到轮次上下文，收尾时统一发一次 REFERENCE。
     */
    private final java.util.List<java.util.Map<String, Object>> references =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private AgentTurnContext(Long conversationId, Long messageId, String turnId, LoginUser user, String traceId) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.turnId = turnId;
        this.user = user;
        this.traceId = traceId;
        this.token = AgentTokenContext.get();
    }

    public static AgentTurnContext begin(Long conversationId, Long messageId, String turnId,
                                        LoginUser user, String traceId) {
        AgentTurnContext context = new AgentTurnContext(conversationId, messageId, turnId, user, traceId);
        HOLDER.set(context);
        if (conversationId != null) {
            REGISTRY.put(conversationId, context);
        }
        return context;
    }

    /** 按会话 ID 取回上下文（工具执行线程使用）。 */
    public static AgentTurnContext byConversation(Long conversationId) {
        return conversationId == null ? null : REGISTRY.get(conversationId);
    }

    /** 在指定上下文下执行逻辑（跨线程恢复身份/链路/轮次）。 */
    public static <T> T runAs(AgentTurnContext context, java.util.function.Supplier<T> action) {
        if (context == null) {
            return action.get();
        }
        AgentTurnContext previous = HOLDER.get();
        String previousToken = AgentTokenContext.get();
        HOLDER.set(context);
        AgentTokenContext.set(context.token);
        try {
            return action.get();
        } finally {
            AgentTokenContext.set(previousToken);
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    public static void runAs(AgentTurnContext context, Runnable action) {
        runAs(context, () -> {
            action.run();
            return null;
        });
    }

    public static AgentTurnContext current() {
        return HOLDER.get();
    }

    public static void clear() {
        AgentTurnContext context = HOLDER.get();
        if (context != null && context.conversationId != null) {
            REGISTRY.remove(context.conversationId, context);
        }
        HOLDER.remove();
    }

    public int nextToolSequence() {
        return ++toolSequence;
    }

    public void addRagHits(int count) {
        if (count > 0) {
            ragHits.addAndGet(count);
        }
    }

    public int ragHits() {
        return ragHits.get();
    }

    /** 记录检索引用；按 title + snippet 去重，最多保留 20 条，避免长轮次无限增长。 */
    public void addReferences(java.util.List<java.util.Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        synchronized (references) {
            for (java.util.Map<String, Object> item : items) {
                if (item == null || references.size() >= 20) {
                    continue;
                }
                String title = String.valueOf(item.getOrDefault("title", ""));
                String snippet = String.valueOf(item.getOrDefault("snippet", ""));
                boolean exists = references.stream().anyMatch(existing ->
                        title.equals(String.valueOf(existing.getOrDefault("title", "")))
                                && snippet.equals(String.valueOf(existing.getOrDefault("snippet", ""))));
                if (!exists) {
                    // 不用 Map.copyOf：引用里的 title/snippet 允许为 null，
                    // 而 Map.copyOf 遇到 null 会抛 NPE，直接把整轮 SSE 打断。
                    references.add(java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(item)));
                }
            }
        }
    }

    /** 当前轮次已收集的引用快照（收尾时发给前端）。 */
    public java.util.List<java.util.Map<String, Object>> references() {
        synchronized (references) {
            return java.util.List.copyOf(references);
        }
    }

    public Long conversationId() {
        return conversationId;
    }

    public Long messageId() {
        return messageId;
    }

    public String turnId() {
        return turnId;
    }

    public LoginUser user() {
        return user;
    }

    public String traceId() {
        return traceId;
    }

    public String token() {
        return token;
    }
}
