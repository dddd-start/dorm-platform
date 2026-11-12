package com.overseas.dorm.agent.model;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

/**
 * 压测/离线测试用的模拟模型。
 *
 * <h3>为什么需要它</h3>
 * 压测流式接口时如果直接打真实模型，会遇到三个问题：
 * <ol>
 *   <li><b>成本</b>：100 并发 × 每轮几千 token，一次压测就烧掉可观的额度；</li>
 *   <li><b>结果不可比</b>：模型服务的排队与限流会主导延迟，
 *       测出来的其实是"上游的容量"，而不是"我们自己的 SSE 链路容量"；</li>
 *   <li><b>不可重复</b>：上游抖动让同一份压测脚本两次跑出完全不同的数字。</li>
 * </ol>
 * 用模拟模型可以把「我们自己的链路」单独拎出来压：
 * Redis Stream 写入、Pub/Sub 广播、SSE 推送、滑窗护栏、
 * 线程池与并发计数 —— 这些才是我们真正要保证的部分。
 *
 * <h3>它模拟了什么</h3>
 * 按固定间隔逐块吐出预设文本，并返回带 token 用量的完整响应。
 * 间隔可配置，用来模拟不同档位模型的出字速度（TTFT 与吞吐）。
 *
 * <h3>安全开关</h3>
 * 只有显式设置 {@code dorm.agent.mock.enabled=true} 才会启用（默认关闭）。
 * 这个开关绝不能在生产环境打开 —— 那会让助手变成复读机，
 * 所以它在日志里会以醒目方式打印当前模式。
 */
public final class MockModelFactory {

    private MockModelFactory() {
    }

    /** 生成一段贴近真实业务的中文回答，便于观察前端渲染效果。 */
    private static String sampleAnswer() {
        return """
                根据公司《入住申请流程与审批时限规定》，员工出差前需要先在系统中提交宿舍入住申请，
                填写目的地区域、目的地城市、入住与退房日期、出差单号以及出差事由。

                提交后由所在区域的区域管理员在 24 小时内完成审批并分配房间与床位。
                审批通过后系统会生成入住订单，您可以在「我的订单」中查看房间号与床位号。

                请在入住日当天办理入住，若超过入住日次日 23:59 仍未办理，申请将自动失效并释放床位。
                退房请在退房日 12:00 前完成；如需提前退房，请填写提前退房原因。
                """;
    }

    /** 模拟一次流式生成。 */
    public static final class MockStreamingChatModel implements StreamingChatModel {

        private final int chunkSize;
        private final long delayMillis;

        /** 是否异步流式（见 AgentProperties.Mock.asyncStreaming 的说明）。 */
        private final boolean asyncStreaming;

        public MockStreamingChatModel(int chunkSize, long delayMillis, boolean asyncStreaming) {
            this.chunkSize = Math.max(chunkSize, 1);
            this.delayMillis = Math.max(delayMillis, 0);
            this.asyncStreaming = asyncStreaming;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            if (asyncStreaming) {
                // 关键：在**独立线程**里跑流。同步跑（默认）会让调用方一直阻塞到生成结束，
                // 使"生成超时"这类与生成并发的收口逻辑不可达，无法被验证。
                Thread.ofVirtual().name("mock-stream").start(() -> stream(request, handler));
                return;
            }
            stream(request, handler);
        }

        private void stream(ChatRequest request, StreamingChatResponseHandler handler) {
            String answer = sampleAnswer();
            try {
                for (int i = 0; i < answer.length(); i += chunkSize) {
                    String chunk = answer.substring(i, Math.min(answer.length(), i + chunkSize));
                    handler.onPartialResponse(chunk);
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                }
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(answer))
                        .metadata(ChatResponseMetadata.builder()
                                .modelName("mock/streaming")
                                .tokenUsage(new TokenUsage(answer.length() / 2, answer.length() / 2))
                                .finishReason(FinishReason.STOP)
                                .build())
                        .build());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handler.onError(e);
            } catch (Exception e) {
                handler.onError(e);
            }
        }
    }

    /** 模拟一次同步生成（用于意图路由 / 摘要这类场景）。 */
    public static final class MockChatModel implements ChatModel {

        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("ROOM"))
                    .metadata(ChatResponseMetadata.builder()
                            .modelName("mock/chat")
                            .tokenUsage(new TokenUsage(10, 5))
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();
        }
    }

    /** 供配置校验：模拟模式下的模型名。 */
    public static List<String> mockModelNames() {
        return List.of("mock/streaming", "mock/chat");
    }
}
