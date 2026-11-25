package com.overseas.dorm.agent.web;

import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.dto.AgentChatRequest;
import com.overseas.dorm.agent.memory.AgentMemoryService;
import com.overseas.dorm.agent.stream.AgentTurnService;
import com.overseas.dorm.agent.stream.ConversationService;
import com.overseas.dorm.agent.vo.AgentInfoVO;
import com.overseas.dorm.agent.vo.AgentLimitsVO;
import com.overseas.dorm.agent.vo.AgentMemoryVO;
import com.overseas.dorm.agent.vo.AgentMessageVO;
import com.overseas.dorm.agent.vo.AgentMetaVO;
import com.overseas.dorm.agent.vo.AgentReplayVO;
import com.overseas.dorm.agent.vo.ConversationVO;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.constant.BusinessLimits;
import com.overseas.dorm.common.enums.AgentType;
import com.overseas.dorm.common.enums.FeedbackRating;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.domain.entity.AgentConversation;
import com.overseas.dorm.domain.support.DataScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 助手接口。
 *
 * <pre>
 *   PC 端（推荐）：
 *     fetch('/api/agent/chat/stream', {method:'POST', headers:{Authorization: 'Bearer ...'}, body: json})
 *       → 读 response.body 的 ReadableStream，自己按 SSE 帧解析
 *     优点：可以带 Authorization 头、可以 POST、可以自己控制重连。
 *
 *   小程序 / 部分 App WebView（受限于没有 ReadableStream）：
 *     走 GET /api/agent/chat/stream/{messageId}?lastEventId=N&amp;token=xxx
 *     这是标准 EventSource 就能消费的形式（token 用查询参数传，
 *     服务端的鉴权拦截器显式支持这种传法）。两条路径共用同一套事件契约。
 * </pre>
 *
 * 本控制器不再返回 {@code Map<String, Object>}，而是各自的 VO。
 * 原因不是"看起来更高级"，而是 {@code Map} 会让接口契约退化：
 * Swagger 只能把它显示成"一个对象"，前端无法据此生成类型，
 * 字段名被某个 {@code put} 改掉时也不会有任何编译期或运行期提示。
 */
@Slf4j
@Tag(name = "12-AI 助手", description = "会话管理、流式对话、断点续传、反馈与记忆")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    /**
     * 会话创建结果。
     *
     * <p>只回三个字段：会话 ID（前端要用来发消息）、编号（对客可读）、标题。
     */
    public record CreatedConversationVO(Long id, String conversationNo, String title) {
    }

    /** 清除记忆的结果。 */
    public record ClearMemoriesVO(int removed) {
    }

    private final AgentTurnService agentTurnService;
    private final ConversationService conversationService;
    private final AgentMemoryService memoryService;
    private final AgentAuditService agentAuditService;
    private final com.overseas.dorm.agent.audit.AgentFeedbackService feedbackService;
    private final com.overseas.dorm.domain.mapper.RagRetrievalLogMapper retrievalLogMapper;
    private final AgentProperties agentProperties;
    private final DataScope dataScope;

    // ================================================================ 会话

    @Operation(summary = "新建会话")
    @PostMapping("/conversations")
    public Result<CreatedConversationVO> createConversation(
            @RequestParam(required = false) String title) {
        var user = UserContext.require();
        AgentConversation conversation = conversationService.createConversation(
                user.getUserId(), title, AgentType.ROUTER);
        return Result.ok(new CreatedConversationVO(
                conversation.getId(), conversation.getConversationNo(), conversation.getTitle()));
    }

    @Operation(summary = "我的会话列表")
    @GetMapping("/conversations")
    public Result<PageResult<ConversationVO>> listConversations(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(conversationService.listConversations(
                UserContext.requireUserId(), current, size));
    }

    @Operation(summary = "会话消息历史", description = "按会话内序号升序返回，前端可直接渲染")
    @GetMapping("/conversations/{id}/messages")
    public Result<List<AgentMessageVO>> messages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(conversationService.listMessages(id, UserContext.requireUserId(),
                Math.clamp(limit, 1, BusinessLimits.MAX_MESSAGE_LIMIT)));
    }

    @Operation(summary = "归档会话", description = "软删除；审计记录保留")
    @DeleteMapping("/conversations/{id}")
    public Result<Void> archive(@PathVariable Long id) {
        conversationService.archive(id, UserContext.requireUserId());
        return Result.ok();
    }

    // ================================================================ 流式对话

    @Operation(summary = "发起流式对话（SSE）",
            description = """
                    返回 text/event-stream。事件类型：
                    START / REASONING / DELTA / TOOL / REFERENCE / HUMAN_REQUIRED / DONE / BLOCKED / INTERRUPTED / ERROR。
                    每个事件都带 id（= 会话内单调序号），断线后可用该 id 续传。
                    """)
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AgentChatRequest request) {
        return agentTurnService.chat(request.conversationId(), request.turnId(), request.message());
    }

    @Operation(summary = "断点续传（SSE）",
            description = """
                    按 lastEventId 补发遗漏事件，并接续实时生成。
                    前端在 fetch 流断开后，用「已收到的最大事件 id」作为 lastEventId 重新连接即可，
                    不会重复也不会丢包。
                    """)
    @GetMapping(value = "/chat/stream/{messageId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable Long messageId,
                             @RequestParam(defaultValue = "0") long lastEventId) {
        return agentTurnService.resume(messageId, lastEventId);
    }

    @Operation(summary = "打断生成",
            description = "已生成的部分会保留并标记为「已打断」，用户可继续追问")
    @PostMapping("/chat/{messageId}/stop")
    public Result<Void> stop(@PathVariable Long messageId) {
        agentTurnService.stop(messageId);
        return Result.ok();
    }

    // ================================================================ 反馈与记忆

    @Operation(summary = "对回答点赞/点踩",
            description = "同一用户对同一条消息只有一份反馈，重复提交视为修改；"
                    + "只能对自己会话里的助手消息反馈")
    @PostMapping("/messages/{messageId}/feedback")
    public Result<Boolean> feedback(@PathVariable Long messageId,
                                    @RequestParam String rating,
                                    @RequestParam(required = false) String reason) {
        FeedbackRating parsed = FeedbackRating.of(rating);
        if (parsed == null || parsed == FeedbackRating.NONE) {
            throw BizException.param("rating 只能是 LIKE 或 DISLIKE");
        }
        boolean inserted = feedbackService.submit(messageId, UserContext.requireUserId(),
                parsed, reason);
        return Result.ok(inserted);
    }

    @Operation(summary = "我的助记信息", description = "用户可查看系统记住了自己哪些信息")
    @GetMapping("/memories")
    public Result<List<AgentMemoryVO>> memories() {
        return Result.ok(memoryService.listMemories(UserContext.requireUserId()));
    }

    @Operation(summary = "清除我的助记信息", description = "用户行使「被遗忘权」")
    @DeleteMapping("/memories")
    public Result<ClearMemoriesVO> clearMemories() {
        int removed = memoryService.clearMemories(UserContext.requireUserId());
        return Result.ok(new ClearMemoriesVO(removed));
    }

    // ================================================================ 说明信息

    @Operation(summary = "助手能力说明", description = "前端据此渲染「助手能做什么」的引导")
    @GetMapping("/meta")
    public Result<AgentMetaVO> meta() {
        List<AgentInfoVO> agents = List.of(
                agentInfo(AgentType.ROOM, "有哪些空房、多少钱、在哪个位置"),
                agentInfo(AgentType.POLICY, "制度怎么规定的、能不能带家属、怎么报销"),
                agentInfo(AgentType.APPLY, "帮我申请宿舍、我的申请到哪一步了"),
                agentInfo(AgentType.ORDER, "我的订单、办理入住、办理退房"));
        // 运维专家只对管理员下发：员工端界面不该出现自己无权使用的能力，
        // 否则点了就报错，反而像系统坏了
        List<AgentInfoVO> adminAgents = UserContext.require().isAdmin()
                ? List.of(agentInfo(AgentType.OPS, "入住率、待审批、逾期未退房、房源健康度"))
                : List.of();
        AgentLimitsVO limits = new AgentLimitsVO(
                agentProperties.getRatelimit().getTurnsPerMinute(),
                agentProperties.getStream().getMaxStreamsPerUser(),
                agentProperties.getRatelimit().getMaxMessageLength(),
                agentProperties.getRatelimit().getDailyTokenBudget(),
                agentProperties.getStream().getGenerationTimeoutSeconds());
        return Result.ok(new AgentMetaVO(
                agentProperties.isEnabled(),
                agentProperties.getModel().getPrimary(),
                // 压测模式标记：压测脚本据此判断是否在打真实模型（避免误烧额度）
                agentProperties.getMock().isEnabled(),
                agentProperties.getRag().isEnabled(),
                agents,
                adminAgents,
                limits));
    }

    private AgentInfoVO agentInfo(AgentType type, String sample) {
        return new AgentInfoVO(type.name(), type.getLabel(), type.getDescription(), sample);
    }

    @Operation(summary = "会话回放", description = "查看某一轮对话的完整执行过程：思考、工具调用、参数与结果")
    @GetMapping("/replay/{conversationId}")
    public Result<AgentReplayVO> replay(@PathVariable Long conversationId,
                                        @RequestParam(required = false) Long messageId) {
        // 会话归属校验：会话主人可回放自己的；管理员可回放任意会话（运维排查需要）。
        //
        // 修复说明：原实现写成
        //     if (conversation == null) { dataScope.assertAdmin(); throw ...; }
        // "管理员校验通过"之后紧跟一句无条件抛出，于是管理员分支永远不可达 ——
        // 表现为"管理员也无法回放他人会话"，而这恰恰是回放功能最主要的用途。
        AgentConversation conversation = conversationService.requireOwned(
                conversationId, UserContext.requireUserId());
        if (conversation == null) {
            // 不是会话主人：只有管理员可以继续，其他角色在这里被拒绝（并写审计）
            dataScope.assertAdmin();
        }
        return Result.ok(agentAuditService.replay(conversationId, messageId));
    }
}
