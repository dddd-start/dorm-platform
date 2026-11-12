package com.overseas.dorm.agent.web;

import com.overseas.dorm.agent.aiservice.AgentRegistry;
import com.overseas.dorm.agent.aiservice.AgentRouter;
import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.model.ModelRegistry;
import com.overseas.dorm.agent.rag.KnowledgeBaseLoader;
import com.overseas.dorm.agent.rag.RagEvaluator;
import com.overseas.dorm.agent.rag.RagService;
import com.overseas.dorm.agent.tool.ToolCatalog;
import com.overseas.dorm.agent.vo.AgentReplayVO;
import com.overseas.dorm.agent.vo.AgentToolsVO;
import com.overseas.dorm.agent.vo.KnowledgeLoadReportVO;
import com.overseas.dorm.agent.vo.ModelHealthVO;
import com.overseas.dorm.agent.vo.RagEvalVO;
import com.overseas.dorm.agent.vo.RagIndexStatusVO;
import com.overseas.dorm.agent.vo.RagQualityVO;
import com.overseas.dorm.agent.security.RequireRole;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.domain.vo.ToolStatVO;
import com.overseas.dorm.domain.vo.UsageTrendVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 助手运营接口（管理员）。
 *
 * <p>这个页面回答的是「Agent 上线之后，怎么知道它好不好、贵不贵、有没有出事」：
 * <ul>
 *   <li><b>贵不贵</b>：token 用量与成本趋势、各工具调用统计；</li>
 *   <li><b>好不好</b>：RAG 无命中率、平均最高相似度、用户点踩率、最常查不到的问题；</li>
 *   <li><b>有没有出事</b>：工具失败率与模型熔断状态、护栏拦截次数；</li>
 *   <li><b>能不能追溯</b>：任意一轮对话的完整回放（模型、参数、结果、耗时）。</li>
 * </ul>
 * 没有这一页，Agent 就只能靠"感觉还行"来运维。
 */
@Slf4j
@Tag(name = "13-AI 助手运营", description = "模型健康、用量成本、知识库质量、工具审计")
@RestController
@RequestMapping("/api/agent/admin")
@RequiredArgsConstructor
@RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
public class AgentAdminController {

    /** 用量统计的默认与最大回看天数。 */
    private static final int DEFAULT_USAGE_DAYS = 14;
    private static final int MAX_USAGE_DAYS = 90;

    /** RAG 质量指标的默认回看天数。 */
    private static final int DEFAULT_QUALITY_DAYS = 30;

    /** 评测的默认召回条数。 */
    private static final int DEFAULT_EVAL_TOP_K = 5;

    /** 用量与工具统计。 */
    public record UsageStatsVO(
            List<UsageTrendVO> trend,
            List<ToolStatVO> toolStatistics,
            int periodDays) {
    }

    /** 金标集题目。 */
    public record GoldenItemVO(
            String id,
            String question,
            String expectedDocCode,
            String keyword,
            String category) {
    }

    private final ModelRegistry modelRegistry;
    private final RagService ragService;
    private final RagEvaluator ragEvaluator;
    private final AgentAuditService agentAuditService;
    private final KnowledgeBaseLoader knowledgeBaseLoader;
    private final AgentRegistry agentRegistry;
    private final AgentProperties agentProperties;

    @Operation(summary = "模型健康与熔断状态",
            description = "回答质量突然变差时第一个要看的地方：主模型是否被熔断、请求是否已降级到备用模型")
    @GetMapping("/models")
    public Result<List<ModelHealthVO>> models() {
        return Result.ok(modelRegistry.health());
    }

    @Operation(summary = "用量与成本趋势")
    @GetMapping("/usage")
    public Result<UsageStatsVO> usage(@RequestParam(defaultValue = "14") int days) {
        int scoped = Math.clamp(days, 1, MAX_USAGE_DAYS);
        return Result.ok(new UsageStatsVO(
                agentAuditService.usageTrend(scoped),
                agentAuditService.toolStatistics(scoped),
                scoped));
    }

    @Operation(summary = "知识库索引状态",
            description = "含实际生效的检索参数（topK / minScore / MMR）与最近一次装载报告")
    @GetMapping("/knowledge")
    public Result<RagIndexStatusVO> knowledge() {
        return Result.ok(ragService.indexStatus());
    }

    @Operation(summary = "重建知识库索引",
            description = "按文档 front-matter 的 version 决定是否重新向量化；返回本次新建/更新/跳过的篇数")
    @PostMapping("/knowledge/rebuild")
    public Result<KnowledgeLoadReportVO> rebuild() throws java.io.IOException {
        return Result.ok(knowledgeBaseLoader.rebuildAll());
    }

    @Operation(summary = "RAG 生产质量指标",
            description = "无命中率、平均最高相似度、平均耗时、缓存命中率、点赞点踩，以及「一条都没查到」的问题榜")
    @GetMapping("/rag-quality")
    public Result<RagQualityVO> ragQuality(
            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(ragService.qualityMetrics(Math.clamp(days, 1, MAX_USAGE_DAYS)));
    }

    @Operation(summary = "运行 RAG 召回质量评测",
            description = """
                    用内置金标集（dorm-agent/src/main/resources/rag-eval/golden-set.jsonl）计算
                    Recall@1/3/5、MRR、关键词命中率、No-Hit 率与平均最高相似度，
                    并返回逐题明细与「未召回题目」清单（这是知识库建设最直接的输入）。
                    注意：本接口会真实跑一遍向量检索，耗时与题目数成正比。
                    """)
    @PostMapping("/rag-eval")
    public Result<RagEvalVO> ragEval(@RequestParam(defaultValue = "5") int topK) {
        RagEvaluator.Report report = ragEvaluator.evaluate(topK);
        return Result.ok(ragEvaluator.toVO(report));
    }

    @Operation(summary = "金标集题目清单")
    @GetMapping("/rag-eval/golden-set")
    public Result<List<GoldenItemVO>> goldenSet() {
        return Result.ok(ragEvaluator.loadGoldenSet().stream()
                .map(item -> new GoldenItemVO(
                        item.id(),
                        item.question(),
                        item.docCode(),
                        item.keyword() == null ? "" : item.keyword(),
                        item.category() == null ? "" : item.category()))
                .toList());
    }

    @Operation(summary = "已装配的 Agent 与工具清单",
            description = "展示每个专家 Agent 挂载了哪些工具、工具的读写属性与调用规则")
    @GetMapping("/tools")
    public Result<AgentToolsVO> tools() {
        List<AgentToolsVO.ToolCatalogItem> catalog = ToolCatalog.descriptors().stream()
                .map(d -> new AgentToolsVO.ToolCatalogItem(d.name(), d.label(), d.readOnly()))
                .toList();
        return Result.ok(new AgentToolsVO(
                agentRegistry.assembled(),
                catalog,
                AgentRouter.keywordTable(),
                agentProperties.getModel().getMaxToolInvocations(),
                agentProperties.getModel().getMaxToolRoundTrips(),
                agentProperties.getModel().getTurnTimeoutSeconds()));
    }

    @Operation(summary = "会话回放（管理员可查看任意会话）")
    @GetMapping("/replay")
    public Result<AgentReplayVO> replay(@RequestParam Long conversationId,
                                        @RequestParam(required = false) Long messageId) {
        return Result.ok(agentAuditService.replay(conversationId, messageId));
    }
}
