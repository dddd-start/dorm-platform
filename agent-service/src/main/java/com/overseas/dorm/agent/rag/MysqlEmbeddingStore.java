package com.overseas.dorm.agent.rag;

import com.overseas.dorm.common.enums.DocCategory;
import com.overseas.dorm.domain.entity.RagChunk;
import com.overseas.dorm.domain.mapper.RagChunkMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.RelevanceScore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.ContainsString;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于 MySQL 的向量存储 —— 自己实现 {@link EmbeddingStore} 而不是用现成的内存实现。
 *
 * <h3>为什么不直接用 InMemoryEmbeddingStore</h3>
 * <ol>
 *   <li>重启即丢，每次启动都要重新跑一遍全量嵌入（几千个切片 = 几分钟启动时间）；</li>
 *   <li>多实例部署时每个实例各存一份，内存浪费且内容可能不一致；</li>
 *   <li>无法审计"这条答案到底基于知识库里的哪个片段" —— 而这是 RAG 可解释性的基础。</li>
 * </ol>
 *
 * <h3>为什么检索走内存索引而不是每次查库</h3>
 * 这是一个显式的取舍，写在这里以免后来者误以为是"偷懒"：
 * <pre>
 *   查库暴力检索：每次检索回传几千行 MEDIUMBLOB（几十 MB），
 *                 即使有连接池，带宽与反序列化开销也是数十毫秒级；
 *   内存索引：    启动时一次性加载并常驻，检索是纯 CPU 的向量点积，
 *                 千级切片在 1 毫秒内完成。
 * </pre>
 * 代价是内存占用（1000 切片 × 512 维 × 4B ≈ 2MB，完全可接受）与
 * 写后需要刷新索引。刷新由 {@link #reload()} 完成，知识库重建时调用。
 *
 * <h3>规模增长后的演进路径</h3>
 * 当切片数超过十万级时，这个实现会成为瓶颈。演进方式是：
 * 把 {@link #search} 的实现换成「先按元数据过滤缩小候选集，
 * 再用 HNSW/IVF 索引」或者直接把向量迁到专用向量库
 * （Milvus / Qdrant / pgvector / Redis Search），
 * <b>上层的 RagService 与工具体系完全不需要改动</b> ——
 * 这正是面向接口编程在这里的真正价值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final RagChunkMapper chunkMapper;

    /** 内存索引条目。 */
    private record IndexEntry(Long chunkId, float[] vector, TextSegment segment,
                              Long regionId, DocCategory category) {
    }

    private volatile List<IndexEntry> index = List.of();
    private final AtomicLong version = new AtomicLong(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 从数据库重新加载索引。
     *
     * <p>用读写锁而不是 synchronized：检索是高频操作（每次对话都要检索），
     * 重建索引是低频操作。读操作之间要能并发，只在重建时短暂写锁。
     */
    public void reload() {
        lock.writeLock().lock();
        try {
            // 只索引"已发布"的文档：状态值由枚举提供，避免 SQL 里出现第二份真相
            List<RagChunk> chunks = chunkMapper.selectIndexedChunks(
                    com.overseas.dorm.common.enums.DocumentStatus.PUBLISHED.name());
            List<IndexEntry> entries = new ArrayList<>(chunks.size());
            for (RagChunk chunk : chunks) {
                float[] vector = VectorCodec.decode(chunk.getEmbedding());
                if (vector.length == 0) {
                    continue;
                }
                java.util.Map<String, Object> metaMap = new java.util.HashMap<>();
                metaMap.put("chunkId", String.valueOf(chunk.getId()));
                metaMap.put("documentId", String.valueOf(chunk.getDocumentId()));
                metaMap.put("docVersion", String.valueOf(chunk.getDocVersion()));
                metaMap.put("chunkIndex", String.valueOf(chunk.getChunkIndex()));
                metaMap.put("title", chunk.getTitle() == null ? "" : chunk.getTitle());
                metaMap.put("category", chunk.getCategory() == null ? "" : chunk.getCategory().name());
                metaMap.put("regionId", chunk.getRegionId() == null ? "" : String.valueOf(chunk.getRegionId()));
                // 文档来源：用于在回答里标注"依据是哪份文件"，也是召回率评测的判定键
                metaMap.put("docTitle", chunk.getDocTitle() == null ? "" : chunk.getDocTitle());
                metaMap.put("docCode", chunk.getDocCode() == null ? "" : chunk.getDocCode());
                Metadata metadata = Metadata.from(metaMap);
                entries.add(new IndexEntry(chunk.getId(), vector,
                        TextSegment.from(chunk.getContent(), metadata),
                        chunk.getRegionId(), chunk.getCategory()));
            }
            this.index = List.copyOf(entries);
            version.incrementAndGet();
            log.info("[RAG] 向量索引已加载：{} 个切片（版本 {}）", entries.size(), version.get());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        return index.size();
    }

    public long version() {
        return version.get();
    }

    // ================================================================ EmbeddingStore

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException(
                "本实现只面向知识库切片，请通过 KnowledgeBaseLoader 写入切片");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException(
                "本实现只面向知识库切片，请通过 KnowledgeBaseLoader 写入切片");
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        // 该重载用于通用写入场景；本项目走 KnowledgeBaseLoader 直接落库，
        // 这里保留实现以便将来接入"用户上传文档即时索引"这类需求。
        if (embedded == null) {
            throw new IllegalArgumentException("追加向量时必须提供文本片段");
        }
        String documentId = embedded.metadata().getString("documentId");
        String chunkIndex = embedded.metadata().getString("chunkIndex");
        RagChunk chunk = new RagChunk();
        chunk.setId(Long.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)));
        chunk.setDocumentId(documentId == null ? 0L : Long.parseLong(documentId));
        chunk.setDocVersion(1);
        chunk.setChunkIndex(chunkIndex == null ? 0 : Integer.parseInt(chunkIndex));
        chunk.setContent(embedded.text());
        chunk.setContentHash(com.overseas.dorm.common.util.CryptoUtil.sha256(embedded.text()));
        chunk.setEmbedding(VectorCodec.encode(embedding.vector()));
        chunk.setEmbeddingDim(embedding.dimension());
        chunk.setTitle(embedded.metadata().getString("title"));
        chunkMapper.insert(chunk);
        String id = String.valueOf(chunk.getId());
        reload();
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException(
                "本实现只面向知识库切片，请通过 KnowledgeBaseLoader 写入切片");
    }

    /**
     * 向量检索 + 元数据过滤。
     *
     * <p>过滤器的处理方式值得说明：LangChain4j 的 {@link Filter} 是一个表达式树，
     * 要完整支持它需要引入 {@code langchain4j-core} 的 filter 解析工具。
     * 本项目用到的过滤维度只有两个（区域、分类），因此这里做<b>显式的字段提取</b>
     * 而不是通用表达式求值 —— 代码更好读，也避免了对 Filter 内部结构的依赖。
     * 如果将来需要组合条件（AND/OR/嵌套），再换成官方提供的求值器。
     */
    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        lock.readLock().lock();
        try {
            if (index.isEmpty()) {
                return new EmbeddingSearchResult<>(List.of());
            }
            float[] query = request.queryEmbedding().vector();
            int maxResults = request.maxResults();
            double minScore = request.minScore();
            Filter filter = request.filter();

            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            for (IndexEntry entry : index) {
                if (!passesFilter(entry, filter)) {
                    continue;
                }
                double score = VectorCodec.cosine(query, entry.vector());
                if (score < minScore) {
                    continue;
                }
                matches.add(new EmbeddingMatch<>(RelevanceScore.fromCosineSimilarity(score),
                        String.valueOf(entry.chunkId()), Embedding.from(entry.vector()), entry.segment()));
            }
            matches.sort(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed());
            List<EmbeddingMatch<TextSegment>> top = matches.size() > maxResults
                    ? new ArrayList<>(matches.subList(0, maxResults)) : matches;
            return new EmbeddingSearchResult<>(top);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void removeAll() {
        lock.writeLock().lock();
        try {
            chunkMapper.delete(null);
            index = List.of();
            version.incrementAndGet();
            log.warn("[RAG] 已清空全部向量索引");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ================================================================ 内部

    /**
     * 元数据过滤求值。
     *
     * <p>这里实现对 LangChain4j {@link Filter} 表达式树的<b>递归求值</b>，
     * 而不是用 {@code toString()} 做字符串匹配 —— 后者看起来能跑，
     * 但只要条件的值里出现 "regionId" 这样的字面量，过滤结果就会莫名其妙地错乱，
     * 而且极难排查。
     *
     * <p>业务上有一条特殊规则：{@code regionId} 为 {@code null} 的切片
     * 表示「全局文档」（例如集团统一的入住流程规定），
     * 它在任何区域的检索里都应该命中 —— 否则员工在新加坡问流程时会查不到集团制度。
     */
    private boolean passesFilter(IndexEntry entry, Filter filter) {
        if (filter == null) {
            return true;
        }
        if (filter instanceof And and) {
            return passesFilter(entry, and.left()) && passesFilter(entry, and.right());
        }
        if (filter instanceof Or or) {
            return passesFilter(entry, or.left()) || passesFilter(entry, or.right());
        }
        if (filter instanceof Not not) {
            return !passesFilter(entry, not.expression());
        }
        if (filter instanceof IsEqualTo eq) {
            return matches(entry, eq.key(), eq.comparisonValue());
        }
        if (filter instanceof IsNotEqualTo ne) {
            return !matches(entry, ne.key(), ne.comparisonValue());
        }
        if (filter instanceof IsIn in) {
            return in.comparisonValues().stream().anyMatch(v -> matches(entry, in.key(), v));
        }
        if (filter instanceof IsNotIn nin) {
            return nin.comparisonValues().stream().noneMatch(v -> matches(entry, nin.key(), v));
        }
        if (filter instanceof ContainsString contains) {
            Object value = metadataValue(entry, contains.key());
            return value != null && String.valueOf(value).contains(String.valueOf(contains.comparisonValue()));
        }
        log.debug("[RAG] 未支持的过滤条件类型 {}，已忽略该条件", filter.getClass().getSimpleName());
        return true;
    }

    /** 取切片上某个元数据键的值。 */
    private Object metadataValue(IndexEntry entry, String key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "regionId" -> entry.regionId();
            case "category" -> entry.category() == null ? null : entry.category().name();
            case "chunkId" -> entry.chunkId();
            default -> entry.segment().metadata().getString(key);
        };
    }

    private boolean matches(IndexEntry entry, String key, Object expected) {
        Object actual = metadataValue(entry, key);
        // 全局文档（regionId 为空）对任何区域查询都可见
        if ("regionId".equals(key) && actual == null) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        // 数值与字符串混比是常见陷阱：元数据里 regionId 可能是 String 也可能是 Long
        if (actual instanceof Number a && expected instanceof Number b) {
            return a.longValue() == b.longValue();
        }
        if (actual instanceof Number a) {
            try {
                return a.longValue() == Long.parseLong(String.valueOf(expected));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (expected instanceof Number b) {
            try {
                return Long.parseLong(String.valueOf(actual)) == b.longValue();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
    }

    /** 便于测试：直接按向量做一次检索。 */
    public List<EmbeddingMatch<TextSegment>> searchByVector(float[] query, int maxResults, double minScore,
                                                            Long regionId, DocCategory category) {
        lock.readLock().lock();
        try {
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            for (IndexEntry entry : index) {
                if (regionId != null && entry.regionId() != null && !regionId.equals(entry.regionId())) {
                    continue;
                }
                if (category != null && entry.category() != category) {
                    continue;
                }
                double score = VectorCodec.cosine(query, entry.vector());
                if (score < minScore) {
                    continue;
                }
                matches.add(new EmbeddingMatch<>(RelevanceScore.fromCosineSimilarity(score),
                        String.valueOf(entry.chunkId()), Embedding.from(entry.vector()), entry.segment()));
            }
            matches.sort(Comparator.comparingDouble(EmbeddingMatch<TextSegment>::score).reversed());
            return matches.size() > maxResults ? new ArrayList<>(matches.subList(0, maxResults)) : matches;
        } finally {
            lock.readLock().unlock();
        }
    }
}
