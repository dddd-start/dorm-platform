package com.overseas.dorm.agent.model;

import com.overseas.dorm.agent.http.VirtualThreadJdkHttpClient;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端回归：LangChain4j 的 {@code OpenAiStreamingChatModel} 在接上
 * {@link VirtualThreadJdkHttpClient} 之后，token / 完成回调必须落在
 * 本项目自己的虚拟线程上，而不是 {@code ForkJoinPool.commonPool}。
 *
 * <p>这条测试是“换回默认 JdkHttpClient 就会红”的那种，它不测业务，
 * 只测“模型流到底跑在哪条线程上”这个容量属性。
 */
class StreamingChatModelCallbackThreadTest {

    private HttpServer server;
    private VirtualThreadJdkHttpClient httpClient;

    @AfterEach
    void tearDown() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(30)
    void openAiStreamingCallbacksRunOnVirtualThread() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                write(out, chunk("你"));
                write(out, chunk("好"));
                write(out, """
                        data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        """);
                write(out, "data: [DONE]\n\n");
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        httpClient = VirtualThreadJdkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("test-key")
                .modelName("test-model")
                .timeout(Duration.ofSeconds(10))
                .logRequests(false)
                .logResponses(false)
                .httpClientBuilder(httpClient.asBuilder())
                .build();

        List<String> partialThreads = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> completeThread = new AtomicReference<>();
        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("你好")).build();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                partialThreads.add(Thread.currentThread().getName());
                answer.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                completeThread.set(Thread.currentThread());
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }
        });

        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(answer).hasToString("你好");
        assertThat(partialThreads).isNotEmpty()
                .allMatch(name -> name.startsWith("agent-model-stream-"));
        assertThat(completeThread.get()).isNotNull();
        assertThat(completeThread.get().isVirtual()).isTrue();
    }

    private static String chunk(String content) {
        return """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":null}]}

                """.formatted(content);
    }

    private static void write(OutputStream out, String data) {
        try {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
