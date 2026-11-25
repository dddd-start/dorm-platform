package com.overseas.dorm.agent.aiservice;

import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.config.LangChain4jConfig;
import com.overseas.dorm.agent.memory.DormChatMemoryStore;
import com.overseas.dorm.agent.model.ModelRegistry;
import com.overseas.dorm.agent.tool.ApplyTools;
import com.overseas.dorm.agent.tool.GuardedToolProvider;
import com.overseas.dorm.agent.tool.OrderTools;
import com.overseas.dorm.agent.tool.OpsTools;
import com.overseas.dorm.agent.tool.PolicyTools;
import com.overseas.dorm.agent.tool.ProfileTools;
import com.overseas.dorm.agent.tool.RoomQueryTools;
import com.overseas.dorm.common.enums.AgentType;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册表：把「专家 Agent 类型 × 角色」映射到装配好的 {@link DormAgent} 实例。
 *
 * 本系统采用「Supervisor + 专家」模式，而不是让一个巨型 Agent 处理所有事情：
 * <pre>
 *   用户消息
 *     → AgentRouter 判断意图（房源 / 政策 / 申请 / 订单 / 运维）
 *     → 取出对应的专家 Agent（各自独立的系统提示词 + 工具子集 + 模型）
 *     → 由它完成这一轮对话
 * </pre>
 * 这样做有三个实际收益：
 * <ol>
 *   <li><b>工具集更小</b>：政策顾问只挂 2 个工具，模型选错工具的概率显著下降；</li>
 *   <li><b>提示词更聚焦</b>：每个专家只需要写清楚自己那一段职责；</li>
 *   <li><b>模型可差异化</b>：可以让政策问答用更强的模型、订单查询用更快的模型
 *       （见 {@code dorm.agent.model.routing}）。</li>
 * </ol>
 *
 * 员工与管理员可用的工具集不同（管理员多一组运维工具）。
 * 与其在运行时判断"该不该挂这个工具"，不如在<b>装配期</b>就分好，* 员工角色的模型根本看不到运维工具的存在，从源头上消除了越权尝试的可能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRegistry {

    private final AgentProperties properties;
    private final ModelRegistry modelRegistry;
    private final LangChain4jConfig.ModelFactory modelFactory;
    private final DormChatMemoryStore chatMemoryStore;
    private final SystemPromptBuilder promptBuilder;
    private final AgentAuditService auditService;
    /** 传给 GuardedToolProvider：用户已停止时不再执行任何工具（见该类的说明）。 */
    private final com.overseas.dorm.agent.stream.AgentStreamBuffer streamBuffer;

    private final RoomQueryTools roomQueryTools;
    private final ApplyTools applyTools;
    private final OrderTools orderTools;
    private final PolicyTools policyTools;
    private final ProfileTools profileTools;
    private final OpsTools opsTools;

    /** key = AgentType + "#" + (admin ? "ADMIN" : "EMPLOYEE")。 */
    private final Map<String, DormAgent> cache = new ConcurrentHashMap<>();

    /**
     * 取得专家 Agent。
     *
     * @param type  专家类型
     * @param admin 是否管理员（决定是否挂载运维工具）
     */
    public DormAgent get(AgentType type, boolean admin) {
        String key = type.name() + "#" + (admin ? "ADMIN" : "EMPLOYEE");
        return cache.computeIfAbsent(key, k -> build(type, admin));
    }

    private DormAgent build(AgentType type, boolean admin) {
        List<Object> tools = toolsFor(type, admin);
        // 模型选择：路由/摘要类轻任务用快模型，其余用主模型（可按 AgentType 配置覆盖）
        boolean preferFast = type == AgentType.ROUTER;
        ModelRegistry.ModelChoice choice = modelRegistry.select(type, preferFast);
        // 完整候选链：主模型 -> 后续降级模型。
        // 关键点：降级不是"选择阶段"的行为，而是"每一次真实调用"周围的包装，
        // 否则主模型返回 429 时就只会把错误抛给用户（见 FailoverStreamingChatModel 的说明）。
        List<String> chain = modelRegistry.candidateChain(type, preferFast);
        boolean failover = properties.getModel().isFailoverEnabled();

        GuardedToolProvider toolProvider = new GuardedToolProvider(tools, auditService, streamBuffer);

        dev.langchain4j.model.chat.StreamingChatModel streamingModel;
        dev.langchain4j.model.chat.ChatModel chatModel;
        if (properties.getMock().isEnabled()) {
            // 压测模式：使用本地模拟模型，不消耗任何模型额度，延迟确定可复现。
            // 这里把实际生效的参数打出来：压测脚本常通过命令行覆盖这些值，
            // 一旦覆盖没生效（例如属性名写错），压测会"跑得飞快但什么也没压到"——
            // 把参数写进日志能让这种情况立刻暴露，而不是靠事后算耗时去猜。
            log.warn("[Agent] 压测模拟模型生效：model={} chunkSize={} delayMillis={} async={} "
                            + "（单轮理论耗时 ≈ 回答字数/chunkSize×delayMillis；"
                            + "async=false 时流在调用线程同步跑完，超时收口逻辑不可达）",
                    "mock/streaming", properties.getMock().getChunkSize(),
                    properties.getMock().getDelayMs(), properties.getMock().isAsyncStreaming());
            streamingModel = new com.overseas.dorm.agent.model.MockModelFactory.MockStreamingChatModel(
                    properties.getMock().getChunkSize(), properties.getMock().getDelayMs(),
                    properties.getMock().isAsyncStreaming());
            chatModel = new com.overseas.dorm.agent.model.MockModelFactory.MockChatModel();
        } else {
            streamingModel = failover
                    ? new com.overseas.dorm.agent.model.FailoverStreamingChatModel(chain, modelFactory,
                            modelRegistry, type.name())
                    : modelRegistry.streamingModel(choice.modelName());
            chatModel = failover
                    ? new com.overseas.dorm.agent.model.FailoverChatModel(chain, modelFactory,
                            modelRegistry, type.name())
                    : modelRegistry.chatModel(choice.modelName());
        }

        DormAgent agent = AiServices.builder(DormAgent.class)
                .streamingChatModel(streamingModel)
                .chatModel(chatModel)
                // 系统提示词按请求动态生成（注入当前用户身份与日期）
                .systemMessageProviderWithContext(invocation -> {
                    LoginUser user = UserContext.get();
                    return promptBuilder.build(type, user);
                })
                // 记忆：窗口式短期记忆 + MySQL 存储（多实例共享、重启不丢）
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(properties.getMemory().getWindowSize())
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .toolProvider(toolProvider)
                // 防死循环：两道闸门
                //   1) 单轮内连续工具调用次数上限
                //   2) 工具调用往返轮数上限（模型可能"多轮反复调用不同工具"）
                // 弱模型很常见的行为是反复查同一个东西，只设第一道闸门挡不住它。
                .maxSequentialToolsInvocations(properties.getModel().getMaxToolInvocations())
                .maxToolCallingRoundTrips(properties.getModel().getMaxToolRoundTrips())
                // 工具执行异常统一处理：本项目在 GuardedToolProvider 内部已把异常
                // 转成给模型的提示文本，这里再兜一层，确保任何漏网异常也不会打断整轮对话
                .toolExecutionErrorHandler((error, context) -> ToolErrorHandlerResult.text(
                        "{\"success\":false,\"message\":\"工具执行异常\","
                                + "\"agentHint\":\"该操作暂时无法完成，请如实告知用户稍后再试，不要编造结果。\"}"))
                // 参数不合法时也不要让框架把原始异常（可能含内部类名/堆栈）回灌给模型。
                // 默认行为是 sendExceptionMessageToLlm，在真实 API 联调里会出现
                // “模型照着 Java 异常向用户解释”的怪异回答。
                .toolArgumentsErrorHandler((error, context) -> ToolErrorHandlerResult.text(
                        "{\"success\":false,\"message\":\"工具参数不合法\","
                                + "\"agentHint\":\"请检查工具参数的格式与必填项后重试；"
                                + "如果无法确定参数，先向用户追问，不要编造参数。\"}"))
                // 模型幻觉出不存在的工具名时，返回明确提示而不是抛异常
                .hallucinatedToolNameStrategy(request -> dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                        request,
                        "{\"success\":false,\"message\":\"工具 " + request.name() + " 不存在\","
                                + "\"agentHint\":\"不存在这个工具，请只使用工具清单中列出的工具；"
                                + "如果没有合适的工具，就直接用已有信息回答用户。\"}"))
                // 并发执行工具会让"同轮去重"与 ThreadLocal 上下文失效，因此保持串行
                .build();

        log.info("[Agent] 已装配专家 Agent：type={} scope={} model={}{} tools={}",
                type, admin ? "ADMIN" : "EMPLOYEE",
                properties.getMock().isEnabled() ? "MOCK(压测模式)" : choice.modelName(),
                choice.degraded() && !properties.getMock().isEnabled() ? "（已降级）" : "", tools.size());
        return agent;
    }

    /**
     * 按专家类型与角色决定工具子集。
     *
     * <p>工具集的分配遵循"最小必要"：每个专家只拿到完成自己职责所需的工具。
     * 例如政策顾问拿不到 {@code createStayApply}，
     * 因此即便它在某轮对话里被诱导去"提交申请"，也无从下手。
     */
    private List<Object> toolsFor(AgentType type, boolean admin) {
        List<Object> tools = new ArrayList<>();
        switch (type) {
            case ROOM -> {
                tools.add(roomQueryTools);
                tools.add(policyTools);
            }
            case POLICY -> {
                tools.add(policyTools);
                tools.add(roomQueryTools);
            }
            case APPLY -> {
                tools.add(applyTools);
                tools.add(roomQueryTools);
                tools.add(policyTools);
            }
            case ORDER -> {
                tools.add(orderTools);
                tools.add(applyTools);
                tools.add(policyTools);
            }
            case OPS -> {
                tools.add(opsTools);
                tools.add(roomQueryTools);
                tools.add(applyTools);
                tools.add(orderTools);
                tools.add(policyTools);
            }
            case ROUTER -> tools.add(roomQueryTools);
            default -> tools.add(roomQueryTools);
        }
        // 所有角色都能查询自己的资料与记忆
        tools.add(profileTools);
        // 运维工具只对管理员开放 —— 员工角色的模型完全看不到它们
        if (admin) {
            tools.add(opsTools);
        }
        return tools;
    }

    /** 已装配的 Agent（运维展示用）。 */
    public List<String> assembled() {
        return cache.keySet().stream().sorted().toList();
    }
}
