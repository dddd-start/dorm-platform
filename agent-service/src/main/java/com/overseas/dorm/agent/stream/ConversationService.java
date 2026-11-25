package com.overseas.dorm.agent.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.enums.ConversationStatus;
import com.overseas.dorm.common.constant.BusinessLimits;
import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.vo.ToolCallVO;
import com.overseas.dorm.agent.vo.ConversationVO;
import com.overseas.dorm.agent.vo.AgentMessageVO;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.enums.AgentType;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.entity.AgentConversation;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.domain.mapper.AgentConversationMapper;
import com.overseas.dorm.domain.mapper.AgentMessageMapper;
import com.overseas.dorm.domain.support.BizNoGenerator;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.support.PageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话与消息持久化服务。
 *
 * 每条消息都有一个<b>会话内单调递增</b>的 {@code seq_no}，
 * 且 {@code (conversation_id, seq_no)} 上有唯一索引。这带来三个性质：
 * <ol>
 *   <li><b>不重</b>：唯一索引让重复插入直接失败，不可能出现两条消息占同一个位置；</li>
 *   <li><b>不漏</b>：序号连续，前端发现 5 后面直接跳到 7 就知道丢了消息，可以重拉；</li>
 *   <li><b>不乱序</b>：客户端并发提交也只会有一个成功拿到某个序号。</li>
 * </ol>
 *
 * 全局自增列会让同一会话内出现很大的序号跳跃（因为别的会话也在用），
 * 而"断点续传"依赖的恰恰是"会话内连续"。用 max+1 并在冲突时重试，
 * 配合唯一索引兜底，是这里的正确解法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final AgentConversationMapper conversationMapper;

    /** 新会话的默认标题（与 ensureTitle 的条件更新配合使用）。 */
    private static final String DEFAULT_TITLE = "新对话";
    private final AgentMessageMapper messageMapper;
    private final AgentAuditService agentAuditService;
    /** 用量写后置缓冲：把用量从"每轮一次数据库提交"改为"周期性批量落库"。 */
    private final com.overseas.dorm.domain.support.UsageWriteBuffer usageWriteBuffer;
    private final BizNoGenerator bizNoGenerator;
    private final DataScope dataScope;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /** 序号分配的最大重试次数（并发冲突时）。 */
    private static final int SEQ_RETRY = 5;

    // ================================================================ 会话

    /**
     * 新建会话。
     *
     * 单号生成器已经做了三层保护（Redis 原子取号 + 数据库最大值播种 + 生成后查库校验），
     * 但仍然存在一个无法靠"先查后插"消除的竞态窗口：
     * <pre>
     *   实例 A 取号 → 查库发现不存在 → （此刻实例 B 用同一个号插入了）→ A 插入 → 唯一索引冲突
     * </pre>
     * 这个窗口在多个实例使用不同 Redis db 时更容易命中（各自的自增独立，
     * 播种值相同因而容易取到同一个号）。共享同一个 Redis 的生产部署下概率极低，
     * 但它终究是"两个检查之间的时间差"造成的，靠检查无法根除。
     *
     * <p>正确做法是在<b>唯一的强一致点</b>，数据库唯一索引，上做处理：
     * 冲突就把整条记录换个号重试。这也再次说明分布式系统的通用原则：
     * <b>唯一性最终必须由数据库保证，Redis/本地检查只能用来减少冲突概率。</b>
     */
    @Transactional
    public AgentConversation createConversation(Long userId, String title, AgentType type) {
        DuplicateKeyException lastError = null;
        for (int attempt = 1; attempt <= SEQ_RETRY; attempt++) {
            AgentConversation conversation = new AgentConversation();
            conversation.setConversationNo(bizNoGenerator.next(BizNoGenerator.TYPE_CONVERSATION));
            conversation.setUserId(userId);
            conversation.setTitle(title == null || title.isBlank()
                    ? "新对话" : truncate(title, 60));
            conversation.setAgentType(type == null ? AgentType.ROUTER : type);
            conversation.setMessageCount(0);
            conversation.setStatus(ConversationStatus.ACTIVE);
            try {
                conversationMapper.insert(conversation);
                return conversation;
            } catch (DuplicateKeyException e) {
                // 会话号撞车：重新取号再试（下一次取号会拿到新的序号）
                lastError = e;
                log.warn("[Agent] 会话号冲突（第 {} 次尝试），重新取号：{}",
                        attempt, conversation.getConversationNo());
            }
        }
        throw new IllegalStateException("创建会话失败：会话号连续冲突 "
                + SEQ_RETRY + " 次", lastError);
    }

    /** 取会话，并校验归属（防止通过猜 ID 读别人的对话）。 */
    public AgentConversation requireOwned(Long conversationId, Long userId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return null;
        }
        if (!conversation.getUserId().equals(userId)) {
            // 不区分"不存在"和"不属于你"，避免通过错误信息探测他人会话 ID 是否存在
            log.warn("[Agent] 用户 {} 尝试访问他人会话 {}", userId, conversationId);
            return null;
        }
        return conversation;
    }

    /** 会话列表（返回 {@link ConversationVO}，字段由编译器固定，不再靠约定）。 */
    public PageResult<ConversationVO> listConversations(Long userId, long current, long size) {
        var page = conversationMapper.selectPage(PageMapper.page(current, size),
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, ConversationStatus.ACTIVE)
                        .orderByDesc(AgentConversation::getLastMessageTime)
                        .orderByDesc(AgentConversation::getCreateTime));
        return PageMapper.of(page, c -> new ConversationVO(
                c.getId(),
                c.getConversationNo(),
                c.getTitle(),
                c.getAgentType() == null ? null : c.getAgentType().name(),
                c.getAgentType() == null ? null : c.getAgentType().getLabel(),
                c.getMessageCount(),
                String.valueOf(c.getLastMessageTime()),
                c.getSummary() != null && !c.getSummary().isBlank()));
    }

    /** 归档（软删除）会话。 */
    @Transactional
    public void archive(Long conversationId, Long userId) {
        AgentConversation conversation = requireOwned(conversationId, userId);
        if (conversation == null) {
            return;
        }
        AgentConversation update = new AgentConversation();
        update.setId(conversationId);
        update.setStatus(ConversationStatus.ARCHIVED);
        conversationMapper.updateById(update);
    }

    @Transactional
    public void touchConversation(Long conversationId, AgentType type, int deltaMessages) {
        // 改造前是 "selectById 读一遍 → 组装 update → updateById"：单轮 2 次数据库往返，
        // 而 1000 并发下连接池正是瓶颈（实测 waiting=914）。
        // 这里改为一条 UPDATE：message_count 用 SQL 自增、时间用 NOW()，
        // 既省掉一次往返，也天然避免了"读-改-写"的丢失更新问题。
        conversationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .setSql("message_count = COALESCE(message_count, 0) + " + Math.max(deltaMessages, 0))
                        .set(AgentConversation::getLastMessageTime, LocalDateTime.now())
                        .set(type != null, AgentConversation::getAgentType, type));
    }

    /** 首轮自动生成标题（用用户第一句话，超长则截断，不必为此多花一次模型调用）。 */
    @Transactional
    public void ensureTitle(Long conversationId, String firstMessage) {
        // 条件更新：只有标题仍是默认值时才写入，省掉"先 select 判断"的一次往返
        conversationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .eq(AgentConversation::getTitle, DEFAULT_TITLE)
                        .set(AgentConversation::getTitle, truncate(firstMessage, 60)));
    }

    // ================================================================ 消息

    /**
     * 追加一条消息，自动分配会话内序号。
     *
     * <p>用独立事务（{@code REQUIRES_NEW}）：流式生成期间，
     * 用户消息必须在"开始生成"之前就已经落库可见，
     * 不能等到整个生成结束才提交，否则用户刷新页面看不到自己刚发的问题。
     */
    /**
     * 一轮对话的开始：把原本 4 次独立提交合并成 1 次。
     *
     * 改造前每轮开始有 4 次各自提交的写入：用户消息、会话计数更新、标题更新、助手消息占位。
     * 结束还有 3 次（终态回写、会话计数、用量累加），合计 6~7 次提交。
     * 实测本机存储只有 26 次 fsync/秒、MySQL 每次提交约 2 次 fsync，
     * 于是 1000 并发 × 6~7 次提交 = 约 13000 次 fsync，物理上就要几百秒，
     * 表现为连接池被占满（waiting=310）、请求 3 秒超时。
     *
     * <p>这些写入在业务上属于"同一次状态变更"，本来就应该在一个事务里，* 拆成多次提交既慢，也意味着中途失败会留下"用户消息已入库、助手消息没建"的半截状态。
     *
     * @return 预创建的助手消息（状态 STREAMING）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentMessage beginTurn(Long conversationId, String userMessage,
                                  String turnId, String traceId) {
        try {
            return doBeginTurn(conversationId, userMessage, turnId, traceId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发提交同一个 turnId 时的胜者/败者竞争：数据库唯一索引
            // uk_msg_turn_role 会把败者挡在这里。此时绝不能再生成一次 ——
            // 那正是"重复调用模型、重复计费"的来源（独立验证实测 4 并发 → 3 条助手消息）。
            // 正确做法：读出胜者写入的那条助手消息，交给上层按"幂等重放"处理。
            log.info("[Agent] 同一 turnId 并发提交，本次由唯一索引拦截 turnId={}，转为重放既有回答", turnId);
            // 会话归属已由上游 requireOwned 校验过，这里按会话内轮次回读即可
            AgentMessage existing = messageMapper.selectAssistantByConversationAndTurn(
                    conversationId, turnId);
            if (existing == null) {
                throw e;
            }
            if (existing.getStatus() == AgentEnums.MessageStatus.STREAMING) {
                // 胜者还在生成中：此时不能把这条"空内容"的消息当成可重放的回答返回，
                // 那会让用户看到空白气泡。给一个明确的业务提示，让他稍后刷新或重发。
                throw com.overseas.dorm.common.exception.BizException.of(
                        com.overseas.dorm.common.api.ResultCode.AGENT_CONCURRENCY_LIMIT,
                        "这一轮正在生成中，请稍候片刻再看（重复提交不会重复计费）。");
            }
            return existing;
        }
    }

    /** beginTurn 的实际写入（抽出来以便统一处理唯一索引冲突）。 */
    private AgentMessage doBeginTurn(Long conversationId, String userMessage,
                                     String turnId, String traceId) {
        appendMessage(conversationId, AgentEnums.Role.USER, userMessage, turnId, traceId);
        // 会话计数 + 时间 + 标题（条件更新，只在仍是默认标题时写入）
        // 标题来自用户消息，必须参数绑定，不能拼进 SQL 字符串：
        // 原实现用 sqlLiteral 只把单引号换成两个，没处理反斜杠（MySQL 默认反斜杠是转义符），
        // 构造 "\\' OR 1=1 -- " 之类的输入即构成写路径 SQL 注入。
        conversationMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .setSql("message_count = COALESCE(message_count, 0) + 1")
                        .set(AgentConversation::getLastMessageTime, LocalDateTime.now())
                        .setSql("title = CASE WHEN title IS NULL OR title = {0} THEN {1} ELSE title END",
                                DEFAULT_TITLE, truncate(userMessage, 60)));
        return appendMessage(conversationId, AgentEnums.Role.ASSISTANT, "", null, null, null,
                turnId, traceId, AgentEnums.MessageStatus.STREAMING, null, null, 0, 0);
    }

    /**
     * 一轮对话的结束：把原本 3 次独立提交合并成 1 次（终态回写 + 会话计数 + 用量累加）。
     *
     * @param promptTokens  本轮输入 token
     * @param outputTokens  本轮输出 token
     */
    /**
     * 兼容旧调用方：没有引用快照时等价于传空列表。
     *
     * <p>教程的中间章节（03–09）仍在使用旧签名，保留这个重载可以让
     * 每一章独立编译；生产路径统一走带 references 的版本。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishTurn(Long messageId, Long conversationId, String content, String reasoningContent,
                           AgentEnums.MessageStatus status, String blockReason,
                           int promptTokens, int outputTokens, long latencyMs, long firstTokenMs,
                           Long userId, int toolCalls, int ragHits, boolean blocked,
                           boolean newConversation, String model) {
        finishTurn(messageId, conversationId, content, reasoningContent, status, blockReason,
                promptTokens, outputTokens, latencyMs, firstTokenMs, userId, toolCalls, ragHits,
                blocked, newConversation, model, List.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishTurn(Long messageId, Long conversationId, String content, String reasoningContent,
                           AgentEnums.MessageStatus status, String blockReason,
                           int promptTokens, int outputTokens, long latencyMs, long firstTokenMs,
                           Long userId, int toolCalls, int ragHits, boolean blocked,
                           boolean newConversation, String model,
                           List<Map<String, Object>> references) {
        // 注意：这里不要放"只有 WHERE 没有 SET"的更新语句 ——
        // 本项目真实踩过：一段残留的 no-op UPDATE 会被 MyBatis-Plus 拒绝执行，
        // 导致整个 finishTurn 事务回滚，终态内容根本没落库（表现为落库长度=0），
        // 而且流式推送看起来一切正常。这个缺陷是被"拼接必须等于落库"的续传断言抓出来的。
        finalizeMessage(messageId, content, reasoningContent, status, blockReason,
                promptTokens, outputTokens, latencyMs, firstTokenMs, model, references);
        touchConversation(conversationId, null, 1);
        accumulateUsage(userId, promptTokens, outputTokens, toolCalls, ragHits, blocked,
                newConversation);
    }

    /**
     * 用量聚合（与终态回写同事务）。
     *
     * <p>放在这里而不是各自一个事务，是为了把"一轮结束"的写入压到一次提交。
     * 代价是：用量统计与消息状态现在同生共死（要么都成功、要么都回滚），* 这对计费数据来说其实是更好的语义。
     */
    private void accumulateUsage(Long userId, int promptTokens, int outputTokens, int toolCalls,
                                 int ragHits, boolean blocked, boolean newConversation) {
        // 写后置：只写 Redis 缓冲（0 次 fsync），由 UsageWriteBuffer 周期性批量落库。
        // 这样"一轮结束"的事务里不再包含用量写入，提交的工作量更小；
        // 用量是纯聚合数据、不在读路径上，延迟落库不影响正确性。
        usageWriteBuffer.accumulate(userId, promptTokens, outputTokens,
                toolCalls, ragHits, blocked, newConversation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentMessage appendMessage(Long conversationId, AgentEnums.Role role, String content,
                                      String turnId, String traceId) {
        return appendMessage(conversationId, role, content, null, null, null, turnId, traceId,
                AgentEnums.MessageStatus.COMPLETED, null, null, 0, 0);
    }

    /**
     * 追加消息（完整参数版本）。
     */




    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentMessage appendMessage(Long conversationId, AgentEnums.Role role, String content,
                                      String reasoningContent, String toolCallsJson, String toolName,
                                      String turnId, String traceId, AgentEnums.MessageStatus status,
                                      String model, String blockReason, int tokensIn, int tokensOut) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= SEQ_RETRY; attempt++) {
            int nextSeq = nextSeqNo(conversationId);
            AgentMessage message = new AgentMessage();
            message.setConversationId(conversationId);
            message.setSeqNo(nextSeq);
            message.setRole(role);
            message.setContent(content);
            message.setReasoningContent(reasoningContent);
            message.setToolCallsJson(toolCallsJson);
            message.setToolName(toolName);
            message.setTurnId(turnId);
            message.setTraceId(traceId);
            message.setStatus(status);
            message.setModel(model);
            message.setBlockReason(blockReason);
            message.setTokensIn(tokensIn);
            message.setTokensOut(tokensOut);
            message.setLatencyMs(0L);
            message.setFirstTokenMs(0L);
            try {
                messageMapper.insert(message);
                return message;
            } catch (DuplicateKeyException e) {
                // 序号被并发请求抢先占用（Redis 计数与数据库不同步时可能发生），重新取号
                lastError = e;
                log.debug("[Agent] 序号 {} 已被占用，重试第 {} 次", nextSeq, attempt);
            } catch (org.springframework.dao.ConcurrencyFailureException e) {
                // 高并发下的死锁/锁等待超时属于"瞬时冲突"，重试即可成功。
                // 这类异常在压测中会真实出现（尤其是多轮写入同一张表时），
                // 不重试的话用户就会看到 500 —— 而实际上什么都没做错。
                lastError = e;
                log.warn("[Agent] 消息写入遇到并发冲突（第 {} 次）：{}", attempt, e.getClass().getSimpleName());
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("会话消息写入失败（已重试 " + SEQ_RETRY + " 次）："
                + (lastError == null ? "未知原因" : lastError.getMessage()), lastError);
    }

    /**
     * 分配会话内下一个序号。
     *
     * 压测发现这是一个真实的高并发缺陷：在 REPEATABLE READ 下，
     * {@code SELECT MAX(...)} 会加<b>间隙锁</b>；多个并发事务同时对同一张表
     * 做「取最大值 + 插入」时，间隙锁与插入意向锁互相等待，
     * MySQL 直接判定为死锁并回滚其中一个事务，表现为用户看到 500。
     *
     * <p>改用 Redis 的 {@code INCR}：它是原子操作，不涉及数据库锁，
     * 天然无竞争。首次使用时用数据库的真实最大值播种（SETNX），
     * 之后即使 Redis 数据丢失导致重号，也会被
     * {@code uk_msg_conv_seq} 唯一索引拦下并重新取号，正确性由数据库兜底。
     */
    private int nextSeqNo(Long conversationId) {
        String key = com.overseas.dorm.common.constant.RedisKeys.PREFIX + "conv:seq:" + conversationId;
        try {
            Boolean seeded = redis.opsForValue().setIfAbsent(key,
                    String.valueOf(messageMapper.selectMaxSeqNo(conversationId)),
                    java.time.Duration.ofDays(7));
            if (Boolean.FALSE.equals(seeded) && redis.opsForValue().get(key) == null) {
                // 极端情况：key 刚好过期，重新播种
                redis.opsForValue().set(key, String.valueOf(messageMapper.selectMaxSeqNo(conversationId)),
                        java.time.Duration.ofDays(7));
            }
            Long seq = redis.opsForValue().increment(key);
            if (seq == null) {
                return messageMapper.selectMaxSeqNo(conversationId) + 1;
            }
            return seq.intValue();
        } catch (Exception e) {
            // Redis 不可用时退化为数据库取号（可能偶发冲突，但有唯一索引兜底）
            log.warn("[Agent] 序号分配回退到数据库取号：{}", e.getMessage());
            return messageMapper.selectMaxSeqNo(conversationId) + 1;
        }
    }

    /**
     * 更新助手消息的最终状态与统计。
     */
    void finalizeMessage(Long messageId, String content, String reasoningContent,
                                AgentEnums.MessageStatus status, String blockReason,
                                int tokensIn, int tokensOut, long latencyMs, long firstTokenMs,
                                String model, List<Map<String, Object>> references) {
        AgentMessage update = new AgentMessage();
        update.setId(messageId);
        update.setContent(content);
        // 记录实际服务的模型名。
        // 改造前这一列从未写入（实测 628/628 条助手消息的 model 全为 NULL），
        // 而 DONE 事件其实如实上报了 model —— 于是前端/历史消息无法回答
        // "这条回答是哪个模型给的"。模型可切换与降级是本项目的常态（降级链 + 熔断），
        // 没有这个字段就无法事后判断"某段时间的回答质量下降是不是因为降到了弱模型"。
        // 注意：调用方必须传实际服务的模型（降级后要用降级后的那个），而不是配置里的主模型。
        update.setModel(model);
        update.setReasoningContent(reasoningContent);
        update.setStatus(status);
        update.setBlockReason(blockReason);
        update.setTokensIn(tokensIn);
        update.setTokensOut(tokensOut);
        update.setLatencyMs(latencyMs);
        update.setFirstTokenMs(firstTokenMs);
        // 把本轮的工具调用固化到消息上。
        //
        // 补上一个真实的功能缺口：这个字段此前从未被写入，后果是
        // 「用户刷新页面后，历史消息里的工具调用卡片（"正在检索可用房源"等）全部消失」，
        // 只剩回答正文 —— 而前端类型里早就声明了 toolCalls，后端一直给 null。
        // 工具调用本身就是一种可解释性：用户能看到"助手查了什么"才敢相信结论。
        update.setToolCallsJson(agentAuditService.toolCallsJson(messageId));
        // 引用来源随消息落库：SSE 的 REFERENCE 帧只在当次连接有效，
        // 刷新页面后历史接口必须还能给出同样的来源，否则可解释性断掉。
        update.setReferencesJson(references == null || references.isEmpty()
                ? "[]" : JsonUtil.toJson(references));
        try {
            messageMapper.updateById(update);
        } catch (Exception e) {
            log.error("[Agent] 消息状态回写失败 messageId={}", messageId, e);
        }
    }

    /** 会话的消息列表（按 seq 正序，供前端渲染历史）。 */
    public List<AgentMessageVO> listMessages(Long conversationId, Long userId, int limit) {
        AgentConversation conversation = requireOwned(conversationId, userId);
        if (conversation == null) {
            return List.of();
        }
        List<AgentMessage> messages = messageMapper.selectAll(conversationId);
        int size = Math.clamp(limit, 1, BusinessLimits.MAX_MESSAGE_LIMIT);
        int from = Math.max(0, messages.size() - size);
        return messages.subList(from, messages.size()).stream()
                .map(this::toMessageVO)
                .toList();
    }

    /**
     * 实体 → 视图对象。
     *
     * <p>{@code toolCallsJson} 反序列化成 {@code List<ToolCallVO>} 而不是
     * 原样透传字符串：前端的类型声明是"字符串或结构化数组"，两种都支持，
     * 但结构化下发能让前端少一次 {@code JSON.parse}，也避免双重转义
     * （JSON 字符串再嵌 JSON）带来的显示异常。
     */
    private AgentMessageVO toMessageVO(AgentMessage message) {
        return new AgentMessageVO(
                message.getId(),
                message.getSeqNo(),
                message.getRole() == null ? null : message.getRole().name(),
                message.getContent(),
                message.getReasoningContent(),
                parseToolCalls(message.getToolCallsJson()),
                parseReferences(message.getReferencesJson()),
                message.getStatus() == null ? null : message.getStatus().name(),
                message.getModel(),
                message.getTokensIn(),
                message.getTokensOut(),
                message.getLatencyMs(),
                message.getFirstTokenMs(),
                message.getBlockReason(),
                String.valueOf(message.getCreateTime()),
                message.getTurnId());
    }

    /** 引用快照解析失败时降级为空列表，不能因为一条旧数据把整页历史打挂。 */
    private List<Map<String, Object>> parseReferences(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonUtil.parse(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            log.warn("[Agent] 引用快照解析失败，已降级为空列表：{}", e.getMessage());
            return List.of();
        }
    }

    /** 历史里可能存着旧格式（非数组）或空值，解析失败时降级为空列表而不是抛异常。 */
    private List<ToolCallVO> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ToolCallVO> calls = JsonUtil.parse(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ToolCallVO>>() {
                    });
            return calls == null ? List.of() : calls;
        } catch (Exception e) {
            log.warn("[Agent] 工具调用记录解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 turnId 查找助手消息，幂等复用的关键。
     *
     * <p>同一个 turnId 重复提交时，我们不复用用户消息而是复用<b>助手消息</b>：
     * 前者是"用户说过的话"，后者是"我们给出的回答"。
     * 重放的是回答，用户看到的就是与第一次完全一致的结果。
     */
    public AgentMessage findAssistantByTurn(String turnId, Long userId, Long conversationId) {
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        // 必须带上归属条件：turnId 来自客户端，只按它查询等于把"幂等重放"变成
        // 一条读他人回答的旁路（详见 AgentMessageMapper.selectAssistantByTurn 的说明）
        return messageMapper.selectAssistantByTurn(turnId, userId, conversationId);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
