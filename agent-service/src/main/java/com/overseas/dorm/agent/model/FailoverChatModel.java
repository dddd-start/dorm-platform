package com.overseas.dorm.agent.model;

import com.overseas.dorm.agent.config.LangChain4jConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 带自动故障切换的同步模型包装器。
 *
 * <p>用途：意图路由、摘要生成、结构化输出这些不需要流式的场景。
 * 同步调用没有"已经输出一半"的问题，失败就整体重试下一个模型，
 * 因此这里的切换策略比流式版本更简单、也更彻底。
 */
@Slf4j
public class FailoverChatModel implements ChatModel {

    private final List<String> chain;
    private final LangChain4jConfig.ModelFactory factory;
    private final ModelRegistry registry;
    private final String role;

    public FailoverChatModel(List<String> chain, LangChain4jConfig.ModelFactory factory,
                             ModelRegistry registry, String role) {
        this.chain = chain.stream().distinct().toList();
        this.factory = factory;
        this.registry = registry;
        this.role = role;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Throwable lastError = null;
        for (int i = 0; i < chain.size(); i++) {
            String modelName = chain.get(i);
            try {
                ChatResponse response = factory.chat(modelName).chat(request);
                registry.reportSuccess(modelName);
                return response;
            } catch (Exception e) {
                lastError = e;
                registry.reportFailure(modelName, e);
                boolean hasNext = i + 1 < chain.size();
                if (hasNext) {
                    log.warn("[模型降级] {} 的模型 {} 同步调用失败（{}），切换到 {}",
                            role, modelName, summarize(e), chain.get(i + 1));
                }
            }
        }
        throw new IllegalStateException("所有 AI 模型均不可用（共 %d 个）".formatted(chain.size()), lastError);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        if (chain.isEmpty()) {
            return ChatModel.super.defaultRequestParameters();
        }
        return factory.chat(chain.get(0)).defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return chain.isEmpty() ? List.of() : factory.chat(chain.get(0)).listeners();
    }

    private static String summarize(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String oneLine = message.replaceAll("\\s+", " ");
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }
}
