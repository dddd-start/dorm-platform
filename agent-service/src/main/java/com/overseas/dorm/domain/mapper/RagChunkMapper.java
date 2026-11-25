package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.RagChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库切片数据访问（含向量二进制）。
 *
 * <p>只索引状态为"已发布"的文档，且状态值由调用方传入枚举名，* SQL 里写死 {@code 'PUBLISHED'} 的话，枚举改名后会静默返回空集，
 * 表现为"助手突然什么制度都查不到"而日志毫无线索。
 */
@Mapper
public interface RagChunkMapper extends BaseMapper<RagChunk> {

    /**
     * 加载全部切片（含向量）用于检索。
     *
     * <p>这里是「低延迟高并发的 RAG 服务」设计上的一个明确取舍：
     * 知识库规模在万级切片以内时，把向量一次性加载进进程内做暴力余弦相似度，
     * 比每次查库回传大对象要快得多（毫秒级 vs 数十毫秒），
     * 而且省掉了数据库带宽。当规模继续增长时，
     * 这里就是替换为向量数据库 / HNSW 索引的切入点（见 MysqlEmbeddingStore 的注释）。
     */
    /**
     * 取参与检索的全部切片。
     *
     * <p>文档状态由调用方传入枚举名（{@code DocumentStatus.PUBLISHED.name()}），
     * 不在 SQL 里写 {@code 'PUBLISHED'} 字面量：枚举改名时 SQL 会照跑不报错，
     * 结果是<b>检索静默返回空</b>（助手突然"什么制度都查不到"），而日志里毫无线索。
     */
    @Select("""
            SELECT c.id, c.document_id, c.doc_version, c.chunk_index, c.title, c.content,
                   c.content_hash, c.embedding, c.embedding_dim, c.embedding_model, c.token_count,
                   c.metadata_json, c.region_id, c.category, c.create_time,
                   d.title AS docTitle, d.doc_code AS docCode
              FROM rag_chunk c
              JOIN rag_document d ON d.id = c.document_id
             WHERE d.status = #{published} AND c.doc_version = d.version
            """)
    List<RagChunk> selectIndexedChunks(@Param("published") String published);


    /** 已发布的切片总数（用于索引自检：库里有多少 vs 内存索引加载了多少）。 */
    @Select("""
            SELECT COUNT(*) FROM rag_chunk c
              JOIN rag_document d ON d.id = c.document_id
             WHERE d.status = #{published}
            """)
    long countIndexed(@Param("published") String published);

    @Delete("DELETE FROM rag_chunk WHERE document_id = #{documentId}")
    int deleteByDocument(@Param("documentId") Long documentId);

}
