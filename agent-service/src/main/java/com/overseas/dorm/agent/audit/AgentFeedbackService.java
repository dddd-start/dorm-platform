package com.overseas.dorm.agent.audit;

import com.overseas.dorm.agent.stream.ConversationService;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.enums.FeedbackRating;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.domain.entity.AgentFeedback;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.domain.mapper.AgentFeedbackMapper;
import com.overseas.dorm.domain.mapper.AgentMessageMapper;
import com.overseas.dorm.domain.mapper.RagRetrievalLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 回答反馈（点赞 / 点踩）。
 *
 * <h3>为什么把它从控制器里搬出来</h3>
 * 这个能力此前直接写在 {@code AgentController} 里，并因此带着两个真实缺陷：
 * <ol>
 *   <li><b>反馈从未成功写入过。</b> {@code agent_feedback.conversation_id} 是 NOT NULL，
 *       而插入时从未赋值 —— MyBatis-Plus 默认跳过 null 字段，于是数据库直接报
 *       "Field 'conversation_id' doesn't have a default value"，接口返回 9999。
 *       也就是说<b>前端每一次点赞/点踩都失败</b>，库里反馈行数长期为 0；</li>
 *   <li><b>没有归属校验。</b> 任何登录用户都可以对别人的消息提交反馈，
 *       既污染统计也可能被用来刷数据。</li>
 * </ol>
 * 业务规则（补全字段、校验归属、同步检索日志）本来就该由服务层承担，
 * 控制器只做参数解析与结果包装 —— 分层不是形式主义：把这三件事写在控制器里，
 * 就会像上面那样漏掉其中两件，而且没有任何测试会因此失败
 * （因为当时的 E2E 根本没覆盖反馈接口）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFeedbackService {

    private final AgentFeedbackMapper feedbackMapper;
    private final AgentMessageMapper messageMapper;
    private final RagRetrievalLogMapper retrievalLogMapper;
    private final ConversationService conversationService;

    /**
     * 提交反馈。
     *
     * @return true 表示新增，false 表示更新了已有反馈（同一人对同一条消息只保留一份）
     */
    public boolean submit(Long messageId, Long userId, FeedbackRating rating, String reason) {
        if (rating == null || rating == FeedbackRating.NONE) {
            throw BizException.param("rating 只能是 LIKE 或 DISLIKE");
        }
        AgentMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "消息不存在");
        }
        // 归属校验：只能给自己的会话里的消息反馈
        if (conversationService.requireOwned(message.getConversationId(), userId) == null) {
            throw BizException.of(ResultCode.FORBIDDEN, "无权对该消息提交反馈");
        }
        AgentFeedback entity = new AgentFeedback();
        // conversation_id 必须显式赋值：表上是 NOT NULL 且没有默认值
        entity.setConversationId(message.getConversationId());
        entity.setMessageId(messageId);
        entity.setUserId(userId);
        entity.setRating(rating);
        entity.setReason(reason);

        boolean inserted;
        try {
            feedbackMapper.insert(entity);
            inserted = true;
        } catch (DuplicateKeyException e) {
            // 唯一索引（message_id + user_id）挡住重复提交：改成更新
            AgentFeedback existing = feedbackMapper.selectOne(
                    new LambdaQueryWrapper<AgentFeedback>()
                            .eq(AgentFeedback::getMessageId, messageId)
                            .eq(AgentFeedback::getUserId, userId)
                            .last("LIMIT 1"));
            if (existing == null) {
                throw e;
            }
            existing.setRating(rating);
            existing.setReason(reason);
            feedbackMapper.updateById(existing);
            inserted = false;
        }
        syncRetrievalLog(messageId, rating);
        return inserted;
    }

    /**
     * 把反馈同步到检索日志 —— 质量闭环里最容易断的一环。
     *
     * <p>运维看板的点踩率读的是 {@code rag_retrieval_log.feedback}，而用户反馈写在
     * {@code agent_feedback}：两张表之间若不打通，点踩率会恒为 0，
     * "哪个问题答得不好"这条最重要的运营线索就彻底丢失。
     *
     * <p>回写失败不影响反馈本身（主记录已落库），因此这里只记日志。
     */
    private void syncRetrievalLog(Long messageId, FeedbackRating rating) {
        try {
            retrievalLogMapper.updateFeedbackByMessage(messageId, rating);
        } catch (Exception e) {
            log.warn("[Agent] 检索日志反馈回写失败 messageId={}", messageId, e);
        }
    }

    /** 某条消息上的反馈评分（用于回放界面展示）。 */
    public FeedbackRating ratingOf(Long messageId, Long userId) {
        AgentFeedback found = feedbackMapper.selectOne(new LambdaQueryWrapper<AgentFeedback>()
                .eq(AgentFeedback::getMessageId, messageId)
                .eq(AgentFeedback::getUserId, userId)
                .last("LIMIT 1"));
        return found == null ? null : found.getRating();
    }

    /** 供 E2E 与运维检查：某会话的反馈统计。 */
    public long countByConversation(Long conversationId, FeedbackRating rating) {
        Long count = feedbackMapper.selectCount(new LambdaQueryWrapper<AgentFeedback>()
                .eq(AgentFeedback::getConversationId, conversationId)
                .eq(rating != null, AgentFeedback::getRating, rating));
        return count == null ? 0L : count;
    }

    /** 消息角色的安全校验（避免对系统消息反馈）。 */
    public boolean feedbackAllowed(Long messageId) {
        AgentMessage message = messageMapper.selectById(messageId);
        return message != null && message.getRole() == AgentEnums.Role.ASSISTANT;
    }
}
