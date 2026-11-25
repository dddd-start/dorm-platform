package com.overseas.dorm.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Agent 配置。
 *
 * <p>所有可调参数集中在这里，目的很实际：模型、阈值、限流这些值
 * 上线后几乎一定会被调（成本、延迟、效果三者互相拉扯），
 * 如果散落在代码里，每次调参都要改代码、重新发版。
 */
@Data
@ConfigurationProperties(prefix = "dorm.agent")
public class AgentProperties {

    /** 总开关：关闭后所有 Agent 接口返回 6000，业务功能不受影响。 */
    private boolean enabled = true;

    private Provider provider = new Provider();
    private Model model = new Model();
    private Memory memory = new Memory();
    private Stream stream = new Stream();
    private Ratelimit ratelimit = new Ratelimit();
    private Guardrail guardrail = new Guardrail();
    private Rag rag = new Rag();
    private Mock mock = new Mock();

    /**
     * 模型服务提供方配置。
     *
     * <p>本项目对接 Command Code（OpenAI 兼容协议）。
     * 换成私有化部署（vLLM / Ollama / 一体机）时只需改 base-url 与模型名，
     * 代码零改动，这是「私有部署 vs 外部 API」这道选择题的技术前提。
     */
    @Data
    public static class Provider {
        private String baseUrl = "https://api.commandcode.ai/provider/v1";
        private String apiKey;
        private int timeoutSeconds = 120;
        private int maxRetries = 1;
        private boolean logRequests = false;
        private boolean logResponses = false;
    }

    /**
     * 模型策略。
     *
     * <p>三级结构，对应三类任务：
     * <ul>
     *   <li>{@link #primary}，主对话模型（效果优先）；</li>
     *   <li>{@link #fast}，意图路由、摘要、标题生成（延迟/成本优先）；</li>
     *   <li>{@link #fallback}，主模型不可用时的降级链，按顺序尝试。</li>
     * </ul>
     */
    @Data
    public static class Model {
        private String primary = "deepseek/deepseek-v4.1-flash";
        private String fast = "deepseek/deepseek-v4.1-flash";
        private List<String> fallback = new ArrayList<>(List.of(
                "deepseek/deepseek-v4.1-flash",
                "deepseek/deepseek-v4.1-flash",
                "deepseek/deepseek-v4.1-flash"));
        private Double temperature = 0.2;
        private Integer maxTokens = 2048;
        /** 单轮内允许的最大连续工具调用次数，防死循环。 */
        private int maxToolInvocations = 6;
        /** 单轮内允许的最大工具调用往返轮数（模型反复换工具查询时的第二道闸门）。 */
        private int maxToolRoundTrips = 3;

        /**
         * 上游模型的最大并发调用数（保护模型服务商）。
         *
         * <p>与 {@code dorm.agent.stream.max-streams-global} 刻意解耦：
         * 前者是"容器能容纳多少条并发流"，后者是"我们向上游同时发多少请求"。
         * 第三方模型按账号限流，把自己的容量当成上游的容量会导致
         * 429 → 熔断 → 降级链切换 → 调用量进一步放大的正反馈，
         * 最终把账号打到被临时封禁（本项目实测到 490 次 429、482 次熔断）。
         *
         * <p>默认值偏保守（免费额度模型的典型配额是每分钟几十次），生产按实际配额调整。
         */
        private int maxConcurrentCalls = 16;
        /**
         * 单轮总时限（秒）：超时由业务层主动收口，给用户一句"生成超时，已中断"。
         *
         * <p><b>它必须小于 {@code dorm.agent.stream.generation-timeout-seconds}</b>
         * （模型 HTTP 客户端超时）。关系是：
         * <pre>
         *   业务收口（本值，90s）→ 先触发 → 用户看到可读提示 + 并发额度被正确释放
         *   客户端超时（120s）   → 兜底   → 只有当业务收口没生效时才会走到这里
         * </pre>
         * 如果反过来配（业务收口 &gt; 客户端超时），用户看到的会是底层连接超时错误，
         * 而且并发计数要等隔离线程跑完才释放。因此启动时会校验这个大小关系并直接拒绝启动。
         */
        private int turnTimeoutSeconds = 90;
        /** 每个专家 Agent 的模型覆盖（不配置则用 primary）。 */
        private Map<String, String> routing = new LinkedHashMap<>();
        /** 是否在模型调用失败时自动切换下一个模型。 */
        private boolean failoverEnabled = true;
        /**
         * 熔断阈值：连续失败多少次后把该模型标记为不可用。
         *
         * <p>这里取 2 是实测调优的结果：Command Code 的免费模型
         * （laguna / ling）上游本身就会间歇性返回 rate_limit_error，
         * 阈值设成 3~5 会让每一次对话都先浪费一次必然失败的调用（多等好几秒）。
         * 阈值 2 + 30 秒半开探测，能在"免费额度能用就用"和"不反复踩坑"之间取得平衡。
         */
        private int breakerFailureThreshold = 2;
        /** 熔断后多久再试探一次（秒）。 */
        private int breakerHalfOpenSeconds = 30;
    }

    /**
     * 记忆策略。
     */
    @Data
    public static class Memory {
        /** 短期记忆窗口（消息条数）。 */
        private int windowSize = 20;
        /** 超过多少轮后触发会话摘要（长期记忆）。 */
        private int summarizeAfterTurns = 12;
        /** 长期记忆条数上限。 */
        private int maxLongTermItems = 20;
    }

    /**
     * 流式输出配置。
     */
    @Data
    public static class Stream {
        /** SSE 心跳间隔（秒）：防止中间代理因空闲断开连接。 */
        private int heartbeatSeconds = 15;
        /** Redis Stream 缓冲的最大条数（MAXLEN），防止单条流撑爆内存。 */
        private int bufferMaxLength = 4000;
        /** 缓冲存活时间（秒）：断线重连的时间窗口。 */
        private int bufferTtlSeconds = 1800;
        /** 每用户最大并发流。 */
        private int maxStreamsPerUser = 3;
        /** 全局最大并发流。 */
        private int maxStreamsGlobal = 200;
        /** 单轮生成超时（秒）。 */
        private int generationTimeoutSeconds = 120;
        /** 是否把每个 token 都写入 Redis（关闭则只在完成时落库，省内存但失去续传能力）。 */
        private boolean durableBuffer = true;

        /**
         * 每条 SSE 连接的出站队列容量（帧数）。
         *
         * <p>这一层是"单容器支撑千级长连接"的关键：生成线程只把帧<b>非阻塞入队</b>，
         * 由连接专属的虚拟线程负责写入 socket。队列满意味着该客户端已经跟不上
         * （弱网、切后台、假死），此时主动断开它并让前端重连续传，* <b>牺牲一条连接，保住其余 999 条</b>，而不是让生产者阻塞在一个慢客户端上。
         *
         * <p>容量按"最坏内存"反推：容量 × 单帧大小 × 连接数。
         * 单帧约 200~500 字节（含 JSON 包装），512 帧 ≈ 100~250KB/连接；
         * 1000 连接最坏约 100~250MB。这是刻意选择的边界：
         * 如果设成无限大，慢客户端会直接把堆吃光（那才是真正的"容器被打挂"）。
         */
        private int channelQueueCapacity = 512;

        /**
         * 判定连接"假死"的阈值（秒）：超过这么久没有任何成功写入，看门狗会断开它。
         *
         * <p>正常生成期间每隔几十毫秒就有帧写出，因此这个阈值只可能被
         * 真正卡住的连接触发。它必须显著大于心跳间隔（默认 15s）。
         */
        private int channelIdleTimeoutSeconds = 90;
    }

    /**
     * 限流与预算。
     */
    @Data
    public static class Ratelimit {
        /** 每用户每分钟允许的对话轮次。 */
        private int turnsPerMinute = 20;
        /** 令牌桶突发容量。 */
        private int burst = 5;
        /** 每用户每日 token 预算；0 表示不限制。 */
        private long dailyTokenBudget = 0;
        /** 超预算时的行为：DEGRADE（降级到快模型）/ REJECT（拒绝）。 */
        private String overBudgetAction = "DEGRADE";
        /**
         * 单条消息允许的最大字符数。
         *
         * <p>此前这个数字硬编码在控制器的 meta 接口里（4000），而真正的长度校验
         * 在护栏里用的是另一个常量，也就是说"界面上写的限制"和"实际生效的限制"
         * 是两份数据。现在统一由这里提供，meta 接口下发真实生效值。
         */
        private int maxMessageLength = 4000;
    }

    /**
     * 内容护栏。
     */
    @Data
    public static class Guardrail {
        private boolean enabled = true;
        /** 流式输出审核的滑动窗口大小（字符）。 */
        private int streamWindowSize = 60;
        /** 命中违规词时是否立即截断。 */
        private boolean blockOnHit = true;
        /** 是否启用提示词注入检测。 */
        private boolean injectionDetection = true;
    }

    /**
     * 压测/离线测试模式。
     *
     * <p>开启后使用本地模拟模型代替真实模型：不消耗任何模型额度、
     * 延迟确定、结果可重复，专门用于压测我们自己的流式链路。
     * <b>绝不能在生产环境开启</b>，那会让助手变成复读机。
     */
    @Data
    public static class Mock {
        private boolean enabled = false;
        /** 每次吐出的字符数。 */
        private int chunkSize = 6;

        /**
         * mock 是否异步流式返回（默认 false，保持与既有压测一致的测量条件）。
         *
         * <p>为什么需要这个开关：默认的 mock 在调用线程里同步跑完整个流
         * （chunk 之间 {@code Thread.sleep}），于是 {@code .start()} 会一直阻塞到生成结束，
         * "生成超时"那段收口代码根本不可达，实测：配置 10 秒超时、mock 跑 177 秒，
         * 超时从未触发；而真实 provider 是异步的（历史上超时触发过 9 次）。
         * 要验证超时/中断这类与生成并发的逻辑，必须让 mock 也异步。
         *
         * <p>刻意不改默认值：改成异步会改变 1000 并发压测的测量条件，
         * 此前记录的压测数字将不再可比。需要时用
         * {@code --dorm.agent.mock.async-streaming=true} 显式开启。
         */
        private boolean asyncStreaming = false;
        /**
         * 每块之间的间隔（毫秒），用于模拟出字速度。
         *
         * <p><b>字段名必须与配置键的"归一化形式"一致</b>，否则会被静默忽略：
         * Spring 的松散绑定会把 {@code delay-ms} 归一化成 {@code delayms}，
         * 而原来的字段名是 {@code delayMillis}（归一化后 {@code delaymillis}），
         * 两者对不上，于是 {@code dorm.agent.mock.delay-ms} 这个配置项
         * <b>一直没生效</b>，字段始终是默认值 15。
         *
         * 与默认值相同，"看起来一切正常"。直到压测脚本用命令行把它覆盖成 1500
         * 却发现单轮耗时依旧是 1 秒多，才暴露出来。
         *
         * <p>教训：<b>配置项生效与否必须可观测</b>。本项目的做法是在装配模型时
         * 把实际生效的参数打进日志（见 {@code AgentRegistry}），
         * 这样"改了配置却没反应"会立刻暴露，而不是靠事后算耗时去猜。
         */
        private long delayMs = 15;
    }

    /**
     * RAG 配置。
     */
    @Data
    public static class Rag {
        private boolean enabled = true;
        private int topK = 5;
        private double minScore = 0.55;
        private int chunkSize = 400;
        private int chunkOverlap = 60;
        private int embeddingCacheTtlSeconds = 86400;
        private int retrievalCacheTtlSeconds = 300;
        private boolean rebuildOnStartup = true;
        /** 知识库文件位置（classpath 模式）。 */
        private String knowledgeLocation = "classpath:knowledge/*.md";
        /** 是否启用查询改写（用快模型把口语化问题改写成检索友好的查询）。 */
        private boolean queryRewrite = false;
        /** 是否使用 MMR 做结果多样性去重。 */
        private boolean mmrEnabled = true;
        /** MMR 的多样性权重（0-1，越大越强调多样性）。 */
        private double mmrLambda = 0.7;
        /**
         * 检索候选池相对 topK 的倍数。
         *
         * <p>为什么要先取一个更大的候选池再重排：MMR 需要在"与问题相关"和
         * "与已选结果不同"之间取舍，如果候选池就等于 topK，它没有可替换的余地，
         * 去冗余也就无从谈起。倍数越大去冗余效果越好，但参与重排的候选也越多。
         * 这个值此前是写死在代码里的 {@code k * 2}，现在做成可调参数。
         */
        private int candidateMultiplier = 2;

        /**
         * 候选池的最小额外条数（下限保护）。
         *
         * <p>当 topK 很小时（例如 1），{@code topK * 倍数} 仍然很小，
         * 重排几乎没有选择空间。这里保证候选池至少比 topK 多这么多条。
         */
        private int candidateMinExtra = 4;
    }
}
