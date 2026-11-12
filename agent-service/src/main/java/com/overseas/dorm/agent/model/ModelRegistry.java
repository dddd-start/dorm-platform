package com.overseas.dorm.agent.model;

import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.vo.ModelHealthVO;
import com.overseas.dorm.agent.config.LangChain4jConfig;
import com.overseas.dorm.common.enums.AgentType;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型注册表：负责「选哪个模型」以及「主模型挂了怎么办」。
 *
 * <p>这是「主模型或主服务故障时如何自动切换」的落地点。实现上有三个要点：
 *
 * <h3>1. 候选链而不是单点</h3>
 * 每个请求都拿到一个<b>有序候选列表</b>（主模型 → 降级链），
 * 调用方按顺序尝试，第一个成功的胜出。这样"切换"是数据驱动的，
 * 不需要在业务代码里写 if-else。
 *
 * <h3>2. 熔断而不是无脑重试</h3>
 * 如果主模型已经连续失败 N 次，那么后续请求<b>直接跳过它</b>。
 * 否则每次请求都要先等一个必然超时的调用，用户看到的就是"系统很慢"。
 * 熔断打开一段时间后半开探测，成功即恢复。
 *
 * <h3>3. 可观测</h3>
 * 每次选择都记录「实际用的是哪个模型、是否降级、为什么」，
 * 这些信息会随 SSE 事件返回前端并写入审计 ——
 * 「回答质量突然变差」时，第一个要查的就是是不是悄悄降级到了小模型。
 */
@Slf4j
@Component
public class ModelRegistry {

    /** 毫秒与秒的换算常量（避免正文里出现裸的 1000）。 */
    private static final long MILLIS_PER_SECOND = 1000L;

    private final AgentProperties properties;
    private final LangChain4jConfig.ModelFactory factory;

    /** 模型名 -> 连续失败次数。 */
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    /** 模型名 -> 熔断打开截止时间（毫秒时间戳）。 */
    private final Map<String, AtomicLong> breakerOpenUntil = new ConcurrentHashMap<>();
    /** 模型名 -> 累计成功/失败次数（运维展示）。 */
    private final Map<String, AtomicLong> successCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failureCount = new ConcurrentHashMap<>();

    public ModelRegistry(AgentProperties properties, LangChain4jConfig.ModelFactory factory) {
        this.properties = properties;
        this.factory = factory;
    }

    /** 一次模型选择的结论。 */
    public record ModelChoice(String modelName, boolean degraded, String reason) {
    }

    /**
     * 计算某类 Agent 的候选模型链。
     *
     * @param agentType   专家 Agent 类型（可为其单独配置模型）
     * @param preferFast  是否为轻量任务（用快模型打头）
     * @return 有序候选（已过滤熔断中的模型；若全部熔断则返回完整链兜底）
     */
    public List<String> candidateChain(AgentType agentType, boolean preferFast) {
        AgentProperties.Model m = properties.getModel();
        List<String> chain = new ArrayList<>();

        // 1. 该 Agent 的专属模型
        String routed = m.getRouting().get(agentType == null ? "" : agentType.name());
        if (routed != null && !routed.isBlank()) {
            chain.add(routed);
        }
        // 2. 轻量任务优先用快模型
        if (preferFast) {
            chain.add(m.getFast());
        }
        // 3. 主模型
        chain.add(m.getPrimary());
        // 4. 降级链
        chain.addAll(m.getFallback());

        List<String> distinct = chain.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        List<String> available = distinct.stream().filter(this::isAvailable).toList();
        // 全部熔断时不再过滤：宁可试一次，也不要直接对用户报错
        return available.isEmpty() ? distinct : available;
    }

    /**
     * 选择模型（不实际调用，只做决策）。
     *
     * @return 首选模型；{@code degraded=true} 表示首选并非链首（说明发生了降级）
     */
    public ModelChoice select(AgentType agentType, boolean preferFast) {
        List<String> chain = candidateChain(agentType, preferFast);
        if (chain.isEmpty()) {
            throw new IllegalStateException("没有可用的 AI 模型，请检查 dorm.agent.model 配置");
        }
        String expected = preferFast ? properties.getModel().getFast() : properties.getModel().getPrimary();
        String routed = agentType == null ? null : properties.getModel().getRouting().get(agentType.name());
        String ideal = routed != null && !routed.isBlank() ? routed : expected;

        String chosen = chain.get(0);
        boolean degraded = !chosen.equals(ideal);
        String reason = degraded
                ? "首选模型 %s 当前不可用（熔断中或已失败），已降级到 %s".formatted(ideal, chosen)
                : null;
        if (degraded) {
            log.warn("[模型路由] {} 降级：{} -> {}", agentType, ideal, chosen);
        }
        return new ModelChoice(chosen, degraded, reason);
    }

    public ChatModel chatModel(String modelName) {
        return factory.chat(modelName);
    }

    public StreamingChatModel streamingModel(String modelName) {
        return factory.streaming(modelName);
    }

    /** 模型是否可用（未处于熔断打开状态）。 */
    public boolean isAvailable(String modelName) {
        AtomicLong until = breakerOpenUntil.get(modelName);
        if (until == null) {
            return true;
        }
        if (System.currentTimeMillis() >= until.get()) {
            // 半开：允许试探一次
            return true;
        }
        return false;
    }

    public void reportSuccess(String modelName) {
        failures.computeIfAbsent(modelName, k -> new AtomicInteger()).set(0);
        breakerOpenUntil.remove(modelName);
        successCount.computeIfAbsent(modelName, k -> new AtomicLong()).incrementAndGet();
    }

    public void reportFailure(String modelName, Throwable error) {
        int count = failures.computeIfAbsent(modelName, k -> new AtomicInteger()).incrementAndGet();
        failureCount.computeIfAbsent(modelName, k -> new AtomicLong()).incrementAndGet();
        if (count >= properties.getModel().getBreakerFailureThreshold()) {
            long openUntil = System.currentTimeMillis()
                    + properties.getModel().getBreakerHalfOpenSeconds() * 1000L;
            breakerOpenUntil.put(modelName, new AtomicLong(openUntil));
            log.error("[模型熔断] {} 连续失败 {} 次，熔断 {} 秒。最后错误：{}",
                    modelName, count, properties.getModel().getBreakerHalfOpenSeconds(),
                    error == null ? "-" : error.getMessage());
        }
    }

    /** 运维视图：每个模型的健康状态与用量。 */
    public List<ModelHealthVO> health() {
        List<ModelHealthVO> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : factory.modelChain().entrySet()) {
            String model = entry.getValue();
            AtomicLong until = breakerOpenUntil.get(model);
            long remaining = until == null ? 0L
                    : Math.max(0L, (until.get() - now) / MILLIS_PER_SECOND);
            result.add(new ModelHealthVO(
                    entry.getKey(),
                    model,
                    isAvailable(model),
                    remaining > 0,
                    remaining,
                    successCount.getOrDefault(model, new AtomicLong()).get(),
                    failureCount.getOrDefault(model, new AtomicLong()).get(),
                    failures.getOrDefault(model, new AtomicInteger()).get()));
        }
        return result;
    }
}
