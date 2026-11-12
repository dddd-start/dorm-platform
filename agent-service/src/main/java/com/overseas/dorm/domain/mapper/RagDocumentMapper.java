package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库文档数据访问。
 *
 * <p>{@code indexed_version} 与 {@code version} 的比对决定"是否需要重新向量化"，
 * 这是避免每次启动都全量重建索引的关键。
 */
@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {


    @Select("SELECT * FROM rag_document WHERE doc_code = #{docCode} LIMIT 1")
    RagDocument selectByCode(@Param("docCode") String docCode);
}
