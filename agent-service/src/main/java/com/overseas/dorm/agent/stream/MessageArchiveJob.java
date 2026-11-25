package com.overseas.dorm.agent.stream;

import com.overseas.dorm.common.constant.BusinessLimits;
import com.overseas.dorm.common.enums.ConversationStatus;
import com.overseas.dorm.domain.mapper.AgentMessageMapper;
import com.overseas.dorm.domain.mapper.AgentConversationMapper;
import com.overseas.dorm.domain.entity.AgentConversation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息冷热分离任务：把"已归档会话"中的旧消息搬到归档表。
 *
 * {@code agent_message} 每条消息都含 LONGTEXT（正文与思考过程往往各上千字），
 * 量级增长很快。归档表 {@code agent_message_archive} 与迁移 Mapper 早已就绪，
 * 但<b>此前没有任何地方调用它们</b>，也就是说"冷热分离"只存在于文档里，
 * 热表只增不减。
 *
 * 迁移按时间全局进行的话，活跃会话的历史会被搬走，
 * 用户打开一个旧对话会看到"消息不见了"，这比表变大严重得多。
 * 用户已主动归档的会话本来就不在活跃列表里，其历史只在审计时需要，
 * 放进冷表正合适。
 *
 * 一次搬几百万行会把库拖垮，也会让这个定时任务长时间持锁。
 * 每轮搬一小批、独立提交，可随时中断、可重入，没搬完的下一轮接着搬。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageArchiveJob {

    /** 单轮最多搬迁多少条（分批搬迁，避免长事务）。 */
    private static final int ARCHIVE_BATCH_SIZE = 500;

    /** 单轮最多循环几批，避免单次任务运行过久。 */
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final AgentMessageMapper messageMapper;
    private final AgentConversationMapper conversationMapper;

    /** 冷数据保留天数：早于这个时间的消息才允许搬走。 */
    @Value("${dorm.agent.archive.retention-days:30}")
    private int retentionDays;

    /** 任务开关（默认开启；演示环境想保留全部热数据可关掉）。 */
    @Value("${dorm.agent.archive.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${dorm.agent.archive.cron:0 15 3 * * ?}")
    public void archiveOldMessages() {
        if (!enabled) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
        String archived = ConversationStatus.ARCHIVED.name();
        int movedTotal = 0;
        for (int round = 1; round <= MAX_BATCHES_PER_RUN; round++) {
            int moved = messageMapper.archiveBefore(before, archived, ARCHIVE_BATCH_SIZE);
            if (moved == 0) {
                break;
            }
            // 先归档再删除：顺序不能颠倒。反过来的话，一旦删除成功而归档失败，
            // 这批消息就永久丢了 —— 审计链断裂是不可接受的。
            int deleted = messageMapper.deleteBefore(before, archived, ARCHIVE_BATCH_SIZE);
            movedTotal += moved;
            log.debug("[归档] 第 {} 批：归档 {} 条、清理 {} 条", round, moved, deleted);
            if (moved < ARCHIVE_BATCH_SIZE) {
                break;
            }
        }
        if (movedTotal > 0) {
            log.info("[归档] 本轮共搬迁 {} 条消息（保留期 {} 天，范围：已归档会话）",
                    movedTotal, retentionDays);
        }
    }

    /**
     * 清理"已归档会话"中已经空掉、且超过保留期的会话记录。
     *
     * <p>会话本身很小，但用户每建一次对话就留一行，长期同样会累积。
     * 只删除确实没有剩余消息的已归档会话，避免出现"有消息却没有会话"的孤儿数据。
     */
    @Scheduled(cron = "${dorm.agent.archive.cron:0 15 3 * * ?}")
    public void purgeEmptyArchivedConversations() {
        if (!enabled) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
        List<AgentConversation> candidates = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getStatus, ConversationStatus.ARCHIVED)
                        .lt(AgentConversation::getLastMessageTime, before)
                        .last("LIMIT " + ARCHIVE_BATCH_SIZE));
        int removed = 0;
        for (AgentConversation conversation : candidates) {
            // 只有当这个会话在热表里已经没有消息（都已搬进归档表）时才删会话行，
            // 否则会留下"有消息却没有会话"的孤儿数据，消息将永远无法被正确查询
            if (messageMapper.selectMaxSeqNo(conversation.getId()) == 0) {
                conversationMapper.deleteById(conversation.getId());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[归档] 清理空会话 {} 个", removed);
        }
    }
}
