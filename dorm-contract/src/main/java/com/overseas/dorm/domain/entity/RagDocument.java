package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.overseas.dorm.common.enums.DocCategory;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * RAG 知识文档。
 *
 * <p>{@code version} 与 {@code indexedVersion} 的分离是有意设计：
 * 文档内容更新后，重建索引是有成本的（要重新切分 + 重新向量化）。
 * 两者不一致就说明「该文档已更新但索引还是旧的」，
 * 启动时与后台任务据此决定要不要重建，也用于让检索缓存失效。
 */
@Data
@TableName("rag_document")
public class RagDocument implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String docCode;
    private String title;
    private DocCategory category;
    /** 归属区域；null 表示全局适用。 */
    private Long regionId;
    private Integer version;
    /** 文档状态；只有 PUBLISHED 参与索引与检索。 */
    private com.overseas.dorm.common.enums.DocumentStatus status;
    private String content;
    private String source;
    private LocalDate effectiveDate;
    private Integer chunkCount;
    private Integer indexedVersion;
    private Long createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
