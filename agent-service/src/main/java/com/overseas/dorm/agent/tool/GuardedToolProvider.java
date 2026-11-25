package com.overseas.dorm.agent.tool;

import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.audit.AgentTurnContext;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.common.util.JsonUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 受控工具提供者，本系统所有工具能力的统一入口。
 *
 * 如果把审计、去重、重试、熔断的代码写进每一个 {@code @Tool} 方法里，
 *
 *
 *
 */
@Slf4j
public class GuardedToolProvider implements ToolProvider {

    private final List<Object> toolObjects;
    private final AgentAuditService auditService;
    /**
     * 用于"用户已停止 → 不再执行任何工具"。
     *
     * <p>为什么需要它：H10 的取消发生在模型流的中途，而工具是由模型返回的 tool_calls 驱动的，* "不再消费 token"并不能阻止 LangChain4j 继续执行工具。若不在这里拦，
     * 用户点了停止之后写类工具（如提交申请）仍可能真的执行：那就不再是"显示问题"，
     * 而是"用户明确喊停之后系统还是替他把单子提交了"。
     */
    private final com.overseas.dorm.agent.stream.AgentStreamBuffer streamBuffer;

    /** 工具名 -> 连续失败次数。 */
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    /** 工具名 -> 熔断截止时间戳。 */
    private final Map<String, AtomicLong> breakerUntil = new ConcurrentHashMap<>();

    /** 连续失败多少次后熔断该工具。 */
    private static final int BREAKER_THRESHOLD = 5;
    /** 熔断持续时间（毫秒）。 */
    private static final long BREAKER_MILLIS = 60_000L;
    /** 只读工具结果在同轮内的缓存时长（秒）。 */
    private static final long DEDUP_TTL_SECONDS = 600;

    public GuardedToolProvider(List<Object> toolObjects, AgentAuditService auditService,
                               com.overseas.dorm.agent.stream.AgentStreamBuffer streamBuffer) {
        this.toolObjects = toolObjects;
        this.auditService = auditService;
        this.streamBuffer = streamBuffer;
    }

    /**
     * 生成工具集。
     *
     * <p>用 {@link ToolSpecifications} 从对象的方法上生成 Schema，
     * 用 {@link DefaultToolExecutor} 负责参数绑定与调用，
     * 然后套上本类自己的守卫。这样既复用了框架成熟的部分
     * （JSON Schema 生成、类型转换），又拿到了完全的控制权。
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        for (Object toolObject : toolObjects) {
            List<Method> toolMethods = new ArrayList<>();
            for (Method method : toolObject.getClass().getMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    toolMethods.add(method);
                }
            }
            for (Method method : toolMethods) {
                try {
                    String name = ToolSpecifications.toolNameFrom(method);
                    ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                    ToolExecutor executor = new DefaultToolExecutor(toolObject, method);
                    builder.add(specification, new GuardedToolExecutor(name, executor));
                } catch (Exception e) {
                    // 单个工具注册失败不应导致整个助手不可用，但要留下明确日志
                    log.error("[Agent] 工具注册失败，已跳过 {}.{}: {}",
                            toolObject.getClass().getSimpleName(), method.getName(), e.getMessage());
                }
            }
        }
        return builder.build();
    }

    /**
     * 受控工具执行器。
     */
    private final class GuardedToolExecutor implements ToolExecutor {

        private final String toolName;
        private final ToolExecutor delegate;

        private GuardedToolExecutor(String toolName, ToolExecutor delegate) {
            this.toolName = toolName;
            this.delegate = delegate;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            return withTurnContext(memoryId,
                    () -> guardedExecute(request, () -> delegate.execute(request, memoryId)));
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
            Object memoryId = context == null ? null : context.chatMemoryId();
            ToolExecutionResult[] holder = new ToolExecutionResult[1];
            String text = withTurnContext(memoryId, () -> guardedExecute(request, () -> {
                ToolExecutionResult result = delegate.executeWithContext(request, context);
                holder[0] = result;
                return result.resultText();
            }));
            ToolExecutionResult original = holder[0];
            if (original != null) {
                return original;
            }
            return ToolExecutionResult.builder().isError(false).resultText(text).build();
        }

        /**
         * 在「发起本轮对话的用户 / 链路 / 轮次」上下文里执行工具。
         *
         * 工具不是在我们发起调用的那个线程上执行的，而是在模型 HTTP 客户端的读流线程里
         * （本项目已固定为 {@code agent-model-stream-*} 虚拟线程，见
         * <pre>
         *   - UserContext 为空 → 工具里的 UserContext.require() 会抛 401；
         *   - AgentTurnContext 为空 → 工具审计的 conversationId 记成 0、turnId 记成 null，
         *     「同轮重复调用去重」因为拿不到 turnId 而彻底失效；
         *   - TraceContext 为空 → 日志里的 traceId 串不上，排查时断链。
         * </pre>
         * 这里通过 chatMemoryId（等于 conversationId）从注册表取回上下文并显式恢复，
         * 保证工具在"正确的身份"下运行，这也是「AI 绝不越权」能够成立的前提：
         * 工具用的身份来自服务端注册表，而不是模型提供的任何参数。
         */
        private String withTurnContext(Object memoryId, java.util.function.Supplier<String> action) {
            AgentTurnContext resolved = AgentTurnContext.byConversation(asLong(memoryId));
            if (resolved == null) {
                resolved = AgentTurnContext.current();
            }
            final AgentTurnContext context = resolved;
            if (context == null) {
                // 取不到上下文意味着工具会以"未登录"状态运行：审计丢失、且 UserContext.require() 会抛 401。
                // 这类问题必须显式暴露，不能静默降级 —— 否则表现为"AI 偶尔查不到数据"，
                // 排查成本极高。
                log.warn("[Agent] 工具 {} 执行时未取到轮次上下文（memoryId={}），"
                                + "将以无身份状态运行，审计与权限校验可能不完整",
                        toolName, memoryId);
                return action.get();
            }
            return AgentTurnContext.runAs(context, () -> TraceContext.runWith(context.traceId(),
                    () -> com.overseas.dorm.common.security.UserContext.runAs(context.user(), action)));
        }

        private Long asLong(Object value) {
            if (value instanceof Long l) {
                return l;
            }
            if (value instanceof Number n) {
                return n.longValue();
            }
            if (value instanceof String s) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        /**
         * 统一的守卫执行流程。
         */
        private String guardedExecute(ToolExecutionRequest request, java.util.function.Supplier<String> action) {
            long start = System.currentTimeMillis();
            String arguments = request.arguments();
            boolean readOnly = ToolCatalog.isReadOnly(toolName);

            // ---- 0. 用户已停止：任何工具都不再执行（尤其是写工具）----
            // 放在最前面：用户喊停优先于熔断/去重等一切优化。
            // 只读工具也不执行 —— 结果已经没人要了，白花上游与数据库的时间。
            com.overseas.dorm.agent.audit.AgentTurnContext turn =
                    com.overseas.dorm.agent.audit.AgentTurnContext.current();
            if (turn != null && turn.messageId() != null && streamBuffer.isCancelled(turn.messageId())) {
                String message = "用户已停止本轮生成，工具 %s 未执行".formatted(toolName);
                log.info("[Agent] 本轮已被用户停止，跳过工具执行 tool={} messageId={}",
                        toolName, turn.messageId());
                auditService.recordToolCall(toolName, arguments, message, false, message,
                        true, false, System.currentTimeMillis() - start);
                return hintJson(false, message,
                        "本轮已被用户停止。不要继续调用任何工具，直接结束回答。");
            }

            // ---- 1. 熔断快速失败 ----
            AtomicLong until = breakerUntil.get(toolName);
            if (until != null && System.currentTimeMillis() < until.get()) {
                long remain = (until.get() - System.currentTimeMillis()) / 1000;
                String message = "工具 %s 暂时不可用（连续失败已熔断，约 %d 秒后恢复）".formatted(toolName, remain);
                log.warn("[Agent] 工具 {} 处于熔断状态，快速失败", toolName);
                auditService.recordToolCall(toolName, arguments, message, false, message,
                        true, false, System.currentTimeMillis() - start);
                return hintJson(false, message,
                        "该功能暂时不可用。请如实告知用户稍后再试或换一种方式，不要反复重试。");
            }

            // ---- 2. 同轮去重（仅只读工具） ----
            if (readOnly) {
                String cached = auditService.lookupToolResult(toolName, arguments);
                if (cached != null) {
                    auditService.recordToolCall(toolName, arguments, cached, true, null,
                            true, true, System.currentTimeMillis() - start);
                    return cached;
                }
            }

            // ---- 3. 执行（只读工具允许一次瞬时故障重试） ----
            String result = null;
            Exception lastError = null;
            int attempts = readOnly ? 2 : 1;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    result = action.get();
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    boolean transient_ = isTransient(e);
                    if (attempt < attempts && transient_) {
                        log.warn("[Agent] 工具 {} 第 {} 次调用遇到瞬时故障，准备重试：{}",
                                toolName, attempt, e.getMessage());
                        sleepQuietly(120L * attempt);
                        continue;
                    }
                    break;
                }
            }

            long cost = System.currentTimeMillis() - start;

            // ---- 4. 成功 ----
            if (lastError == null) {
                failures.remove(toolName);
                breakerUntil.remove(toolName);
                if (readOnly) {
                    auditService.cacheToolResult(toolName, arguments, result, DEDUP_TTL_SECONDS);
                }
                auditService.recordToolCall(toolName, arguments, result, true, null, true, false, cost);
                return result;
            }

            // ---- 5. 失败：区分业务异常与系统异常 ----
            failures.computeIfAbsent(toolName, k -> new AtomicInteger()).incrementAndGet();
            int failureCount = failures.get(toolName).get();
            if (failureCount >= BREAKER_THRESHOLD) {
                breakerUntil.put(toolName, new AtomicLong(System.currentTimeMillis() + BREAKER_MILLIS));
                log.error("[Agent] 工具 {} 连续失败 {} 次，已熔断 {} 秒", toolName, failureCount,
                        BREAKER_MILLIS / 1000);
            }

            if (lastError instanceof BizException biz) {
                // 业务异常：把「给模型的纠偏提示」回灌，让模型换条路走
                boolean permissionDenied = biz.getResultCode() != null
                        && biz.getResultCode().name().contains("PERMISSION");
                auditService.recordToolCall(toolName, arguments, null, false, biz.getMessage(),
                        !permissionDenied, false, cost);
                return hintJson(false, biz.getMessage(), biz.toAgentHint());
            }

            auditService.recordToolCall(toolName, arguments, null, false,
                    lastError.getClass().getSimpleName() + ": " + lastError.getMessage(),
                    true, false, cost);
            log.error("[Agent] 工具 {} 执行异常", toolName, lastError);
            return hintJson(false, "工具执行失败",
                    "该操作暂时无法完成（系统内部错误）。请如实告知用户稍后再试，"
                            + "不要猜测数据、不要重试同样的调用。");
        }

        private boolean isTransient(Exception e) {
            if (e instanceof BizException) {
                return false;
            }
            String name = e.getClass().getName().toLowerCase();
            return name.contains("sql") || name.contains("timeout") || name.contains("connect")
                    || name.contains("socket") || name.contains("redis");
        }

        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 把错误渲染成给模型看的结构化提示。
         *
         * <p>刻意返回 JSON 而不是纯文本：模型对结构化输入的遵循度更高，
         * 而且前端也能识别这个结构，把"需要用户注意"的信息渲染成提示条，
         * 而不是混在回答正文里。
         */
        private String hintJson(boolean success, String message, String agentHint) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", success);
            payload.put("message", message);
            payload.put("agentHint", agentHint);
            AgentTurnContext context = AgentTurnContext.current();
            if (context != null) {
                payload.put("turnId", context.turnId());
            }
            return JsonUtil.toJson(payload);
        }
    }
}
