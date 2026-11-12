package com.overseas.dorm.agent.model;

import com.overseas.dorm.agent.config.LangChain4jConfig;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带自动故障切换的流式模型包装器 —— 「主模型故障时自动切换」的真正实现。
 *
 * <h3>为什么需要它（一个真实的坑）</h3>
 * 最初的设计是：{@code ModelRegistry} 选出一个"当前可用"的模型，
 * 然后把这个模型塞进 AiServices。看起来没问题，但实际运行时
 * 主模型（一个免费的小模型）经常返回
 * {@code 429 rate_limit_error}，此时：
 * <pre>
 *   - ModelRegistry 只在"选择阶段"做了熔断判断；
 *   - 真正的调用阶段失败后，没有任何机制去尝试下一个模型；
 *   - 结果：用户看到一句"生成过程中出现异常"，而备用模型一直闲着。
 * </pre>
 * 也就是说，「降级链」必须落在<b>每一次真实调用</b>的周围，而不是选择阶段。
 * 这正是本类存在的意义。
 *
 * <h3>什么时候可以切换，什么时候不行</h3>
 * <ul>
 *   <li><b>可以切换</b>：请求还没产出任何 token 就失败了（限流、连接失败、超时）。
 *       此时用户什么都没看到，换一个模型重来体验上完全无感。</li>
 *   <li><b>不能切换</b>：已经吐出部分内容后才失败。此时若换模型重来，
 *       用户会看到两段拼接的回答，或前半段被重复 —— 这比"明确失败"更糟。
 *       这种情况下如实报错并保留半成品，用户可以点"重新回答"。</li>
 * </ul>
 * 实现上用 {@code anyOutput} 标志来区分这两种情况。
 *
 * <h3>与熔断的配合</h3>
 * 每次成功/失败都会回报给 {@link ModelRegistry}：
 * 连续失败到阈值的模型会被标记为熔断，后续请求在选择阶段就跳过它，
 * 不必每次都先等一个必然失败的调用。
 */
@Slf4j
public class FailoverStreamingChatModel implements StreamingChatModel {

    private final List<String> chain;
    private final LangChain4jConfig.ModelFactory factory;
    private final ModelRegistry registry;
    /** 用途标签，仅用于日志可读性。 */
    private final String role;

    public FailoverStreamingChatModel(List<String> chain, LangChain4jConfig.ModelFactory factory,
                                      ModelRegistry registry, String role) {
        this.chain = chain.stream().distinct().toList();
        this.factory = factory;
        this.registry = registry;
        this.role = role;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        attempt(request, handler, 0, null);
    }

    private void attempt(ChatRequest request, StreamingChatResponseHandler handler, int index, Throwable lastError) {
        if (index >= chain.size()) {
            log.error("[模型降级] {} 全部 {} 个模型均不可用，最后错误：{}", role, chain.size(),
                    lastError == null ? "-" : lastError.getMessage());
            handler.onError(lastError == null
                    ? new IllegalStateException("没有可用的 AI 模型")
                    : lastError);
            return;
        }
        String modelName = chain.get(index);
        AtomicBoolean anyOutput = new AtomicBoolean(false);

        StreamingChatResponseHandler wrapper = new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                anyOutput.set(true);
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                anyOutput.set(true);
                handler.onPartialResponse(partialResponse, context);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                anyOutput.set(true);
                handler.onPartialThinking(partialThinking);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                anyOutput.set(true);
                handler.onPartialThinking(partialThinking, context);
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                anyOutput.set(true);
                handler.onPartialToolCall(partialToolCall);
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
                anyOutput.set(true);
                handler.onPartialToolCall(partialToolCall, context);
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                anyOutput.set(true);
                handler.onCompleteToolCall(completeToolCall);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                registry.reportSuccess(modelName);
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                registry.reportFailure(modelName, error);
                boolean canFailover = !anyOutput.get() && index + 1 < chain.size();
                if (canFailover) {
                    log.warn("[模型降级] {} 的模型 {} 调用失败（{}），自动切换到 {} 重试",
                            role, modelName, summarize(error), chain.get(index + 1));
                    attempt(request, handler, index + 1, error);
                } else {
                    if (anyOutput.get()) {
                        log.warn("[模型降级] {} 的模型 {} 在输出部分内容后失败（{}），"
                                        + "为避免内容拼接错乱，不再切换模型",
                                role, modelName, summarize(error));
                    }
                    handler.onError(error);
                }
            }

            @Override
            public void onUnmappedRawEvent(Object rawEvent) {
                handler.onUnmappedRawEvent(rawEvent);
            }
        };

        try {
            factory.streaming(modelName).chat(request, wrapper);
        } catch (Exception e) {
            // 同步抛出的异常（例如参数非法、客户端构造失败）同样走降级逻辑
            wrapper.onError(e);
        }
    }

    /**
     * 默认请求参数取链首模型的配置。
     *
     * <p>AiServices 在合并调用参数时会读取它；如果返回 null，
     * 部分模型实现会因为缺少必要参数（如 maxTokens）而报错。
     */
    @Override
    public ChatRequestParameters defaultRequestParameters() {
        if (chain.isEmpty()) {
            return StreamingChatModel.super.defaultRequestParameters();
        }
        return factory.streaming(chain.get(0)).defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return chain.isEmpty() ? List.of() : factory.streaming(chain.get(0)).listeners();
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
