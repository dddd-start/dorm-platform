package com.overseas.dorm.agent.audit;

import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.common.util.CryptoUtil;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.agent.tool.ToolCatalog;
import com.overseas.dorm.agent.vo.AgentReplayVO;
import com.overseas.dorm.agent.vo.ReplayNodeVO;
import com.overseas.dorm.agent.vo.ToolCallVO;
import com.overseas.dorm.domain.entity.AgentToolCall;
import com.overseas.dorm.domain.mapper.AgentMessageMapper;
import com.overseas.dorm.domain.mapper.AgentToolCallMapper;
import com.overseas.dorm.domain.mapper.AgentUsageDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Agent 审计与用量服务。
 *
 * <h3>三张表，各回答一类问题</h3>
 * <table>
 *   <tr><th>表</th><th>回答的问题</th></tr>
 *   <tr><td>{@code agent_message}</td>
 *       <td>「这轮对话用户问了什么、模型答了什么、用了多少 token、首字延迟多少」</td></tr>
 *   <tr><td>{@code agent_tool_call}</td>
 *       <td>「模型调用了哪个工具、传了什么参数、结果如何、是否被权限拒绝、是否被去重」</td></tr>
 *   <tr><td>{@code agent_usage_daily}</td>
 *       <td>「今天这个用户/整个平台消耗了多少 token、成本多少」</td></tr>
 * </table>
 *
 * <h3>为什么用量要用"累加"而不是"重算"</h3>
 * 用 {@code INSERT ... ON DUPLICATE KEY UPDATE x = x + VALUES(x)} 做原子累加，
 * 而不是「查出来 + 加上增量 + 写回去」。后者在并发下会丢增量：
 * 同一个用户同时开了两个流，各自读到 100，各自加 50 写回 150，
 * 结果应该是 200 却变成了 150。用量是计费依据，不能丢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuditService {

    private final AgentToolCallMapper toolCallMapper;
    private final AgentUsageDailyMapper usageMapper;
    private final AgentMessageMapper messageMapper;
    private final StringRedisTemplate redis;

    /** 单价（每百万 token，用于估算成本；可按实际合同价调整）。 */
    @Value("${dorm.agent.cost.input-per-million:2.0}")
    private BigDecimal inputPricePerMillion;

    @Value("${dorm.agent.cost.output-per-million:8.0}")
    private BigDecimal outputPricePerMillion;

    // ================================================================ 工具调用审计

    /** 记录一次工具调用。 */
    public void recordToolCall(String toolName, String argumentsJson, String resultJson,
                               boolean success, String errorMsg, boolean permissionChecked,
                               boolean deduplicated, long costMs) {
        AgentTurnContext context = AgentTurnContext.current();
        try {
            AgentToolCall entity = new AgentToolCall();
            entity.setConversationId(context == null || context.conversationId() == null
                    ? 0L : context.conversationId());
            entity.setMessageId(context == null ? null : context.messageId());
            entity.setTurnId(context == null ? null : context.turnId());
            entity.setSeqInTurn(context == null ? 0 : context.nextToolSequence());
            entity.setToolName(toolName);
            entity.setArgumentsJson(truncate(argumentsJson, 4000));
            entity.setResultJson(truncate(resultJson, 8000));
            entity.setSuccess(success ? 1 : 0);
            entity.setErrorMsg(truncate(errorMsg, 480));
            entity.setPermissionChecked(permissionChecked ? 1 : 0);
            entity.setDeduplicated(deduplicated ? 1 : 0);
            entity.setCostMs(costMs);
            entity.setTraceId(TraceContext.get());
            toolCallMapper.insert(entity);

            if (deduplicated) {
                log.info("[Agent] 工具 {} 被去重守卫拦截，直接复用同轮已有结果", toolName);
            }
            if (!success) {
                log.warn("[Agent] 工具 {} 执行失败：{}", toolName, errorMsg);
            }
        } catch (Exception e) {
            // 审计失败不影响对话
            log.warn("[Agent] 工具调用审计写入失败：{}", e.getMessage());
        }
    }

    /**
     * 工具调用去重判定。
     *
     * <h3>为什么需要"同轮去重"</h3>
     * 模型在实际运行中经常重复调用同一个工具并传完全一样的参数，典型场景：
     * <ul>
     *   <li>第一次调用返回空结果，模型"不甘心"再调一次；</li>
     *   <li>模型在同一轮里想"确认一下"，于是又调了一次相同的查询；</li>
     *   <li>部分模型对工具结果的格式不敏感，误判为失败后重试。</li>
     * </ul>
     * 这些重复调用既浪费 token 与时间，也可能放大副作用（对写操作尤其危险）。
     * 因此在同一轮内对 {@code (工具名 + 参数)} 做指纹，命中则直接返回上次的结果。
     *
     * <p>为什么按"轮"而不是按"会话"去重：跨轮重复调用往往是合理的
     * （用户可能几分钟后问"那间房现在还有吗"），不能一概拦截。
     *
     * <p>为什么只对<b>只读</b>工具生效：写操作不应该被"缓存结果"，
     * 否则用户第二次确实想再提交一张申请时会被静默吞掉。
     * 写操作自身的重复保护由业务层的幂等键负责。
     */
    public String toolFingerprint(String toolName, String argumentsJson) {
        return CryptoUtil.sha256(toolName + "|" + (argumentsJson == null ? "" : argumentsJson));
    }

    public String lookupToolResult(String toolName, String argumentsJson) {
        AgentTurnContext context = AgentTurnContext.current();
        if (context == null || context.turnId() == null) {
            return null;
        }
        try {
            String key = RedisKeys.toolGuard(context.conversationId(), context.turnId()) + ":"
                    + toolFingerprint(toolName, argumentsJson);
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            return null;
        }
    }

    public void cacheToolResult(String toolName, String argumentsJson, String result, long ttlSeconds) {
        AgentTurnContext context = AgentTurnContext.current();
        if (context == null || context.turnId() == null || result == null) {
            return;
        }
        try {
            String key = RedisKeys.toolGuard(context.conversationId(), context.turnId()) + ":"
                    + toolFingerprint(toolName, argumentsJson);
            redis.opsForValue().set(key, result, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.debug("[Agent] 工具结果缓存写入失败：{}", e.getMessage());
        }
    }

    // ================================================================ 用量与成本

    /**
     * 累加当日用量。
     *
     * @param userId           用户
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param toolCalls        本轮工具调用次数
     * @param ragHits          本轮 RAG 命中数
     * @param blocked          是否被护栏拦截
     */
    public void accumulateUsage(Long userId, long promptTokens, long completionTokens, int toolCalls,
                                int ragHits, boolean blocked, boolean newConversation) {
        if (userId == null) {
            return;
        }
        long total = promptTokens + completionTokens;
        BigDecimal cost = inputPricePerMillion.multiply(BigDecimal.valueOf(promptTokens))
                .add(outputPricePerMillion.multiply(BigDecimal.valueOf(completionTokens)))
                .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP);
        try {
            usageMapper.accumulate(
                    ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                    LocalDate.now(), userId,
                    newConversation ? 1 : 0,
                    promptTokens, completionTokens, total,
                    toolCalls, ragHits, blocked ? 1 : 0, 0, cost);
        } catch (Exception e) {
            log.warn("[Agent] 用量累加失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 查询用户当日已消耗 token（预算守卫用）。 */
    public long dailyTokens(Long userId) {
        try {
            Long tokens = usageMapper.selectDailyTokens(LocalDate.now(), userId);
            return tokens == null ? 0L : tokens;
        } catch (Exception e) {
            // 查询失败时宁可"当作没超预算"，也不要因为统计问题把用户挡在门外
            return 0L;
        }
    }

    /** 平台用量趋势（管理看板）。 */
    public List<com.overseas.dorm.domain.vo.UsageTrendVO> usageTrend(int days) {
        return usageMapper.dailySummary(days);
    }

    // ================================================================ 回放

    /**
     * 构造一轮对话的完整回放数据。
     *
     * <p>这是「Agent 执行过程可回放」的实现：把用户输入、模型思考过程、
     * 每一次工具调用的参数与结果、最终答案、token 与耗时按时间顺序拼成一条时间线。
     * 出问题时，可以直接看到"模型当时到底看到了什么、做了什么决定"。
     */
    /**
     * 取某条消息上发生的全部工具调用（按轮内序号）。
     *
     * <p>抽成公共方法是因为它有两个消费者，而且两边必须完全一致：
     * 会话回放（管理员排查用）与 {@code agent_message.tool_calls_json}
     * （用户刷新页面后重建工具卡片用）。各写一份的结果就是"回放里看得到、
     * 前端刷新后看不到"这类让人怀疑数据丢了的差异。
     */
    public List<ToolCallVO> listToolCalls(Long messageId) {
        if (messageId == null) {
            return List.of();
        }
        return toolCallMapper.selectByMessage(messageId).stream()
                .map(call -> new ToolCallVO(
                        call.getSeqInTurn(),
                        call.getToolName(),
                        ToolCatalog.labelOf(call.getToolName()),
                        JsonUtil.parseMap(call.getArgumentsJson()),
                        call.getSuccess() != null && call.getSuccess() == 1,
                        call.getDeduplicated() != null && call.getDeduplicated() == 1,
                        call.getPermissionChecked() != null && call.getPermissionChecked() == 1,
                        call.getErrorMsg(),
                        call.getCostMs()))
                .toList();
    }

    /**
     * 把工具调用序列化为 JSON，用于写入 {@code agent_message.tool_calls_json}。
     *
     * <p>此前这个字段从未被写入，导致一个真实的功能缺口：
     * 用户刷新页面后，历史消息里的工具调用卡片（"正在检索可用房源"等）全部消失，
     * 只剩回答正文。前端类型里早就声明了 {@code toolCalls}，后端却一直给 null。
     */
    public String toolCallsJson(Long messageId) {
        List<ToolCallVO> calls = listToolCalls(messageId);
        return calls.isEmpty() ? null : JsonUtil.toJson(calls);
    }

    /**
     * 构造一轮对话的完整回放数据。
     *
     * <p>这是「Agent 执行过程可回放」的实现：把用户输入、模型思考过程、
     * 每一次工具调用的参数与结果、最终答案、token 与耗时按时间顺序拼成一条时间线。
     * 出问题时可以直接看到"模型当时到底看到了什么、做了什么决定"，而不用猜。
     */
    public AgentReplayVO replay(Long conversationId, Long messageId) {
        List<AgentMessage> messages;
        if (messageId != null) {
            AgentMessage message = messageMapper.selectById(messageId);
            messages = message == null ? List.of() : List.of(message);
        } else {
            messages = messageMapper.selectAll(conversationId);
        }
        List<ReplayNodeVO> nodes = new ArrayList<>(messages.size());
        for (AgentMessage message : messages) {
            List<ToolCallVO> calls = message.getTurnId() == null
                    ? List.of() : listToolCalls(message.getId());
            nodes.add(new ReplayNodeVO(
                    message.getSeqNo(),
                    message.getRole() == null ? null : message.getRole().name(),
                    message.getRole() == null ? null : message.getRole().getLabel(),
                    message.getContent(),
                    message.getReasoningContent(),
                    message.getStatus() == null ? null : message.getStatus().name(),
                    message.getModel(),
                    message.getTokensIn(),
                    message.getTokensOut(),
                    message.getLatencyMs(),
                    message.getFirstTokenMs(),
                    String.valueOf(message.getCreateTime()),
                    calls));
        }
        return new AgentReplayVO(conversationId, messageId, nodes.size(), nodes);
    }

    /** 工具调用排行（运维看板：哪个工具最常被调、失败率多高）。 */
    public List<com.overseas.dorm.domain.vo.ToolStatVO> toolStatistics(int days) {
        return toolCallMapper.statistics(days);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }
}
