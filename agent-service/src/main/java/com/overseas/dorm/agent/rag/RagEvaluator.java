package com.overseas.dorm.agent.rag;

import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.vo.RagEvalMetricsVO;
import com.overseas.dorm.agent.vo.RagEvalVO;
import com.overseas.dorm.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 召回质量评测器，回答「RAG 召回率到底是怎么测的」。
 *
 * <pre>
 *   Recall@k   ：Top-k 结果里是否包含正确文档的切片。本项目的定义是
 *                「期望文档出现在前 k 条里的题目占比」。k 取 1/3/5。
 *                注意这是"文档级召回"，不是"切片级召回"，*                用户要的是找到正确的制度文件，而不是找到某一句话。
 *   MRR        ：第一条正确答案所在位置的倒数的平均值。
 *                只排到第 1 位是 1.0，排到第 3 位是 0.333。
 *                它比 Recall@k 更敏感：能区分"总是排第 1"和"总在第 5 名擦边"。
 *   关键词命中 ：答案切片里是否真的出现了关键信息（如"24 小时"）。
 *                这是对"召回了正确文档但拿到的是无关段落"的补充检查，*                实际排查中，这类问题比"完全没召回"更常见。
 *   No-Hit 率  ：一条都没检索到（低于 minScore）的比例，
 *                对应"知识库有缺口"，是最该优先修的问题。
 * </pre>
 *
 * 如果用人工写的标准答案做评测，就变成"答案文本相似度"评测，
 * 会被措辞差异严重干扰。而"这个问题应该由哪份制度文件回答"是客观事实，
 * 标注成本低、歧义小、可复核，唯一正确的做法是把它作为召回率的判定依据。
 * 至于"答案组织得好不好"，属于生成质量，用用户点赞/点踩和人工抽检来评（见运行日志）。
 *
 * 一个常见误区是只盯 Recall@5。但真实场景里，如果 minScore 定得过高，
 * 大量问题会直接 No-Hit，此时 Recall@k 依然很高（因为它不设阈值），
 * 监控数据看不出任何异常，而用户体验是"什么都答不上来"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEvaluator {

    private final RagService ragService;
    private final MysqlEmbeddingStore embeddingStore;
    private final AgentProperties properties;

    /** 金标集条目。 */
    public record GoldenItem(String id, String question, String docCode, String keyword, String category) {
    }

    /** 单题评测结果。 */
    public record ItemResult(String id, String question, String expectedDocCode, String expectedKeyword,
                             boolean hitDoc, int rank, boolean hitKeyword, double topScore,
                             List<String> retrievedDocCodes, long latencyMs) {
    }

    /** 整体报告。 */
    public record Report(int total, int recallAt1, int recallAt3, int recallAt5,
                         double recallRate1, double recallRate3, double recallRate5,
                         double mrr, double keywordHitRate, double noHitRate, double avgTopScore,
                         double avgLatencyMs, int indexSize,
                         List<ItemResult> details, List<ItemResult> misses) {
    }

    /**
     * 执行评测。
     *
     * @param topK 评测用的召回条数（建议 5）
     */
    public Report evaluate(int topK) {
        List<GoldenItem> goldenSet = loadGoldenSet();
        int k = Math.max(topK, 5);
        List<ItemResult> results = new ArrayList<>();

        for (GoldenItem item : goldenSet) {
            long start = System.currentTimeMillis();
            // 评测不设 minScore：要测的是"能不能排进前 k"，
            // 而不是"阈值卡不卡得住"。阈值的影响单独用 No-Hit 率来观察。
            List<RagService.Hit> hits = ragService.retrieveForEvaluation(item.question(), null, k);
            long latency = System.currentTimeMillis() - start;

            int rank = 0;
            boolean hitKeyword = false;
            List<String> docCodes = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                RagService.Hit hit = hits.get(i);
                if (hit.docCode() != null) {
                    docCodes.add(hit.docCode());
                }
                if (rank == 0 && item.docCode().equalsIgnoreCase(hit.docCode())) {
                    rank = i + 1;
                }
                if (item.keyword() != null && hit.content() != null
                        && hit.content().contains(item.keyword())) {
                    hitKeyword = true;
                }
            }
            double topScore = hits.isEmpty() ? 0.0 : hits.get(0).score();
            results.add(new ItemResult(item.id(), item.question(), item.docCode(), item.keyword(),
                    rank > 0, rank, hitKeyword, topScore, docCodes, latency));
        }

        int total = results.size();
        int at1 = (int) results.stream().filter(r -> r.rank() == 1).count();
        int at3 = (int) results.stream().filter(r -> r.rank() > 0 && r.rank() <= 3).count();
        int at5 = (int) results.stream().filter(r -> r.rank() > 0).count();
        double mrr = results.stream()
                .mapToDouble(r -> r.rank() > 0 ? 1.0 / r.rank() : 0.0)
                .average().orElse(0.0);
        double keywordHit = results.stream().filter(ItemResult::hitKeyword).count() * 100.0 / Math.max(total, 1);
        double noHit = results.stream().filter(r -> r.retrievedDocCodes().isEmpty()).count() * 100.0
                / Math.max(total, 1);
        double avgTop = results.stream().mapToDouble(ItemResult::topScore).average().orElse(0.0);
        double avgLatency = results.stream().mapToLong(ItemResult::latencyMs).average().orElse(0.0);

        List<ItemResult> misses = results.stream().filter(r -> !r.hitDoc()).toList();

        Report report = new Report(total, at1, at3, at5,
                pct(at1, total), pct(at3, total), pct(at5, total),
                round(mrr, 4), round(keywordHit, 2), round(noHit, 2),
                round(avgTop, 4), round(avgLatency, 1), embeddingStore.size(),
                results, misses);

        log.info("[RAG评测] 题目={} Recall@1={}%@3={}%@5={}% MRR={} 关键词命中={}% 无命中={}% 平均最高分={}",
                total, report.recallRate1(), report.recallRate3(), report.recallRate5(),
                report.mrr(), report.keywordHitRate(), report.noHitRate(), report.avgTopScore());
        return report;
    }

    /** 报告渲染为 Markdown（用于产出 docs/eval-report.md）。 */
    public String renderMarkdown(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("## RAG 召回质量评测报告\n\n");
        sb.append("### 评测设置\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 金标集题目数 | ").append(report.total()).append(" |\n");
        sb.append("| 向量索引切片数 | ").append(report.indexSize()).append(" |\n");
        sb.append("| 嵌入模型 | bge-small-zh-v1.5（本地 ONNX，512 维） |\n");
        sb.append("| 检索方式 | 余弦相似度 + MMR 重排（λ=")
                .append(properties.getRag().getMmrLambda()).append("），不设相似度阈值 |\n");
        sb.append("| 运行时 minScore 配置 | ").append(properties.getRag().getMinScore()).append(" |\n\n");

        sb.append("### 核心指标\n\n");
        sb.append("| 指标 | 含义 | 数值 |\n|---|---|---|\n");
        sb.append("| Recall@1 | 正确文档排在首位 | ").append(report.recallAt1()).append("/")
                .append(report.total()).append(" = ").append(report.recallRate1()).append("% |\n");
        sb.append("| Recall@3 | 正确文档进入前三 | ").append(report.recallAt3()).append("/")
                .append(report.total()).append(" = ").append(report.recallRate3()).append("% |\n");
        sb.append("| Recall@5 | 正确文档进入前五 | ").append(report.recallAt5()).append("/")
                .append(report.total()).append(" = ").append(report.recallRate5()).append("% |\n");
        sb.append("| MRR | 首位正确结果的排名倒数均值 | ").append(report.mrr()).append(" |\n");
        sb.append("| 关键词命中率 | Top5 切片里出现关键信息的比例 | ")
                .append(report.keywordHitRate()).append("% |\n");
        sb.append("| No-Hit 率 | 一条都没召回的比例（知识库缺口） | ")
                .append(report.noHitRate()).append("% |\n");
        sb.append("| 平均最高相似度 | 用于校准 minScore 阈值 | ")
                .append(report.avgTopScore()).append(" |\n");
        sb.append("| 平均检索耗时 | 含向量化（命中缓存时更低） | ")
                .append(report.avgLatencyMs()).append(" ms |\n\n");

        if (!report.misses().isEmpty()) {
            sb.append("### 未召回题目（知识库建设输入）\n\n");
            sb.append("| 题目 | 期望文档 | 实际 Top5 |\n|---|---|---|\n");
            for (ItemResult miss : report.misses()) {
                sb.append("| ").append(miss.question()).append(" | ").append(miss.expectedDocCode())
                        .append(" | ").append(String.join("、", miss.retrievedDocCodes())).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("### 逐题明细\n\n");
        sb.append("| # | 题目 | 期望文档 | 排名 | 关键词 | 最高分 | 耗时(ms) |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (ItemResult r : report.details()) {
            sb.append("| ").append(r.id()).append(" | ").append(r.question()).append(" | ")
                    .append(r.expectedDocCode()).append(" | ")
                    .append(r.hitDoc() ? r.rank() : "未命中").append(" | ")
                    .append(r.hitKeyword() ? "✓" : "✗").append(" | ")
                    .append("%.4f".formatted(r.topScore())).append(" | ")
                    .append(r.latencyMs()).append(" |\n");
        }
        return sb.toString();
    }

    /** 读取金标集（JSONL）。 */
    public List<GoldenItem> loadGoldenSet() {
        List<GoldenItem> items = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("rag-eval/golden-set.jsonl").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> map = JsonUtil.parseMap(line);
                if (map.isEmpty()) {
                    continue;
                }
                items.add(new GoldenItem(
                        String.valueOf(map.get("id")),
                        String.valueOf(map.get("question")),
                        String.valueOf(map.get("docCode")),
                        map.get("keyword") == null ? null : String.valueOf(map.get("keyword")),
                        map.get("category") == null ? null : String.valueOf(map.get("category"))));
            }
        } catch (Exception e) {
            log.error("[RAG评测] 金标集加载失败（评测将返回空结果）", e);
        }
        log.info("[RAG评测] 已加载金标集 {} 题", items.size());
        return items;
    }

    /** 结果转为可直接返回前端的结构。 */
    /**
     * 报告 → 接口返回对象。
     *
     * <p>比率下发小数（0–1）而不是 {@code "77.5%"} 字符串：
     * 字符串把数值与展示格式混在一起，前端做阈值判断还得再解析一遍百分号。
     * 同时把命中数（{@code hitAt1/3/5}）一并给出，便于核对分母。
     */
    public RagEvalVO toVO(Report report) {
        return new RagEvalVO(
                report.indexSize(),
                new RagEvalMetricsVO(
                        report.total(),
                        report.recallAt1(),
                        report.recallAt3(),
                        report.recallAt5(),
                        ratio(report.recallRate1()),
                        ratio(report.recallRate3()),
                        ratio(report.recallRate5()),
                        ratio(report.mrr()),
                        keywordHitCount(report),
                        ratio(report.keywordHitRate()),
                        noHitCount(report),
                        ratio(report.noHitRate()),
                        ratio(report.avgTopScore()),
                        report.avgLatencyMs()),
                report.misses().stream().map(RagEvaluator::toItemVO).toList(),
                report.details().stream().map(RagEvaluator::toItemVO).toList(),
                renderMarkdown(report));
    }

    /** 关键词命中的题数（由命中率反推，避免再改一次 Report 结构）。 */
    private static int keywordHitCount(Report report) {
        return (int) Math.round(report.keywordHitRate() * report.total() / 100.0);
    }

    /** 一条都没召回的题数。 */
    private static int noHitCount(Report report) {
        return (int) Math.round(report.noHitRate() * report.total() / 100.0);
    }

    /** 百分数（0–100 的 double）→ 比率（0–1 的 BigDecimal）。 */
    private static java.math.BigDecimal ratio(double percent) {
        return java.math.BigDecimal.valueOf(percent)
                .divide(java.math.BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
    }

    private static RagEvalVO.ItemResultVO toItemVO(ItemResult item) {
        return new RagEvalVO.ItemResultVO(
                item.id(),
                item.question(),
                item.expectedDocCode(),
                item.retrievedDocCodes().isEmpty() ? null : item.retrievedDocCodes().get(0),
                item.rank() == 0 ? null : item.rank(),
                item.topScore(),
                item.latencyMs(),
                item.hitKeyword(),
                null);
    }

    private static double pct(int part, int total) {
        return total == 0 ? 0.0 : round(part * 100.0 / total, 2);
    }

    private static double round(double value, int scale) {
        return java.math.BigDecimal.valueOf(value)
                .setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
