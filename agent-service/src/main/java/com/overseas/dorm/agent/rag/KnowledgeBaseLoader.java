package com.overseas.dorm.agent.rag;

import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.enums.DocCategory;
import com.overseas.dorm.common.util.CryptoUtil;
import com.overseas.dorm.domain.entity.RagChunk;
import com.overseas.dorm.agent.vo.KnowledgeLoadReportVO;
import com.overseas.dorm.common.enums.DocumentStatus;
import com.overseas.dorm.domain.entity.RagDocument;
import com.overseas.dorm.domain.mapper.RagChunkMapper;
import com.overseas.dorm.domain.mapper.RagDocumentMapper;
import com.overseas.dorm.agent.client.DormServiceClient;
import com.overseas.dorm.domain.vo.RegionOptionVO;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库装载器：把 Markdown 文档变成可检索的向量切片。
 *
 * 知识内容用 Markdown 放在版本库里，而不是让人在后台富文本里编辑，有三个好处：
 * <ol>
 *   <li><b>可评审</b>：制度变更走代码评审流程，谁改了什么一目了然；</li>
 *   <li><b>可回滚</b>：知识库出错可以 git revert，后台编辑做不到；</li>
 *   <li><b>可复现</b>：任何环境（开发/测试/生产）用同一份知识，不会出现
 *       "测试环境查得到、生产环境查不到"的诡异问题。</li>
 * </ol>
 * 表里的 {@code rag_document} 只是这份真相的<b>索引与快照</b>。
 *
 * 向量化是整个 RAG 链路里最贵的一步（本地 ONNX 也要几百毫秒/篇）。
 * 每次启动都全量重建会让启动时间白白增加几十秒。
 * 改了内容就把 front-matter 里的 version +1。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseLoader {

    private final RagDocumentMapper documentMapper;
    private final RagChunkMapper chunkMapper;
    private final DormServiceClient dormService;
    private final EmbeddingModel embeddingModel;
    private final MysqlEmbeddingStore embeddingStore;
    private final StringRedisTemplate redis;
    private final AgentProperties properties;

    /**
     * 最近一次装载结果，供管理接口展示。
     *
     * <p>用具体类型而不是 {@code Map<String, Object>}：这份报告是运维判断
     * "这次启动到底有没有真的重建索引"的依据，字段名写错只会得到 null，
     * 而"看起来装载成功、实际一篇都没更新"正是 RAG 最隐蔽的故障。
     */
    private volatile KnowledgeLoadReportVO lastLoadReport = KnowledgeLoadReportVO.empty();

    /**
     * 显式事务模板。
     *
     * <p>见 {@link #rebuildAll()} 的说明：为了在"类内自调用"路径上也能生效，
     * 这里不用声明式事务注解。
     */
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * 应用就绪后自动装载知识库。
     *
     * <p>用 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 后者会在 bean 初始化阶段执行，此时数据库连接池、事务管理器可能还没完全就绪。
     * 而且知识库重建是耗时操作，放在 @PostConstruct 会拖慢整个应用启动，
     * 甚至让健康检查探针超时。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        if (!properties.getRag().isEnabled()) {
            log.info("[RAG] 知识库功能已关闭（dorm.agent.rag.enabled=false）");
            return;
        }
        if (!properties.getRag().isRebuildOnStartup()) {
            log.info("[RAG] 跳过启动重建，仅加载现有索引");
            embeddingStore.reload();
            return;
        }
        try {
            KnowledgeLoadReportVO report = rebuildAll();
            log.info("[RAG] 知识库装载完成：{}", report);
        } catch (Exception e) {
            // 知识库装载失败不应该让应用起不来 —— 业务功能必须可用
            log.error("[RAG] 知识库装载失败，助手将退化为无知识库模式：{}", e.getMessage(), e);
        }
    }

    /**
     * 全量重建：扫描文件、按需重新向量化、重载索引。
     *
     * 这个方法有两条调用路径：
     * <ol>
     *   <li>管理接口 {@code POST /api/agent/admin/knowledge/rebuild}，外部调用，代理生效；</li>
     *   <li>启动监听 {@link #loadOnStartup}，<b>类内自调用</b>，Spring 代理不参与，
     *       {@code @Transactional} 会被完全忽略。</li>
     * </ol>
     * 也就是说，加了 {@code @Transactional} 反而会让人误以为启动重建是原子的，
     * 实际上一旦中途失败，知识库会停在"部分文档已更新、旧切片已删、新切片未写完"的中间状态，
     * 而 RAG 检索仍会照常读到这批不一致的数据。
     *
     * <p>改为显式的事务模板后，两条路径都真正具备事务性，而且事务边界在代码里看得见。
     * 重载内存索引与清理缓存放在事务之外：它们是内存/Redis 操作，不该被数据库回滚牵连。
     */
    public KnowledgeLoadReportVO rebuildAll() throws IOException {
        KnowledgeLoadReportVO report = transactionTemplate.execute(status -> {
            try {
                return doRebuild();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        // 事务提交后再刷新索引与缓存：避免"数据库回滚了、内存索引却已更新"的错位
        embeddingStore.reload();
        invalidateRetrievalCache();
        // 自检：数据库里有已发布切片、但内存索引为空 → 一定是装载链路坏了
        // （本项目真实发生过：显式列名写错导致 SQL 报错，索引静默为 0，
        //  表现为"助手任何制度问题都答不上来"，而日志里只有一行被 try/catch 吞掉的警告）。
        // 这种"静默降级为无知识库"必须显式告警，否则只能靠用户投诉发现。
        if (embeddingStore.size() == 0) {
            long published = chunkMapper.countIndexed(DocumentStatus.PUBLISHED.name());
            if (published > 0) {
                log.error("[RAG] 索引自检失败：数据库有 {} 个已发布切片，但内存索引为 0！"
                        + "知识库检索将全部无命中，请检查装载日志（此前常见原因是列名/映射不匹配）",
                        published);
            }
        }
        return report;
    }

    /** 真正做数据库写入的部分（扫描 / 按版本决定重建 / 写文档与切片）。 */
    private KnowledgeLoadReportVO doRebuild() throws IOException {
        List<Resource> resources = scanKnowledgeFiles();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int totalChunks = 0;
        List<String> details = new ArrayList<>();

        for (Resource resource : resources) {
            String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ParsedDocument parsed = parse(raw, resource.getFilename());
            if (parsed == null) {
                log.warn("[RAG] 跳过无法解析的文档：{}", resource.getFilename());
                continue;
            }
            RagDocument existing = documentMapper.selectByCode(parsed.docCode());
            if (existing == null) {
                RagDocument document = upsertDocument(null, parsed);
                int chunkCount = indexDocument(document, parsed);
                created++;
                totalChunks += chunkCount;
                details.add("%s(新建 %d 切片)".formatted(parsed.docCode(), chunkCount));
            } else if (existing.getIndexedVersion() == null
                    || existing.getIndexedVersion() < parsed.version()
                    // 区域归属变化也必须更新：否则业务分库后首次启动（dorm-service 尚未就绪）
                    // 写入的 region_id=null 会永久留在库里，区域过滤检索会一直失效。
                    || !java.util.Objects.equals(existing.getRegionId(), parsed.regionId())) {
                RagDocument document = upsertDocument(existing, parsed);
                int chunkCount = indexDocument(document, parsed);
                updated++;
                totalChunks += chunkCount;
                details.add("%s(v%d→v%d，重建 %d 切片)".formatted(parsed.docCode(),
                        existing.getVersion(), parsed.version(), chunkCount));
            } else {
                unchanged++;
                totalChunks += existing.getChunkCount() == null ? 0 : existing.getChunkCount();
            }
        }

        KnowledgeLoadReportVO report = new KnowledgeLoadReportVO(
                resources.size(), created, updated, unchanged,
                totalChunks, embeddingStore.size(), List.copyOf(details));
        lastLoadReport = report;
        return report;
    }

    /** 扫描 classpath 下的知识文件。 */
    private List<Resource> scanKnowledgeFiles() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(properties.getRag().getKnowledgeLocation());
        List<Resource> result = new ArrayList<>();
        for (Resource resource : resources) {
            if (resource.isReadable() && resource.getFilename() != null
                    && resource.getFilename().endsWith(".md")) {
                result.add(resource);
            }
        }
        result.sort((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
        return result;
    }

    /** 解析 front-matter 与正文。 */
    @SuppressWarnings("unused")
    private ParsedDocument parse(String raw, String filename) {
        if (!raw.startsWith("---")) {
            log.warn("[RAG] {} 缺少 front-matter，已跳过", filename);
            return null;
        }
        int end = raw.indexOf("\n---", 3);
        if (end < 0) {
            log.warn("[RAG] {} 的 front-matter 未闭合，已跳过", filename);
            return null;
        }
        String header = raw.substring(3, end).strip();
        String content = raw.substring(end + 4).strip();
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : header.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            meta.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
        }
        String docCode = meta.get("docCode");
        String title = meta.get("title");
        if (docCode == null || title == null) {
            log.warn("[RAG] {} 缺少 docCode 或 title，已跳过", filename);
            return null;
        }
        DocCategory category = DocCategory.FAQ;
        try {
            category = DocCategory.valueOf(meta.getOrDefault("category", "FAQ"));
        } catch (Exception e) {
            log.warn("[RAG] {} 的分类 {} 非法，回退为 FAQ", filename, meta.get("category"));
        }
        int version = 1;
        try {
            version = Integer.parseInt(meta.getOrDefault("version", "1"));
        } catch (NumberFormatException ignored) {
            // 保持默认版本 1
        }
        Long regionId = resolveRegionId(meta.get("regionCode"));
        LocalDate effectiveDate = null;
        try {
            if (meta.get("effectiveDate") != null) {
                effectiveDate = LocalDate.parse(meta.get("effectiveDate"));
            }
        } catch (Exception ignored) {
            // 生效日期非必填
        }
        return new ParsedDocument(docCode, title, category, regionId, version, content,
                meta.get("source"), effectiveDate);
    }

    private Long resolveRegionId(String regionCode) {
        if (regionCode == null || regionCode.isBlank() || "GLOBAL".equalsIgnoreCase(regionCode)) {
            return null;
        }
        String code = regionCode.trim().toUpperCase();
        try {
            for (RegionOptionVO option : dormService.catalogRegionOptions()) {
                if (code.equalsIgnoreCase(option.code())) {
                    return option.id();
                }
            }
        } catch (Exception e) {
            log.warn("[RAG] 调用业务服务解析区域 {} 失败，按全局文档处理：{}", code, e.getMessage());
            return null;
        }
        log.warn("[RAG] 未找到区域编码 {}，该文档将作为全局文档处理", regionCode);
        return null;
    }

    private RagDocument upsertDocument(RagDocument existing, ParsedDocument parsed) {
        RagDocument document = new RagDocument();
        document.setDocCode(parsed.docCode());
        document.setTitle(parsed.title());
        document.setCategory(parsed.category());
        document.setRegionId(parsed.regionId());
        document.setVersion(parsed.version());
        document.setStatus(DocumentStatus.PUBLISHED);
        document.setContent(parsed.content());
        document.setSource(parsed.source());
        document.setEffectiveDate(parsed.effectiveDate());
        if (existing == null) {
            document.setIndexedVersion(0);
            document.setChunkCount(0);
            documentMapper.insert(document);
        } else {
            document.setId(existing.getId());
            documentMapper.updateById(document);
            document = documentMapper.selectById(existing.getId());
        }
        return document;
    }

    /**
     * 分块 + 向量化 + 落库。
     *
     * @return 本次写入的切片数量（用于装载报告；注意不能用 document.getChunkCount()，
     *         因为那是在另一个 update 对象上写库的，本地对象不会自动刷新）
     */
    private int indexDocument(RagDocument document, ParsedDocument parsed) {
        // 先清旧切片：版本变更后旧内容必须彻底移除，否则会检索到已废止的条款
        chunkMapper.deleteByDocument(document.getId());

        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(parsed.content(),
                properties.getRag().getChunkSize(), properties.getRag().getChunkOverlap());
        for (MarkdownChunker.Chunk chunk : chunks) {
            float[] vector = embedWithCache(chunk.content());
            RagChunk entity = new RagChunk();
            entity.setDocumentId(document.getId());
            entity.setDocVersion(parsed.version());
            entity.setChunkIndex(chunk.index());
            entity.setTitle(truncate(chunk.title(), 250));
            entity.setContent(chunk.content());
            entity.setContentHash(CryptoUtil.sha256(chunk.content()));
            entity.setEmbedding(VectorCodec.encode(vector));
            entity.setEmbeddingDim(vector.length);
            entity.setEmbeddingModel("bge-small-zh-v1.5-q");
            entity.setTokenCount(estimateTokens(chunk.content()));
            entity.setRegionId(parsed.regionId());
            entity.setCategory(parsed.category());
            entity.setMetadataJson("""
                    {"docCode":"%s","category":"%s","regionId":%s,"chunkIndex":%d}"""
                    .formatted(parsed.docCode(), parsed.category().name(),
                            parsed.regionId() == null ? "null" : parsed.regionId(), chunk.index()));
            chunkMapper.insert(entity);
        }

        RagDocument update = new RagDocument();
        update.setId(document.getId());
        update.setChunkCount(chunks.size());
        update.setIndexedVersion(parsed.version());
        documentMapper.updateById(update);
        log.info("[RAG] 文档已索引：{} v{} 共 {} 个切片", parsed.docCode(), parsed.version(), chunks.size());
        return chunks.size();
    }

    /**
     * 带缓存的向量化。
     *
     * <p>缓存键是文本的 SHA-256：内容相同必然得到相同的向量，
     * 因此可以放心缓存。这个缓存在两个场景下收益极大：
     * <ol>
     *   <li>知识库重建时未改动的段落不必重复推理；</li>
     *   <li>多个用户问相似问题时，查询向量可以命中缓存。</li>
     * </ol>
     */
    public float[] embedWithCache(String text) {
        String hash = CryptoUtil.sha256(text);
        String key = RedisKeys.embeddingCache("bge-small-zh-v1.5-q", hash);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return decodeBase64Vector(cached);
            }
        } catch (Exception e) {
            log.debug("[RAG] 读取嵌入缓存失败，回退到实时计算：{}", e.getMessage());
        }
        Embedding embedding = embeddingModel.embed(text).content();
        float[] vector = embedding.vector();
        try {
            redis.opsForValue().set(key, encodeBase64Vector(vector),
                    Duration.ofSeconds(properties.getRag().getEmbeddingCacheTtlSeconds()));
        } catch (Exception e) {
            log.debug("[RAG] 写入嵌入缓存失败：{}", e.getMessage());
        }
        return vector;
    }

    private static String encodeBase64Vector(float[] vector) {
        return java.util.Base64.getEncoder().encodeToString(VectorCodec.encode(vector));
    }

    private static float[] decodeBase64Vector(String base64) {
        return VectorCodec.decode(java.util.Base64.getDecoder().decode(base64));
    }

    private void invalidateRetrievalCache() {
        try {
            var keys = redis.keys(RedisKeys.PREFIX + "cache:retrieval:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("[RAG] 已清理 {} 个检索缓存", keys.size());
            }
            var answerKeys = redis.keys(RedisKeys.PREFIX + "cache:answer:*");
            if (answerKeys != null && !answerKeys.isEmpty()) {
                redis.delete(answerKeys);
            }
        } catch (Exception e) {
            log.warn("[RAG] 清理检索缓存失败：{}", e.getMessage());
        }
    }

    /** 粗略估算 token 数（中文约 1 字 1 token，英文约 4 字符 1 token）。 */
    private static int estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + other / 4;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    public KnowledgeLoadReportVO lastReport() {
        return lastLoadReport;
    }

    /** 解析结果。 */
    private record ParsedDocument(String docCode, String title, DocCategory category, Long regionId,
                                  int version, String content, String source, LocalDate effectiveDate) {
    }
}
