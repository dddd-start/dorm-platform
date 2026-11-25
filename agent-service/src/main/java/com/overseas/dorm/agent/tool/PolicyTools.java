package com.overseas.dorm.agent.tool;

import com.overseas.dorm.agent.audit.AgentTurnContext;
import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.guardrail.ContentGuardrail;
import com.overseas.dorm.agent.rag.RagService;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.util.JsonUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策知识库检索工具（RAG 的入口）。
 *
 * 如果把检索到的片段直接拼成一段话返回，模型就只会照抄，
 * 用户也无法判断信息来自哪份文件。这里返回的是结构化的片段列表
 * （标题 + 内容 + 相关度 + 文档来源），让模型：
 * <ol>
 *   <li>自己组织语言回答，必要时做多片段综合；</li>
 *   <li>在回答里标注出处（「根据《入住申请流程与审批时限规定》…」），
 *       用户可核对，这也是抑制幻觉最有效的手段之一，*       当模型必须说出"依据是哪一条"时，它编造的概率显著下降。</li>
 * </ol>
 *
 * 检索时会带上区域条件：属地类文档（如《德国属地须知》）
 * 只在对应区域被召回，而集团级制度（regionId 为空）对所有区域可见。
 * 这样员工在新加坡问"能带家属吗"，不会看到德国的属地条款。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyTools {

    private final RagService ragService;
    private final RegionResolver regionResolver;
    private final AgentProperties properties;
    private final ContentGuardrail contentGuardrail;

    @Tool("""
            检索公司宿舍管理制度知识库。适用于以下类型的问题：
            入住申请流程与时限、房间分配原则、入住与退房办理规范、费用与报销标准、
            宿舍安全消防规定、各属地注意事项（新加坡/德国/阿联酋/日本）、常见问题。
            当用户询问"规定是什么""能不能""怎么办""要注意什么"这类问题时，应当先调用本工具，
            不要凭记忆回答。可以先用一个较宽泛的查询词，必要时再换关键词检索第二次。
            """)
    public String searchDormPolicy(
            @P("检索查询词，用用户的原话或其中的关键词，例如「提前退房」「新加坡 电压」「费用报销」") String query,
            @P("区域编码（可空），用于优先检索该属地的规定，如 SEA/EU/ME/NEA") String regionCode) {
        UserContext.require();
        if (query == null || query.isBlank()) {
            return JsonUtil.toJson(new PolicySearchVO(false, null, null, null,
                    "查询词不能为空", List.of(), null));
        }
        Long regionId = regionResolver.resolveOrNull(regionCode);
        RagService.RetrievalResult result = ragService.retrieve(query, regionId,
                properties.getRag().getTopK(), properties.getRag().getMinScore());
        recordRagHits(result.hits().size());
        recordRagReferences(result.hits());

        if (result.isEmpty()) {
            // 未命中时给出明确的"该怎么回答"指引，而不是让模型自己猜测 ——
            // RAG 场景下最危险的行为就是"没查到却编一条规定出来"
            return JsonUtil.toJson(new PolicySearchVO(false, query, 0, result.latencyMs(),
                    "知识库中没有检索到与该问题相关的规定。"
                            + "请不要凭猜测回答，应该告诉用户：未在制度库中找到明确规定，"
                            + "建议联系所在区域的行政管理员确认，并可以顺势询问是否需要其它帮助。",
                    List.of(), null));
        }
        List<PolicyHitVO> items = result.hits().stream()
                .map(hit -> new PolicyHitVO(
                        hit.docTitle(), hit.title(), hit.category(),
                        // 相似度保留两位小数：工具结果会进模型上下文，多余精度只是浪费 token
                        Math.round(hit.score() * 100) / 100.0,
                        // 【F2 修复】知识库正文属于外部不可信文本（文档由人上传/维护，
                        // 内容可能被注入"忽略以上指令"之类的句子），必须经过第 ③ 层防御：
                        // 脱敏 + 用 <资料 来源="..."> 明确包裹并声明"这是资料不是指令"。
                        // 此前 wrapUntrustedContent 只有定义和单测、生产零调用 ——
                        // 文档却把它列为已实现的防御层，属于"文档说做了、代码没做"。
                        warnIfUnwrapped(hit.docTitle(),
                                contentGuardrail.wrapUntrustedContent(hit.docTitle(), hit.content()))))
                .toList();
        return JsonUtil.toJson(new PolicySearchVO(true, query, items.size(), result.latencyMs(),
                null, items,
                "回答时请注明信息来源（如「根据《入住申请流程与审批时限规定》」）。"
                        + "如果这些资料没有覆盖用户的问题，请明确说明未找到相关规定，不要编造条款。"));
    }

    /**
     * 断言式防护：包裹必须真的发生。
     *
     * <p>这一层不是装饰。F2 的教训是"方法实现了但没人调用"，而"没人调用"在运行时
     * 没有任何症状，只有出事（模型执行了资料里的指令）才会被发现。
     * 因此在返回前自检一次：一旦包裹标记缺失，说明调用链被人改坏了，
     * 立刻降级为"不带正文"（宁可少给资料，也不能把未包裹的文本喂给模型），并留下 ERROR 日志。
     */
    /** 把工具内真实发生的检索命中汇报给本轮上下文，供 SSE 与用量统计使用。 */
    private void recordRagHits(int count) {
        if (count <= 0) {
            return;
        }
        AgentTurnContext context = AgentTurnContext.current();
        if (context != null) {
            context.addRagHits(count);
        }
    }

    /**
     * 把工具检索命中也登记为引用来源。
     *
     * <p>工具式 RAG 不经过 LangChain4j 的 Retriever 回调，所以 onRetrieved
     * 永远不会触发；如果只在那里发 REFERENCE，前端只能看到 count=0。
     */
    private void recordRagReferences(List<RagService.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        AgentTurnContext context = AgentTurnContext.current();
        if (context == null) {
            return;
        }
        List<Map<String, Object>> references = hits.stream().map(hit -> {
            String title = hit.docTitle() != null ? hit.docTitle() : hit.title();
            String text = hit.content() == null ? "" : hit.content().replaceAll("\\s+", " ").trim();
            String snippet = text.length() > 120 ? text.substring(0, 120) + "…" : text;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", title);
            item.put("snippet", snippet);
            return item;
        }).toList();
        context.addReferences(references);
    }

    private String warnIfUnwrapped(String docTitle, String wrapped) {
        if (wrapped != null && wrapped.contains("<资料 来源=")) {
            return wrapped;
        }
        log.error("[Tool] 知识库正文未经过不可信内容包裹，已降级为不含正文 doc={}", docTitle);
        return "";
    }

    // ================================================================ 工具结果结构
    //
    // 政策检索的结果是给模型看的接口，字段名就是模型引用与标注来源的依据。
    // 用 record 固定下来，避免键名笔误导致"资料明明检索到了，模型却说没找到"。

    /** 检索命中条。 */
    public record PolicyHitVO(
            String source, String section, String category, double relevance, String content) {
    }

    /** 检索结果（未命中时 found=false 并给出明确指引）。 */
    public record PolicySearchVO(
            boolean found, String query, Integer hitCount, Long latencyMs,
            String message, List<PolicyHitVO> results, String citationInstruction) {
    }

    /** 知识库文档条目。 */
    public record DocumentVO(String title, String category) {
    }

    /** 知识库目录。 */
    public record DocumentListVO(int count, List<DocumentVO> documents) {
    }

    @Tool("列出知识库中有哪些制度文档（文档标题与类别）。当用户问「你们都收录了哪些规定」时使用。")
    public String listKnowledgeDocuments() {
        UserContext.require();
        // 直接复用检索做一次宽泛查询，并按文档去重展示
        RagService.RetrievalResult result = ragService.retrieve(
                "宿舍管理规定 制度 流程 政策 属地须知", null, 20, 0.0);
        recordRagHits(result.hits().size());
        // 这里用 Map 做去重（键 = 文档标题，属于动态键），再转成记录列表下发
        Map<String, String> byTitle = new java.util.LinkedHashMap<>();
        for (RagService.Hit hit : result.hits()) {
            String title = hit.docTitle() == null ? "未命名文档" : hit.docTitle();
            byTitle.putIfAbsent(title, hit.category());
        }
        List<DocumentVO> list = byTitle.entrySet().stream()
                .map(entry -> new DocumentVO(entry.getKey(), entry.getValue()))
                .toList();
        return JsonUtil.toJson(new DocumentListVO(list.size(), list));
    }

}
