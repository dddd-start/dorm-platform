package com.overseas.dorm.agent.aiservice;

import com.overseas.dorm.agent.config.LangChain4jConfig;
import com.overseas.dorm.agent.model.ModelRegistry;
import com.overseas.dorm.common.enums.AgentType;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图路由：决定这一轮对话交给哪个专家 Agent。
 *
 * 纯模型路由有两个问题：
 * <ol>
 *   <li><b>延迟与成本</b>：每一轮对话都要多一次模型调用，哪怕用户只是问"还有房吗"
 *，这在小模型上也要几百毫秒；</li>
 *   <li><b>不稳定</b>：模型可能这轮判成"房源"、下轮把同样的话判成"政策"，
 *       用户会感觉助手"精神分裂"。而路由结果的变化会导致整个系统提示词变化，
 *       连带着回答风格都变了。</li>
 * </ol>
 *
 * <p>关键词打分的优势是<b>确定、即时、零成本</b>；它的弱点是覆盖面有限。
 * <pre>
 *   第一层：关键词打分（覆盖 90% 的常见表达）
 *   第二层：若最高分低于阈值（即"说不清是哪类"），才调用快模型做一次判断
 *   第三层：模型判断失败或超时 → 回退到默认 Agent（房源顾问，它挂了最全的查询工具）
 * </pre>
 * 这样既保证了常见问题的即时响应，又不会因为关键词没覆盖到而把用户晾着。
 *
 * 路由只是内部实现细节，用户不应该感知到"系统分不清你想问什么"。
 * 回退到能力最全的专家 + 让它自己调用需要的工具，用户得到的结果是一样的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final ModelRegistry modelRegistry;
    private final LangChain4jConfig.ModelFactory modelFactory;
    private final com.overseas.dorm.agent.config.AgentProperties properties;

    /** 关键词 -> 权重。权重体现该词对意图判定的确定性。 */
    private static final Map<AgentType, Map<String, Integer>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(AgentType.ORDER, Map.ofEntries(
                Map.entry("订单", 10), Map.entry("退房", 10), Map.entry("办理入住", 10),
                Map.entry("入住办理", 10), Map.entry("check out", 8), Map.entry("check in", 8),
                Map.entry("提前退", 8), Map.entry("续住", 6), Map.entry("退订", 6),
                Map.entry("已经入住", 8), Map.entry("退掉", 6)));

        KEYWORDS.put(AgentType.APPLY, Map.ofEntries(
                Map.entry("申请", 8), Map.entry("提交申请", 10), Map.entry("帮我订", 8),
                Map.entry("帮我申请", 10), Map.entry("进度", 6), Map.entry("审批状态", 8),
                Map.entry("通过了吗", 8), Map.entry("驳回", 8), Map.entry("取消申请", 10),
                Map.entry("我的行程", 8), Map.entry("申请单", 8)));

        KEYWORDS.put(AgentType.POLICY, Map.ofEntries(
                Map.entry("规定", 8), Map.entry("制度", 8), Map.entry("政策", 8),
                Map.entry("能不能", 4), Map.entry("可以带家属", 8), Map.entry("报销", 8),
                Map.entry("费用标准", 8), Map.entry("注意", 4), Map.entry("须知", 8),
                Map.entry("安全", 6), Map.entry("消防", 8), Map.entry("签证", 6),
                Map.entry("电压", 6), Map.entry("插座", 6), Map.entry("流程", 6),
                Map.entry("怎么办", 4), Map.entry("多久", 4), Map.entry("多少晚", 6)));

        KEYWORDS.put(AgentType.ROOM, Map.ofEntries(
                Map.entry("空房", 10), Map.entry("还有房", 10), Map.entry("房源", 10),
                Map.entry("房间", 8), Map.entry("床位", 8), Map.entry("公寓", 8),
                Map.entry("房型", 8), Map.entry("单人间", 8), Map.entry("双人间", 8),
                Map.entry("多少钱", 6), Map.entry("价格", 6), Map.entry("有没有", 4),
                Map.entry("位置", 4), Map.entry("地址", 4), Map.entry("推荐", 4)));

        KEYWORDS.put(AgentType.OPS, Map.ofEntries(
                Map.entry("待审批", 8), Map.entry("入住率", 10), Map.entry("运营", 8),
                Map.entry("统计", 6), Map.entry("有多少申请", 8), Map.entry("未退房", 8),
                Map.entry("逾期", 8), Map.entry("空置", 8), Map.entry("房源健康", 10)));
    }

    /** 低于这个分数就认为关键词判断不可靠，需要问模型。 */
    private static final int MODEL_ASSIST_THRESHOLD = 6;

    /**
     * 判断意图。
     *
     * @param message 用户消息
     * @param admin   是否管理员（员工不会被路由到运维 Agent）
     */
    public AgentType route(String message, boolean admin) {
        if (message == null || message.isBlank()) {
            return AgentType.ROOM;
        }
        String normalized = message.toLowerCase();

        AgentType best = null;
        int bestScore = 0;
        Map<AgentType, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<AgentType, Map<String, Integer>> entry : KEYWORDS.entrySet()) {
            AgentType type = entry.getKey();
            if (type == AgentType.OPS && !admin) {
                continue;
            }
            int score = 0;
            for (Map.Entry<String, Integer> keyword : entry.getValue().entrySet()) {
                if (normalized.contains(keyword.getKey().toLowerCase())) {
                    score += keyword.getValue();
                }
            }
            scores.put(type, score);
            if (score > bestScore) {
                bestScore = score;
                best = type;
            }
        }

        if (best != null && bestScore >= MODEL_ASSIST_THRESHOLD) {
            log.debug("[Agent路由] 关键词判定 {}（得分 {}，各意图分：{}）", best, bestScore, scores);
            return best;
        }
        // 关键词不足以判断：交给快模型
        AgentType modelChoice = classifyByModel(message, admin);
        if (modelChoice != null) {
            log.debug("[Agent路由] 模型判定 {}（关键词最高分仅 {}）", modelChoice, bestScore);
            return modelChoice;
        }
        // 兜底：房源顾问挂载了最通用的查询工具
        log.debug("[Agent路由] 回退到默认 ROOM（关键词最高分 {}）", bestScore);
        return best != null ? best : AgentType.ROOM;
    }

    private AgentType classifyByModel(String message, boolean admin) {
        try {
            ModelRegistry.ModelChoice choice = modelRegistry.select(AgentType.ROUTER, true);
            ChatModel model = modelFactory.chat(choice.modelName());
            IntentClassifier classifier = AiServices.builder(IntentClassifier.class)
                    .chatModel(model)
                    .build();
            String raw = classifier.classify(message,
                    admin ? "ROOM / POLICY / APPLY / ORDER / OPS" : "ROOM / POLICY / APPLY / ORDER");
            if (raw == null) {
                return null;
            }
            String cleaned = raw.trim().toUpperCase().replaceAll("[^A-Z]", " ").trim();
            for (String token : cleaned.split("\\s+")) {
                for (AgentType type : AgentType.values()) {
                    if (type.name().equals(token)) {
                        if (type == AgentType.OPS && !admin) {
                            continue;
                        }
                        return type;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            // 模型不可用或输出不可解析：静默回退，不打扰用户
            log.debug("[Agent路由] 模型辅助判定失败，使用兜底策略：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 意图分类的 AiService 接口。
     *
     * <p>注意返回类型是 {@code String} 而不是枚举：如果声明成枚举，
     * 一旦模型输出了枚举里没有的值，LangChain4j 会抛解析异常，* 而我们希望的是"解析不了就回退"，所以这里拿原始文本自己解析，
     * 把容错逻辑掌握在自己手里。
     */
    interface IntentClassifier {

        @dev.langchain4j.service.SystemMessage("""
                你是意图分类器。只输出一个类别词，不要输出任何其它内容、标点或解释。
                """)
        String classify(@UserMessage String message, @V("可选类别") String categories);
    }

    /** 供测试与运维展示：各意图的关键词表。 */
    public static Map<String, List<String>> keywordTable() {
        Map<String, List<String>> table = new LinkedHashMap<>();
        KEYWORDS.forEach((type, keywords) -> table.put(type.name(), List.copyOf(keywords.keySet())));
        return table;
    }
}
