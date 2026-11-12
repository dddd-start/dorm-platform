package com.overseas.dorm.agent.rag;

import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.enums.FeedbackRating;
import com.overseas.dorm.common.enums.DocCategory;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.common.util.CryptoUtil;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.agent.vo.RagIndexStatusVO;
import com.overseas.dorm.agent.vo.RagQualityVO;
import com.overseas.dorm.domain.vo.RagQualityMetricsVO;
import com.overseas.dorm.domain.entity.RagRetrievalLog;
import com.overseas.dorm.domain.mapper.RagRetrievalLogMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索服务。
 *
 * <h3>一次完整检索做了什么</h3>
 * <pre>
 *   查询文本
 *     → 1. 结果缓存查找（同一问题高并发时直接复用）        [命中则跳过 2-5]
 *     → 2. 向量化（带嵌入缓存）
 *     → 3. 向量检索（TopK × 2 候选，为 MMR 留出候选池）
 *     → 4. 元数据过滤（区域隔离：只召回本区域 + 全局文档）
 *     → 5. MMR 重排（去冗余，避免 Top5 全是同一段话的变体）
 *     → 6. 写检索日志（生产效果追踪的数据来源）
 * </pre>
 *
 * <h3>为什么要做 MMR 去冗余</h3>
 * 纯按相似度排序有个典型问题：如果知识库里有一段话被拆成了 3 个相邻切片，
 * 那么这 3 个切片会同时占据 Top3，而真正补充信息的第 4 段内容被挤出。
 * 模型拿到的 3 个切片内容几乎一样，答案自然单薄。
 *
 * <p>MMR（最大边际相关性）在排序时同时考虑「与问题的相关性」与
 * 「与已选结果的差异度」：
 * <pre>
 *   score = λ × sim(片段, 问题) − (1−λ) × max(sim(片段, 已选片段))
 * </pre>
 * λ 越大越偏重相关性，越小越偏重多样性。默认 0.7 是在"别漏掉重点"
 * 和"别重复啰嗦"之间的一个经验平衡点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final KnowledgeBaseLoader knowledgeBaseLoader;
    private final MysqlEmbeddingStore embeddingStore;
    private final RagRetrievalLogMapper retrievalLogMapper;
    private final StringRedisTemplate redis;
    private final AgentProperties properties;

    /** 一条命中结果。 */
    public record Hit(Long chunkId, Long documentId, String docTitle, String docCode, String title,
                      String content, String category, Long regionId, double score) {
    }

    /** 一次检索的完整结果。 */
    public record RetrievalResult(String query, List<Hit> hits, long latencyMs, boolean cacheHit,
                                  int candidateCount) {

        public boolean isEmpty() {
            return hits.isEmpty();
        }

        public List<Long> chunkIds() {
            return hits.stream().map(Hit::chunkId).toList();
        }
    }

    /**
     * 检索知识库。
     *
     * @param query   用户问题
     * @param regionId 区域过滤：只召回该区域与全局文档；null 表示不限
     * @param topK    返回条数（0 表示用配置默认值）
     */
    public RetrievalResult retrieve(String query, Long regionId, int topK, double minScore) {
        long start = System.currentTimeMillis();
        int k = topK > 0 ? topK : properties.getRag().getTopK();
        double score = minScore > 0 ? minScore : properties.getRag().getMinScore();

        if (!properties.getRag().isEnabled() || query == null || query.isBlank()) {
            return new RetrievalResult(query, List.of(), 0, false, 0);
        }

        // 1. 结果缓存
        String cacheKey = RedisKeys.retrievalCache(CryptoUtil.sha256(
                "%s|%s|%d|%.2f|%d".formatted(query, regionId, k, score, embeddingStore.version())));
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                List<Hit> hits = deserializeHits(cached);
                if (hits != null) {
                    long latency = System.currentTimeMillis() - start;
                    logRetrieval(query, hits, latency, true, hits.size());
                    return new RetrievalResult(query, hits, latency, true, hits.size());
                }
            }
        } catch (Exception e) {
            log.debug("[RAG] 读取检索缓存失败：{}", e.getMessage());
        }

        // 2. 向量化（带缓存）
        float[] queryVector = knowledgeBaseLoader.embedWithCache(query);

        // 3. 多取候选，为 MMR 留出池子
        // 候选池 = topK × 倍数，并保证至少比 topK 多 candidateMinExtra 条，
        // 让 MMR 有可替换的余地（此前这里的 2 和 4 是写死的魔法值）
        int multiplier = Math.max(properties.getRag().getCandidateMultiplier(), 1);
        int minExtra = Math.max(properties.getRag().getCandidateMinExtra(), 0);
        int candidateSize = Math.max(k * multiplier, k + minExtra);
        List<EmbeddingMatch<TextSegment>> candidates = embeddingStore.searchByVector(
                queryVector, candidateSize, score, regionId, null);

        // 4. MMR 重排
        List<EmbeddingMatch<TextSegment>> selected = properties.getRag().isMmrEnabled()
                ? mmr(queryVector, candidates, k, properties.getRag().getMmrLambda())
                : candidates.stream().limit(k).toList();

        List<Hit> hits = selected.stream().map(this::toHit).toList();
        long latency = System.currentTimeMillis() - start;

        // 5. 写检索日志 + 写缓存
        logRetrieval(query, hits, latency, false, candidates.size());
        try {
            redis.opsForValue().set(cacheKey, serializeHits(hits),
                    Duration.ofSeconds(properties.getRag().getRetrievalCacheTtlSeconds()));
        } catch (Exception e) {
            log.debug("[RAG] 写入检索缓存失败：{}", e.getMessage());
        }
        return new RetrievalResult(query, hits, latency, false, candidates.size());
    }

    /**
     * MMR（最大边际相关性）重排。
     *
     * <p>实现上刻意用「嵌入向量」而不是「文本关键词」计算片段间相似度：
     * 关键词重叠无法反映语义重复，例如「入住需在次日 23:59 前办理」
     * 与「超过次日 23:59 未办理入住将失效」关键词几乎不重叠，
     * 但语义高度重复，MMR 用向量才能正确识别。
     */
    private List<EmbeddingMatch<TextSegment>> mmr(float[] queryVector,
                                                  List<EmbeddingMatch<TextSegment>> candidates,
                                                  int k, double lambda) {
        List<EmbeddingMatch<TextSegment>> selected = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> pool = new ArrayList<>(candidates);
        while (selected.size() < k && !pool.isEmpty()) {
            EmbeddingMatch<TextSegment> best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (EmbeddingMatch<TextSegment> candidate : pool) {
                double relevance = candidate.score();
                double redundancy = 0.0;
                if (!selected.isEmpty()) {
                    float[] candidateVector = candidate.embedding().vector();
                    for (EmbeddingMatch<TextSegment> chosen : selected) {
                        redundancy = Math.max(redundancy,
                                VectorCodec.cosine(candidateVector, chosen.embedding().vector()));
                    }
                }
                double mmrScore = lambda * relevance - (1 - lambda) * redundancy;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
            pool.remove(best);
        }
        return selected;
    }

    private Hit toHit(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Long chunkId = parseLong(segment.metadata().getString("chunkId"));
        Long documentId = parseLong(segment.metadata().getString("documentId"));
        Long regionId = parseLong(segment.metadata().getString("regionId"));
        return new Hit(chunkId, documentId,
                emptyToNull(segment.metadata().getString("docTitle")),
                emptyToNull(segment.metadata().getString("docCode")),
                segment.metadata().getString("title"),
                segment.text(),
                segment.metadata().getString("category"),
                regionId,
                match.score() == null ? 0.0 : match.score());
    }

    /**
     * 写检索日志。
     *
     * <p>这张表是「RAG 生产实际效果怎么追踪」的答案：
     * 每次检索的命中情况、分数、耗时、是否缓存命中都会落库，
     * 于是可以算出无命中率、平均最高分、用户点踩率等指标，
     * 并且能反查"哪些问题一条都没检索到" —— 那就是知识库的缺口清单。
     *
     * <p>写入失败只记日志、不影响主流程：检索日志是<b>观测数据</b>，
     * 不能因为它写不进去就让用户问不了问题。
     */
    private void logRetrieval(String query, List<Hit> hits, long latencyMs, boolean cacheHit, int candidates) {
        // 先判容量再提交：拿不到"在途名额"就直接丢弃并计数，
        // 绝不因为写观测日志而占用业务连接或阻塞调用线程
        if (!retrievalLogSlots.tryAcquire()) {
            droppedRetrievalLogs.incrementAndGet();
            return;
        }
        // 【必须在调用线程取上下文快照】
        // TraceContext 与 AgentTurnContext 都是 ThreadLocal：检索发生在工具执行线程，
        // 而落库被丢给 retrievalLogExecutor 的池线程执行 —— 池线程里这两个 ThreadLocal
        // 都是空的。改造前直接在池线程里读，结果 user_id/conversation_id/message_id
        // 当日 81/81 行全为 NULL（独立验证实测），运营看板"点踩→当时检索到什么"
        // 这条排查链路实际不成立，而代码注释却声称已补齐。
        final String traceId = TraceContext.get();
        com.overseas.dorm.agent.audit.AgentTurnContext turn =
                com.overseas.dorm.agent.audit.AgentTurnContext.current();
        final Long ctxConversationId = turn == null ? null : turn.conversationId();
        final Long ctxMessageId = turn == null ? null : turn.messageId();
        final Long ctxUserId = (turn == null || turn.user() == null) ? null : turn.user().getUserId();
        try {
            retrievalLogExecutor.execute(() -> {
                try {
                    doLogRetrieval(query, hits, latencyMs, cacheHit,
                            traceId, ctxConversationId, ctxMessageId, ctxUserId);
                } finally {
                    retrievalLogSlots.release();
                }
            });
        } catch (Exception e) {
            retrievalLogSlots.release();
            droppedRetrievalLogs.incrementAndGet();
        }
    }

    /**
     * 落库检索日志。
     *
     * <p>上下文（traceId / 会话 / 消息 / 用户）由**调用线程**作为参数传入，
     * 不在这里读 ThreadLocal —— 本方法运行在检索日志的线程池里，
     * 池线程没有调用方的 ThreadLocal 上下文（见 logRetrieval 里的说明）。
     */
    private void doLogRetrieval(String query, List<Hit> hits, long latencyMs, boolean cacheHit,
                                String traceId, Long conversationId, Long messageId, Long userId) {
        try {
            RagRetrievalLog entity = new RagRetrievalLog();
            entity.setTraceId(traceId);
            // 把检索与"哪一轮对话、哪条消息、哪个用户"关联起来 ——
            // 这是运营看板"点踩的那条回答当时检索到了什么"唯一的排查入口。
            entity.setConversationId(conversationId);
            entity.setMessageId(messageId);
            entity.setUserId(userId);
            entity.setQueryText(query.length() > 480 ? query.substring(0, 480) : query);
            entity.setTopK(properties.getRag().getTopK());
            entity.setHitCount(hits.size());
            entity.setTopScore(hits.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.valueOf(hits.get(0).score()).setScale(4, java.math.RoundingMode.HALF_UP));
            entity.setHitChunkIds(hits.stream().map(h -> String.valueOf(h.chunkId()))
                    .collect(Collectors.joining(",")));
            entity.setScores(hits.stream().map(h -> "%.3f".formatted(h.score()))
                    .collect(Collectors.joining(",")));
            entity.setLatencyMs(latencyMs);
            entity.setCacheHit(cacheHit ? 1 : 0);
            entity.setAnswerUsed(1);
            entity.setFeedback(FeedbackRating.NONE);
            retrievalLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[RAG] 检索日志写入失败（不影响检索结果）", e);
        }
    }

    // ------------------------------------------------------------------ 缓存序列化

    private String serializeHits(List<Hit> hits) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Hit hit : hits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", hit.chunkId());
            item.put("documentId", hit.documentId());
            item.put("docTitle", hit.docTitle());
            item.put("docCode", hit.docCode());
            item.put("title", hit.title());
            item.put("content", hit.content());
            item.put("category", hit.category());
            item.put("regionId", hit.regionId());
            item.put("score", hit.score());
            list.add(item);
        }
        return JsonUtil.toJson(list);
    }

    private List<Hit> deserializeHits(String json) {
        List<Map<String, Object>> list = JsonUtil.parseList(json);
        if (list.isEmpty()) {
            return null;
        }
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> item : list) {
            hits.add(new Hit(
                    parseLong(String.valueOf(item.get("chunkId"))),
                    parseLong(String.valueOf(item.get("documentId"))),
                    item.get("docTitle") == null ? null : String.valueOf(item.get("docTitle")),
                    item.get("docCode") == null ? null : String.valueOf(item.get("docCode")),
                    item.get("title") == null ? null : String.valueOf(item.get("title")),
                    String.valueOf(item.get("content")),
                    item.get("category") == null ? null : String.valueOf(item.get("category")),
                    parseLong(String.valueOf(item.get("regionId"))),
                    item.get("score") == null ? 0.0 : ((Number) item.get("score")).doubleValue()));
        }
        return hits;
    }

    private static String emptyToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static Long parseLong(String text) {
        if (text == null || text.isBlank() || "null".equals(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 运维视图：索引规模与检索参数。
     *
     * <p>把实际生效的检索参数一并下发（而不是让前端猜）：
     * "召回效果变差了"时，第一件事就是确认 topK / minScore / MMR 当前是什么值。
     */
    public RagIndexStatusVO indexStatus() {
        return new RagIndexStatusVO(
                embeddingStore.size(),
                embeddingStore.version(),
                properties.getRag().isMmrEnabled(),
                properties.getRag().getMmrLambda(),
                properties.getRag().getCandidateMultiplier(),
                properties.getRag().getTopK(),
                properties.getRag().getMinScore(),
                knowledgeBaseLoader.lastReport());
    }

    /**
     * 生产质量指标（近 N 天）。
     *
     * <p>比率统一用 {@link BigDecimal} 小数（0–1）而不是 {@code "12.5%"} 字符串：
     * 字符串把数值与展示混在一起，前端要做阈值变色还得再解析一次。
     * 同时保留分子分母原始计数，便于核对。
     */
    public RagQualityVO qualityMetrics(int days) {
        RagQualityMetricsVO metrics = retrievalLogMapper.qualityMetrics(days);
        long total = metrics == null || metrics.getTotal() == null ? 0L : metrics.getTotal();
        long noHit = metrics == null || metrics.getNoHit() == null ? 0L : metrics.getNoHit();
        long cacheHits = metrics == null || metrics.getCacheHits() == null ? 0L : metrics.getCacheHits();
        long likes = metrics == null || metrics.getLikes() == null ? 0L : metrics.getLikes();
        long dislikes = metrics == null || metrics.getDislikes() == null ? 0L : metrics.getDislikes();
        List<RagQualityVO.MissedQueryItem> missed = retrievalLogMapper
                .topMissedQueries(days, QUALITY_MISSED_QUERY_LIMIT).stream()
                .map(item -> new RagQualityVO.MissedQueryItem(item.getQueryText(),
                        item.getCnt() == null ? 0L : item.getCnt()))
                .toList();
        return new RagQualityVO(
                days,
                total,
                noHit,
                cacheHits,
                ratio(noHit, total),
                ratio(cacheHits, total),
                metrics == null || metrics.getAvgTopScore() == null
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(metrics.getAvgTopScore()).setScale(4, RoundingMode.HALF_UP),
                metrics == null ? null : metrics.getAvgLatencyMs(),
                likes,
                dislikes,
                missed);
    }

    /** 占比计算（分母为 0 时返回 0，避免出现 NaN 让前端渲染成 "NaN"）。 */
    private static BigDecimal ratio(long part, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part * 100.0 / total)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    /**
     * 检索日志的在途写入上限。
     *
     * <p>为什么要有上限并允许丢弃：检索日志是**观测数据**，不是业务数据。
     * 实测 1000 并发下每轮会产生 1~3 次检索日志写入，与业务请求抢同一个连接池
     * （当时报的是 waiting=914、连接 10s 超时）。观测数据在过载时应当被丢弃并计数，
     * 而不是把业务请求拖死 —— 这条取舍必须在代码里显式表达，而不是默认"日志都要写进去"。
     */
    private static final int RETRIEVAL_LOG_MAX_INFLIGHT = 200;

    /** 因过载被丢弃的检索日志条数（暴露出去，避免"悄悄不记了"）。 */
    private final java.util.concurrent.atomic.AtomicLong droppedRetrievalLogs =
            new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.Semaphore retrievalLogSlots =
            new java.util.concurrent.Semaphore(RETRIEVAL_LOG_MAX_INFLIGHT);

    private final java.util.concurrent.ExecutorService retrievalLogExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** 供运维查看：被丢弃的检索日志量（持续增长说明需要扩容或降采样）。 */
    public long droppedRetrievalLogs() {
        return droppedRetrievalLogs.get();
    }

    /** 质量看板里"未命中问题榜"的展示条数。 */
    private static final int QUALITY_MISSED_QUERY_LIMIT = 20;

    /** 供评测使用：不写日志、不带缓存的纯检索。 */
    public List<Hit> retrieveForEvaluation(String query, Long regionId, int topK) {
        float[] vector = knowledgeBaseLoader.embedWithCache(query);
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.searchByVector(
                vector, Math.max(topK, 1), 0.0, regionId, null);
        return matches.stream().map(this::toHit).toList();
    }

    /** 供评测使用：带分类过滤的检索。 */
    public List<Hit> retrieveForEvaluation(String query, Long regionId, DocCategory category, int topK) {
        float[] vector = knowledgeBaseLoader.embedWithCache(query);
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.searchByVector(
                vector, Math.max(topK, 1), 0.0, regionId, category);
        return matches.stream().map(this::toHit).toList();
    }

    /** 标准检索入口（走 LangChain4j 的 EmbeddingSearchRequest，供通用场景使用）。 */
    public List<Hit> search(Embedding queryEmbedding, int topK, double minScore, Long regionId) {
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();
        return embeddingStore.search(request).matches().stream().map(this::toHit).toList();
    }
}
