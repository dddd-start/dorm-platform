package com.overseas.dorm.agent.config;

import com.overseas.dorm.agent.http.VirtualThreadJdkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j 装配。
 *
 * <p><b>为什么不用 langchain4j 的 Spring Boot Starter</b>：
 * Starter 会通过约定自动装配一批 bean，看起来省事，但对学习很不友好，* 「模型是怎么被创建出来的、参数从哪来、什么时候生效」全被藏进了自动配置里。
 * 这里全部手写 {@code @Bean}，让你在启动日志里就能看到每个模型的实际参数。
 *
 * <p>本类只负责<b>造模型</b>，不负责<b>选模型</b>：
 * 选模型（主/快/降级链/熔断）的逻辑在 {@code ModelRegistry}，
 * 这样模型工厂保持无状态、可缓存、易测试。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final AgentProperties properties;

    /**
     * 本地中文嵌入模型。
     *
     * <p>为什么不用外部 Embedding API：Command Code 的 Provider 没有
     * {@code /embeddings} 端点（实测 404）。本地 ONNX 推理反而有三个好处：
     * <ol>
     *   <li>知识库切片向量化不需要出网，合规上更干净（文档不外发）；</li>
     *   <li>没有按次计费，重建索引随便做；</li>
     *   <li>延迟可控（毫秒级），不占用模型配额。</li>
     * </ol>
     * BGE-small-zh-v1.5 是 512 维、量化版约 24MB，模型文件直接内嵌在 jar 里，
     * 不需要首次运行时下载（这在离线环境里非常关键）。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("[LangChain4j] 初始化本地嵌入模型 BGE-small-zh-v1.5（ONNX 量化版，512 维）");
        return new BgeSmallZhV15QuantizedEmbeddingModel();
    }

    /**
     * 主对话模型（同步）。
     *
     * <p>用途：意图路由、摘要生成、结构化输出（不需要流式的场景）。
     * 流式对话用 {@link #primaryStreamingChatModel()}。
     */
    @Bean("primaryChatModel")
    public ChatModel primaryChatModel(ModelFactory modelFactory) {
        String model = properties.getModel().getPrimary();
        log.info("[LangChain4j] 主对话模型 = {} @ {}", model, properties.getProvider().getBaseUrl());
        return modelFactory.chat(model);
    }

    /** 主对话模型（流式）。 */
    @Bean("primaryStreamingChatModel")
    public StreamingChatModel primaryStreamingChatModel(ModelFactory modelFactory) {
        String model = properties.getModel().getPrimary();
        log.info("[LangChain4j] 主对话流式模型 = {}", model);
        return modelFactory.streaming(model);
    }

    /**
     * 快模型：意图路由、标题生成、摘要这类"轻任务"。
     *
     * <p>为什么要把轻任务拆出去：一次意图路由只需要模型输出一个枚举值，
     * 用主模型是纯粹的浪费，既慢又贵。这是成本控制里
     * 「按任务价值选择模型档位」最直接的体现。
     */
    @Bean("fastChatModel")
    public ChatModel fastChatModel(ModelFactory modelFactory) {
        String model = properties.getModel().getFast();
        log.info("[LangChain4j] 轻量任务模型 = {}", model);
        return modelFactory.chat(model);
    }

    /**
     * 流式 HTTP 客户端：把 SSE 读循环固定到专用虚拟线程。
     *
     * <p>为什么单独一个 bean：连接池和虚拟线程池都只应该有一份；
     * 主模型、快模型、降级链共享同一个客户端，避免每个模型各建一套连接池。
     *
     * <p>为什么不用 LangChain4j 默认的 {@code JdkHttpClient} 流式实现：
     * 它走 {@code sendAsync().thenAccept(...)}，回调落在
     * {@code ForkJoinPool.commonPool}（2 核容器上退化为 thread-per-task），
     * 并发数不再由业务层控制。详见 {@link VirtualThreadJdkHttpClient}。
     */
    @Bean(destroyMethod = "close")
    public VirtualThreadJdkHttpClient streamingHttpClient() {
        AgentProperties.Provider p = properties.getProvider();
        return VirtualThreadJdkHttpClient.builder()
                // 建连超时没必要跟生成超时一样长：15 秒还连不上，降级/重试更划算
                .connectTimeout(Duration.ofSeconds(Math.min(p.getTimeoutSeconds(), 15)))
                // 读超时与单轮生成超时一致：上游一直不吐 token 时，读线程最多活这么久
                .readTimeout(Duration.ofSeconds(properties.getStream().getGenerationTimeoutSeconds()))
                .build();
    }

    /**
     * 模型工厂：按名称产出 ChatModel，带缓存。
     *
     * <p>缓存是必要的，每次请求都新建一个 HTTP 客户端会迅速耗尽连接池；
     * 而 {@code OpenAiChatModel} 内部持有连接池，必须复用。
     */
    @Bean
    public ModelFactory modelFactory(VirtualThreadJdkHttpClient streamingHttpClient) {
        if (properties.getMock().isEnabled()) {
            log.warn("[LangChain4j] ⚠ 当前处于【压测模拟模式】(dorm.agent.mock.enabled=true)："
                    + "所有对话都由本地模拟模型应答，不会调用真实大模型。"
                    + "此模式仅用于压测与离线测试，生产环境必须关闭！");
        }
        return new ModelFactory(properties, streamingHttpClient);
    }

    /**
     * 模型工厂实现。
     *
     * <p>注意 {@code buildChatModel} 与 {@code buildStreamingChatModel} 用的是
     * 两套独立的缓存：同一个模型名在两种协议下是不同的客户端对象，
     * 混用会导致「同步调用拿到了流式模型」这类隐蔽错误。
     */
    public static class ModelFactory {

        private final AgentProperties props;
        private final VirtualThreadJdkHttpClient streamingHttpClient;
        private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
        private final Map<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

        public ModelFactory(AgentProperties props, VirtualThreadJdkHttpClient streamingHttpClient) {
            this.props = props;
            this.streamingHttpClient = streamingHttpClient;
        }

        public ChatModel chat(String modelName) {
            return chatCache.computeIfAbsent(modelName, this::buildChat);
        }

        public StreamingChatModel streaming(String modelName) {
            return streamingCache.computeIfAbsent(modelName, this::buildStreaming);
        }

        private ChatModel buildChat(String modelName) {
            AgentProperties.Provider p = props.getProvider();
            return OpenAiChatModel.builder()
                    .baseUrl(p.getBaseUrl())
                    .apiKey(p.getApiKey())
                    .modelName(modelName)
                    .temperature(props.getModel().getTemperature())
                    .maxTokens(props.getModel().getMaxTokens())
                    .timeout(Duration.ofSeconds(p.getTimeoutSeconds()))
                    .maxRetries(p.getMaxRetries())
                    .logRequests(p.isLogRequests())
                    .logResponses(p.isLogResponses())
                    // 关键：把模型的思考过程取回来。DeepSeek / Ling 等推理模型会返回
                    // reasoning_content，不开启这个开关，思考内容会被静默丢弃。
                    .returnThinking(Boolean.TRUE)
                    // Command Code 不是标准 OpenAI，关闭严格工具模式以提升兼容性
                    .strictTools(Boolean.FALSE)
                    .build();
        }

        private StreamingChatModel buildStreaming(String modelName) {
            AgentProperties.Provider p = props.getProvider();
            return OpenAiStreamingChatModel.builder()
                    .baseUrl(p.getBaseUrl())
                    .apiKey(p.getApiKey())
                    .modelName(modelName)
                    .temperature(props.getModel().getTemperature())
                    .maxTokens(props.getModel().getMaxTokens())
                    .timeout(Duration.ofSeconds(props.getStream().getGenerationTimeoutSeconds()))
                    // 关键：流式读循环用专用虚拟线程，而不是 CompletableFuture 的默认公共池
                    .httpClientBuilder(streamingHttpClient.asBuilder())
                    .logRequests(p.isLogRequests())
                    .logResponses(p.isLogResponses())
                    .returnThinking(Boolean.TRUE)
                    // 流式工具调用时，把分片的 tool_call id 累积起来
                    .accumulateToolCallId(Boolean.TRUE)
                    .strictTools(Boolean.FALSE)
                    .build();
        }

        /** 全部已构建的模型名（用于运维展示与健康检查）。 */
        public List<String> knownModels() {
            List<String> all = new ArrayList<>();
            all.add(props.getModel().getPrimary());
            all.add(props.getModel().getFast());
            all.addAll(props.getModel().getFallback());
            return all.stream().distinct().toList();
        }

        /** 主模型 + 降级链，按顺序排列。 */
        public Map<String, String> modelChain() {
            Map<String, String> chain = new LinkedHashMap<>();
            chain.put("primary", props.getModel().getPrimary());
            chain.put("fast", props.getModel().getFast());
            for (int i = 0; i < props.getModel().getFallback().size(); i++) {
                chain.put("fallback-" + (i + 1), props.getModel().getFallback().get(i));
            }
            return chain;
        }
    }
}
