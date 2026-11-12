package com.overseas.dorm.domain.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 业务单号相关的查询。
 *
 * <p>存在的唯一理由：单号是「{{前缀}{yyyyMMdd}{4 位序号}」的形式，
 * 而序号的分配依赖 Redis 自增。这个方案在正常情况下完全够用，
 * 但有一个必须兜住的失效场景 ——
 * <b>Redis 数据丢失或换库后，自增会从 1 重新开始，与库里已有单号撞唯一索引</b>，
 * 结果是"所有业务单据突然都提交不了"。
 *
 * <p>本项目在压测时真实触发过这个问题（压测实例使用独立 Redis db，
 * 序号从 1 开始，立刻与库中既有会话号冲突）。
 * 因此这里提供「按类型取当天最大序号」的能力，用于在 Redis 计数缺失时播种。
 */
@Mapper
public interface BizNoMapper {

    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(apply_no, #{prefixLen} + 1) AS UNSIGNED)), 0)
              FROM stay_apply
             WHERE apply_no LIKE CONCAT(#{prefix}, '%')
            """)
    long maxApplySeq(@Param("prefix") String prefix, @Param("prefixLen") int prefixLen);

    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(order_no, #{prefixLen} + 1) AS UNSIGNED)), 0)
              FROM stay_order
             WHERE order_no LIKE CONCAT(#{prefix}, '%')
            """)
    long maxOrderSeq(@Param("prefix") String prefix, @Param("prefixLen") int prefixLen);

    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(conversation_no, #{prefixLen} + 1) AS UNSIGNED)), 0)
              FROM agent_conversation
             WHERE conversation_no LIKE CONCAT(#{prefix}, '%')
            """)
    long maxConversationSeq(@Param("prefix") String prefix, @Param("prefixLen") int prefixLen);

    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(task_no, #{prefixLen} + 1) AS UNSIGNED)), 0)
              FROM agent_task
             WHERE task_no LIKE CONCAT(#{prefix}, '%')
            """)
    long maxTaskSeq(@Param("prefix") String prefix, @Param("prefixLen") int prefixLen);

    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(approval_no, #{prefixLen} + 1) AS UNSIGNED)), 0)
              FROM agent_pending_approval
             WHERE approval_no LIKE CONCAT(#{prefix}, '%')
            """)
    long maxApprovalSeq(@Param("prefix") String prefix, @Param("prefixLen") int prefixLen);

    @Select("SELECT COUNT(*) FROM stay_apply WHERE apply_no = #{no}")
    int countApplyNo(@Param("no") String no);

    @Select("SELECT COUNT(*) FROM stay_order WHERE order_no = #{no}")
    int countOrderNo(@Param("no") String no);

    @Select("SELECT COUNT(*) FROM agent_conversation WHERE conversation_no = #{no}")
    int countConversationNo(@Param("no") String no);

    @Select("SELECT COUNT(*) FROM agent_task WHERE task_no = #{no}")
    int countTaskNo(@Param("no") String no);

    @Select("SELECT COUNT(*) FROM agent_pending_approval WHERE approval_no = #{no}")
    int countApprovalNo(@Param("no") String no);
}
