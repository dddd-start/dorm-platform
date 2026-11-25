package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AI 消息数据访问。
 *
 * <p>两条不变量由本接口配合唯一索引保证：
 * <ol>
 *   <li>{@code (conversation_id, seq_no)} 唯一，会话内消息不乱序；</li>
 *   <li>{@code turn_id} 唯一，同一轮重复提交只落一条助手消息（幂等）。</li>
 * </ol>
 * 归档相关方法按 LIMIT 分批搬迁，便于随时中断与重入。
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    /**
     * 取会话内当前最大序号。
     *
     * <p>新消息的序号 = max + 1，并由 {@code uk_msg_conv_seq} 唯一索引兜底。
     * 为什么不直接用自增列做序号：会话之间相互独立，
     * 用全局自增会出现「同一会话内序号跳跃很大」，
     * 前端做断点续传时缺少「会话内连续序号」这个语义。
     */
    @Select("SELECT COALESCE(MAX(seq_no), 0) FROM agent_message WHERE conversation_id = #{conversationId}")
    int selectMaxSeqNo(@Param("conversationId") Long conversationId);

    /** 按会话倒序取最近 N 条（短期记忆窗口），返回结果按正序方便直接拼上下文。 */
    @Select("""
            SELECT * FROM (
              SELECT * FROM agent_message
               WHERE conversation_id = #{conversationId}
               ORDER BY seq_no DESC
               LIMIT #{limit}
            ) t ORDER BY t.seq_no
            """)
    List<AgentMessage> selectRecent(@Param("conversationId") Long conversationId, @Param("limit") int limit);

    @Select("SELECT * FROM agent_message WHERE conversation_id = #{conversationId} ORDER BY seq_no")
    List<AgentMessage> selectAll(@Param("conversationId") Long conversationId);

    /**
     * 按 turnId 查找助手消息（幂等复用的关键）。
     *
     * <p><b>为什么必须同时限定 user_id 与会话</b>：改造前这条 SQL 只有
     * {@code WHERE turn_id = ? AND role = 'ASSISTANT'}。turnId 由客户端提供，
     * 于是任意登录用户只要猜到/拿到别人的 turnId，就能通过"幂等重放"这条路径
     * 读到别人的回答（独立验证实测：攻击者用自己的会话 + 受害者的 turnId
     * 调用 /api/agent/chat/stream，收到 replayed=true 与受害者正文 29 字）。
     * 而其它三条访问路径（历史/回放/续传）都被正确拦截，这是一条授权旁路。
     *
     * <p>现在同时限定会话归属：只有"本人在本会话里"的那一轮才允许重放。
     */
    @Select("""
            SELECT m.* FROM agent_message m
              JOIN agent_conversation c ON c.id = m.conversation_id
             WHERE m.turn_id = #{turnId}
               AND m.role = 'ASSISTANT'
               AND c.user_id = #{userId}
               AND m.conversation_id = #{conversationId}
             LIMIT 1
            """)
    AgentMessage selectAssistantByTurn(@Param("turnId") String turnId,
                                       @Param("userId") Long userId,
                                       @Param("conversationId") Long conversationId);

    /**
     * 按会话 + turnId 查找助手消息（用于唯一索引冲突后的回读）。
     *
     * <p>为什么这里可以不带 user_id 条件：调用方（{@code ConversationService.beginTurn}）
     * 的上游已经用 {@code requireOwned(conversationId, userId)} 校验过会话归属，
     * 因此"会话内的那一轮"必然是本人的。把归属校验保留在唯一入口处，
     * 避免为了通过 SQL 传参而把 userId 层层透传进写入路径。
     */
    @Select("""
            SELECT * FROM agent_message
             WHERE conversation_id = #{conversationId}
               AND turn_id = #{turnId}
               AND role = 'ASSISTANT'
             LIMIT 1
            """)
    AgentMessage selectAssistantByConversationAndTurn(@Param("conversationId") Long conversationId,
                                                     @Param("turnId") String turnId);

    /**
     * 冷热分离：把指定会话集合中 N 天前的消息搬进归档表。
     *
     * <p><b>为什么要限定会话状态</b>：如果按时间全局搬迁，活跃会话的历史会被搬走，
     * 用户打开旧对话会看到"消息不见了"，这是比"表变大"严重得多的问题。
     * 因此归档只针对用户已主动归档（{@code ARCHIVED}）的会话：
     * 这些会话本来就不在活跃列表里，其历史只在审计时需要，放到归档表正合适。
     */
    @Update("""
            INSERT INTO agent_message_archive
                (id, conversation_id, seq_no, role, content, reasoning_content,
                 tool_calls_json, references_json, tool_call_id, tool_name, turn_id, model,
                 tokens_in, tokens_out, latency_ms, first_token_ms, status, block_reason,
                 trace_id, create_time, archived_time)
            SELECT m.id, m.conversation_id, m.seq_no, m.role, m.content, m.reasoning_content,
                   m.tool_calls_json, m.references_json, m.tool_call_id, m.tool_name, m.turn_id, m.model,
                   m.tokens_in, m.tokens_out, m.latency_ms, m.first_token_ms, m.status, m.block_reason,
                   m.trace_id, m.create_time, NOW()
              FROM agent_message m
              JOIN agent_conversation c ON c.id = m.conversation_id
             WHERE m.create_time < #{before} AND c.status = #{archivedStatus}
             LIMIT #{limit}
            """)
    int archiveBefore(@Param("before") java.time.LocalDateTime before,
                      @Param("archivedStatus") String archivedStatus,
                      @Param("limit") int limit);

    @Update("""
            DELETE m FROM agent_message m
              JOIN agent_conversation c ON c.id = m.conversation_id
             WHERE m.create_time < #{before} AND c.status = #{archivedStatus}
             LIMIT #{limit}
            """)
    int deleteBefore(@Param("before") java.time.LocalDateTime before,
                     @Param("archivedStatus") String archivedStatus,
                     @Param("limit") int limit);
}
