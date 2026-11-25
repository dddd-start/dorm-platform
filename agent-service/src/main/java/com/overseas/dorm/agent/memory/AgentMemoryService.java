package com.overseas.dorm.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.domain.entity.AgentConversation;
import com.overseas.dorm.agent.vo.AgentMemoryVO;
import com.overseas.dorm.domain.entity.AgentLongMemory;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.domain.mapper.AgentConversationMapper;
import com.overseas.dorm.domain.mapper.AgentLongMemoryMapper;
import com.overseas.dorm.agent.client.DormServiceClient;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.domain.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 记忆服务：短期与长期记忆的分层实现。
 *
 * <table>
 *   <tr><th>层</th><th>存储</th><th>内容</th><th>生命周期</th></tr>
 *   <tr>
 *     <td>短期记忆</td><td>{@code agent_message}（按窗口读取）</td>
 *     <td>最近 N 条对话原文</td><td>当前会话</td>
 *   </tr>
 *   <tr>
 *     <td>长期记忆</td><td>{@code agent_long_memory}</td>
 *     <td>用户画像、偏好、会话摘要</td><td>跨会话，可过期</td>
 *   </tr>
 * </table>
 *
 * 常见做法是把长期记忆也做成向量库做语义召回。但本系统的长期记忆
 * 是<b>结构化偏好</b>（偏好房型、常去区域、是否介意合住），
 * 条数极少（每人 20 条以内）且有明确的 key。
 * 这种场景下精确的 key 覆盖 + 重要度排序，比向量召回更准、更快、更可控，* 向量召回反而可能因为"语义相近"把「不介意合住」和「介意合住」混在一起，
 * 造成难以排查的错误。技术选型要服务于数据特征，而不是反过来。
 *
 * 在拼装 System Prompt 时，把用户的画像与偏好渲染成一段自然语言，
 * 例如：「该用户为国际业务一部员工，常驻东南亚，偏好单人间，不介意与同事合住。」
 * 模型据此就不再需要每轮都问一遍偏好。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private final AgentLongMemoryMapper memoryMapper;
    private final AgentConversationMapper conversationMapper;
    private final DormServiceClient dormService;
    private final AgentProperties properties;
    private final com.overseas.dorm.agent.config.LangChain4jConfig.ModelFactory modelFactory;
    private final com.overseas.dorm.domain.mapper.AgentMessageMapper messageMapper;

    /** 一轮对话产生两条消息（用户 + 助手），配置里的"轮数"按此换算。 */
    private static final int MESSAGES_PER_TURN = 2;

    /** 摘要覆盖的"较旧消息"最多取多少条（防止一次把整段历史塞给模型）。 */
    private static final int SUMMARY_SOURCE_LIMIT = 60;

    /** 摘要正文的长度上限，避免摘要本身把上下文撑满。 */
    private static final int SUMMARY_MAX_LENGTH = 600;

    /**
     * 渲染长期记忆为可拼入 System Prompt 的文本。
     *
     * <p>返回空字符串表示没有记忆，调用方需要判断，避免把空段落拼进去
     * 浪费 token（每一轮都浪费，累计起来相当可观）。
     */
    public String renderLongTermMemory(Long userId) {
        if (userId == null) {
            return "";
        }
        List<AgentLongMemory> memories = memoryMapper.selectTop(userId,
                properties.getMemory().getMaxLongTermItems());
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【关于当前用户的已知信息】\n");
        for (AgentLongMemory memory : memories) {
            String label = switch (memory.getMemoryType()) {
                case PROFILE -> "画像";
                case PREFERENCE -> "偏好";
                case SUMMARY -> "上次沟通纪要";
            };
            sb.append("- ").append(label).append("：").append(memory.getContent()).append('\n');
        }
        sb.append("（这些是系统此前记录的信息，可直接使用，不必再向用户询问；"
                + "如果与用户当前说法冲突，以用户当前说法为准。）\n");
        return sb.toString();
    }

    /** 渲染用户基本画像（角色、部门、所属区域），这部分事实不需要用户告知。 */
    public String renderUserProfile(Long userId) {
        UserVO user;
        try {
            user = dormService.currentUser();
        } catch (Exception e) {
            // 业务服务短暂不可用时，不阻断纯政策/知识问答：
            // JWT 里已经有姓名、角色、部门、区域 ID，足够拼一个降级版身份段落。
            log.warn("[Agent] 获取用户资料失败，使用令牌内的身份信息降级：{}", e.getMessage());
            user = fromLoginUser(UserContext.get());
        }
        if (user == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【当前用户身份】\n");
        sb.append("- 姓名：").append(user.getRealName()).append('\n');
        sb.append("- 工号：").append(user.getEmployeeNo() == null ? "未设置" : user.getEmployeeNo()).append('\n');
        sb.append("- 角色：").append(user.getRole().getLabel()).append('\n');
        if (user.getDepartment() != null) {
            sb.append("- 部门：").append(user.getDepartment()).append('\n');
        }
        if (user.getRegionId() != null) {
            sb.append("- 所属区域：").append(user.getRegionName() == null ? user.getRegionId() : user.getRegionName()).append('\n');
        }
        if (user.getRole() != null && user.getRole().isAdmin()) {
            sb.append("- 管理权限：可查看").append(user.getRole().isSuperAdmin() ? "全部区域" : "本区域")
                    .append("的申请、订单与统计。\n");
        }
        return sb.toString();
    }

    /** 用 JWT 中已有的身份字段构造降级版用户视图（不查业务服务）。 */
    private static UserVO fromLoginUser(LoginUser loginUser) {
        if (loginUser == null) {
            return null;
        }
        UserVO user = new UserVO();
        user.setId(loginUser.getUserId());
        user.setUsername(loginUser.getUsername());
        user.setRealName(loginUser.getRealName());
        user.setEmployeeNo(loginUser.getEmployeeNo());
        user.setRole(loginUser.getRole());
        user.setRoleLabel(loginUser.getRole() == null ? null : loginUser.getRole().getLabel());
        user.setRegionId(loginUser.getRegionId());
        user.setDepartment(loginUser.getDepartment());
        return user;
    }

    /** 写入或更新一条长期记忆（key 唯一，重复则覆盖）。 */
    @Transactional
    public void remember(Long userId, AgentEnums.MemoryType type, String key, String content, int importance) {
        if (userId == null || content == null || content.isBlank()) {
            return;
        }
        String normalizedKey = key == null || key.isBlank() ? null : key.trim().toUpperCase();
        AgentLongMemory existing = normalizedKey == null ? null
                : memoryMapper.selectOne(new LambdaQueryWrapper<AgentLongMemory>()
                        .eq(AgentLongMemory::getUserId, userId)
                        .eq(AgentLongMemory::getMemoryKey, normalizedKey)
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setContent(content);
            existing.setImportance(importance);
            existing.setMemoryType(type);
            memoryMapper.updateById(existing);
            return;
        }
        AgentLongMemory memory = new AgentLongMemory();
        memory.setUserId(userId);
        memory.setMemoryType(type);
        memory.setMemoryKey(normalizedKey);
        memory.setContent(content);
        memory.setImportance(importance);
        try {
            memoryMapper.insert(memory);
        } catch (DuplicateKeyException e) {
            // 并发下同 key 同时插入：唯一索引兜底，改成更新
            AgentLongMemory again = memoryMapper.selectOne(new LambdaQueryWrapper<AgentLongMemory>()
                    .eq(AgentLongMemory::getUserId, userId)
                    .eq(AgentLongMemory::getMemoryKey, normalizedKey)
                    .last("LIMIT 1"));
            if (again != null) {
                again.setContent(content);
                memoryMapper.updateById(again);
            }
        }
    }

    /** 会话摘要（超过阈值时把早期对话压成一段摘要存进会话记录）。 */
    @Transactional
    /**
     * 会话变长时为较早的消息生成摘要。
     *
     * 短期记忆只读最近 N 条消息，超出窗口的内容会被彻底丢弃，* 用户在第 3 轮说过的关键约束（"我要单人间""我对猫毛过敏"），
     * 到第 30 轮就"忘记"了。摘要把超窗部分压缩成一段话保留下来。
     *
     * 这个能力此前只有"存储"（{@code updateConversationSummary}）与"读取"
     * （{@code conversationSummary}）两半，<b>没有任何地方生成摘要</b>：
     * 配置项 {@code dorm.agent.memory.summarize-after-turns} 也从未被读取。
     * 于是 {@code agent_conversation.summary} 永远是 null、会话列表里的
     * {@code hasSummary} 永远是 false。现已接入轮次结束流程并启用了该配置。
     *
     * 摘要需要一次额外的模型调用：它必须<b>异步执行</b>（不拖慢用户感知的响应）、
     * <b>失败静默</b>（摘要失败绝不能让用户的对话失败）。
     * 调用方用 {@code agentTaskExecutor} 提交，这里再兜一层 try/catch。
     *
     * 触发阈值 = 配置的"超过多少轮"换算成消息条数（用户 + 助手各一条）；
     * 已有摘要时按同一个步长刷新，避免每一轮都重新摘要（那会持续烧 token）。
     * 用取模判断而<b>不新增数据库列</b>记录"上次摘要到哪一条"，* 为这个辅助功能加一列并做迁移，收益不抵成本。
     */
    public void summarizeIfNeeded(Long conversationId) {
        try {
            AgentConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) {
                return;
            }
            int count = conversation.getMessageCount() == null ? 0 : conversation.getMessageCount();
            int step = Math.max(properties.getMemory().getSummarizeAfterTurns(), 1) * MESSAGES_PER_TURN;
            if (count < step || count % step != 0) {
                return;
            }
            List<AgentMessage> messages = messageMapper.selectRecent(conversationId, SUMMARY_SOURCE_LIMIT);
            int window = properties.getMemory().getWindowSize();
            if (messages.size() <= window) {
                return;
            }
            // 只把"超出记忆窗口"的较早部分交给模型压缩
            List<AgentMessage> older = messages.subList(0, messages.size() - window);
            String source = older.stream()
                    .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                    .map(m -> (m.getRole() == com.overseas.dorm.common.enums.AgentEnums.Role.USER
                            ? "用户：" : "助手：") + m.getContent())
                    .collect(java.util.stream.Collectors.joining("\n", "", ""));
            if (source.isBlank()) {
                return;
            }
            String summary = summarize(source);
            if (summary != null && !summary.isBlank()) {
                updateConversationSummary(conversationId, summary);
                log.info("[Agent] 会话摘要已更新 conversationId={} 压缩 {} 条 / 摘要 {} 字",
                        conversationId, older.size(), summary.length());
            }
        } catch (Exception e) {
            // 摘要属于"锦上添花"：失败只记日志，绝不能影响对话本身
            log.warn("[Agent] 会话摘要生成失败 conversationId={}", conversationId, e);
        }
    }

    /** 调用快模型压缩历史。用快模型而不是主模型：摘要是信息压缩，不需要强推理，但会周期性触发。 */
    private String summarize(String source) {
        var model = modelFactory.chat(properties.getModel().getFast());
        String prompt = """
                请把下面这段宿舍管理助手的对话历史压缩成不超过 200 字的中文摘要。
                必须保留：用户的目的地区域与日期、房型/合住偏好、已提交的单号、
                被驳回的原因、以及用户明确强调过的任何约束。
                不要写"用户询问了…"这类空话，直接给出事实要点。
                只输出摘要正文，不要任何前后缀。

                --- 对话历史 ---
                %s
                """.formatted(source);
        String result = model.chat(prompt);
        if (result == null) {
            return null;
        }
        String trimmed = result.strip();
        return trimmed.length() > SUMMARY_MAX_LENGTH
                ? trimmed.substring(0, SUMMARY_MAX_LENGTH) : trimmed;
    }

    /**
     * 清理已过期的长期记忆。
     *
     * <p>加 {@code @Scheduled} 的原因：这个方法此前存在但<b>从未被调用</b>，
     * 意味着带过期时间的记忆永远不会被删除，表只增不减。
     * 每天凌晨执行一次即可，单次批量有限，不会对数据库造成压力。
     */
    @org.springframework.scheduling.annotation.Scheduled(
            cron = "${dorm.agent.memory.purge-cron:0 30 3 * * ?}")
    public void purgeExpiredScheduled() {
        int removed = purgeExpired();
        if (removed > 0) {
            log.info("[Agent] 已清理 {} 条过期长期记忆", removed);
        }
    }


    /** 会话摘要是否已存在（用于展示"上次聊到哪"）。 */
    public String conversationSummary(Long conversationId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        return conversation == null ? null : conversation.getSummary();
    }

    /**
     * 用户可自助查看与清除的长期记忆列表。
     *
     * <p>下发 {@code typeLabel} 而不只是 {@code type}：给用户看的界面不该出现
     * {@code PREFERENCE} 这种内部枚举名。中文映射属于后端职责，* 放前端意味着每加一个记忆类型都要同步改前端，且必然有漏掉的一次。
     */
    public List<AgentMemoryVO> listMemories(Long userId) {
        return memoryMapper.selectTop(userId, MEMORY_LIST_LIMIT).stream()
                .map(memory -> new AgentMemoryVO(
                        memory.getId(),
                        memory.getMemoryType() == null ? null : memory.getMemoryType().name(),
                        memory.getMemoryType() == null ? null : memory.getMemoryType().getLabel(),
                        memory.getMemoryKey(),
                        memory.getContent(),
                        memory.getImportance(),
                        String.valueOf(memory.getUpdateTime())))
                .toList();
    }

    /** 列表与清空时的单次读取上限。 */
    private static final int MEMORY_LIST_LIMIT = 50;
    private static final int MEMORY_CLEAR_LIMIT = 500;

    /** 清除记忆（用户行使"被遗忘权"）。 */
    @Transactional
    public int clearMemories(Long userId) {
        List<AgentLongMemory> memories = memoryMapper.selectTop(userId, MEMORY_CLEAR_LIMIT);
        for (AgentLongMemory memory : memories) {
            memoryMapper.deleteById(memory.getId());
        }
        log.info("用户清除长期记忆 userId={} 共 {} 条", userId, memories.size());
        return memories.size();
    }

    /**
     * 写入会话摘要。
     *
     * <p>由 {@link #summarizeIfNeeded(Long)} 在会话变长时调用；
     * 摘要会与最近窗口内的消息一起进入 System Prompt，让"第 3 轮说过的约束"
     * 在第 30 轮依然有效。
     */
    public void updateConversationSummary(Long conversationId, String summary) {
        if (conversationId == null || summary == null || summary.isBlank()) {
            return;
        }
        AgentConversation update = new AgentConversation();
        update.setId(conversationId);
        update.setSummary(summary);
        conversationMapper.updateById(update);
    }

    /**
     * 清理过期记忆。
     *
     * <p>由 {@link #purgeExpiredScheduled()} 每天凌晨调用。
     * 补充说明为什么需要调度：这个方法此前<b>存在但从未被任何地方调用</b>，
     * 意味着带过期时间的长期记忆永远删不掉，表只增不减。
     */
    @Transactional
    public int purgeExpired() {
        int removed = memoryMapper.delete(new LambdaQueryWrapper<AgentLongMemory>()
                .isNotNull(AgentLongMemory::getExpireTime)
                .lt(AgentLongMemory::getExpireTime, LocalDateTime.now()));
        if (removed > 0) {
            log.info("清理过期长期记忆 {} 条", removed);
        }
        return removed;
    }
}
