package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.overseas.dorm.common.enums.DocCategory;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RAG 文档切片与向量。
 *
 * <p>向量以 float32 小端字节序存进 MEDIUMBLOB，而不是 JSON 数组：
 * 一个 512 维向量用 JSON 存大约 6~8KB，用二进制只需 2KB，
 * 检索时也省掉了解析字符串的开销。配套的编解码在
 * {@code com.overseas.dorm.agent.rag.VectorCodec}。
 *
 * <p>{@code contentHash} 用于嵌入缓存：同样的文本永不重复调用嵌入模型。
 */
@Data
@TableName("rag_chunk")
public class RagChunk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long documentId;
    private Integer docVersion;
    private Integer chunkIndex;
    private String title;
    private String content;
    private String contentHash;
    private byte[] embedding;
    private Integer embeddingDim;
    private String embeddingModel;
    private Integer tokenCount;
    /** 冗余：用于元数据过滤，避免检索时 JOIN 文档表。 */
    private Long regionId;
    private DocCategory category;
    private String metadataJson;
    private LocalDateTime createTime;

    /**
     * 以下两个字段不进数据库，仅用于承载 JOIN 出来的文档来源信息。
     *
     * <p>为什么不让检索结果回头去查文档表：检索是最高频的路径（每轮对话都会走），
     * 多一次查询就是多一次往返；而且知识库规模变大后，
     * "每个切片回查一次文档"会变成 N+1 查询。在加载索引时一次性 JOIN 出来最省。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String docTitle;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String docCode;
}
