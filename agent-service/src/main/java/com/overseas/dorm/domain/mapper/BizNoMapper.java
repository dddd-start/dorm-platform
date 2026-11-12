package com.overseas.dorm.domain.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Agent 库的单号播种查询。
 *
 * <p>与业务服务的 BizNoMapper 分开：Agent 只能看到自己库里的
 * {@code agent_conversation / agent_task / agent_pending_approval}，
 * 对业务单号（申请/订单）返回 0，避免跨库查询。
 */
@Mapper
public interface BizNoMapper {

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

    /** 业务单号不属于 Agent 库。 */
    default long maxApplySeq(String prefix, int prefixLen) {
        return 0L;
    }

    /** 业务单号不属于 Agent 库。 */
    default long maxOrderSeq(String prefix, int prefixLen) {
        return 0L;
    }

    @Select("SELECT COUNT(*) FROM agent_conversation WHERE conversation_no = #{no}")
    int countConversationNo(@Param("no") String no);

    @Select("SELECT COUNT(*) FROM agent_task WHERE task_no = #{no}")
    int countTaskNo(@Param("no") String no);

    @Select("SELECT COUNT(*) FROM agent_pending_approval WHERE approval_no = #{no}")
    int countApprovalNo(@Param("no") String no);

    default int countApplyNo(String no) {
        return 0;
    }

    default int countOrderNo(String no) {
        return 0;
    }
}
