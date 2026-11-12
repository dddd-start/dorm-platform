package com.overseas.dorm.agent.http;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 LangChain4j 的流式 HTTP 读循环放到<b>专用虚拟线程</b>上的 HTTP 客户端。
 *
 * <h3>为什么需要它（一个真实的容量陷阱）</h3>
 * LangChain4j 的 {@code OpenAiStreamingChatModel} 最终走
 * {@code JdkHttpClient.execute(...)}：它用
 * {@code HttpClient.sendAsync(...).thenAccept(resp -> parser.parse(resp.body(), listener))}
 * 读取 SSE。{@code thenAccept} 在“完成 future 的线程”上执行，而 JDK 把这条链交给
 * {@code CompletableFuture} 的默认执行器：
 * <ul>
 *   <li>{@code common.parallelism > 1} 时是 {@code ForkJoinPool.commonPool()}；</li>
 *   <li>{@code common.parallelism == 1}（典型 2 核容器）时退化成 thread-per-task；</li>
 *   <li>无论哪种，整个 SSE 读循环都占着这个线程，直到模型输出 {@code [DONE]}。</li>
 * </ul>
 * 结果是：聊天流的并发数不再由业务层的 {@code max-streams-global} /
 * {@code max-concurrent-calls} 决定，而由 JVM 的公共池决定；而且每 token 的
 * Redis 写、护栏、工具 HTTP 也都压在这个公共池线程上。这正是“30 并发看不出来、
 * 1000 并发把容器 hang 住”的来源之一。
 *
 * <h3>为什么是虚拟线程，而不是再开一个平台线程池</h3>
 * 流式读的绝大部分时间在等上游 token，属于阻塞 I/O。虚拟线程在
 * {@code InputStream.read()} 上会主动让出载体线程，1000 条流只需要很少的
 * 载体线程；平台线程池则会回到“每线程 1MB 栈”的老问题。
 *
 * <h3>为什么不用 {@code JdkHttpClient} 做流式</h3>
 * 它的流式实现走 {@code sendAsync().thenAccept(...)}，正好是我们要绕开的路径。
 * 这里改用同步的 {@code HttpClient.send(...)}，放在自己启动的虚拟线程里执行：
 * 读循环、SSE 解析、LangChain4j 回调都发生在这条虚拟线程上。
 * 非流式调用仍委托给 {@code JdkHttpClient}，保持与官方实现一致的行为
 * （错误映射、multipart、超时等）。
 *
 * <h3>取消/超时</h3>
 * {@link #execute(HttpRequest, ServerSentEventParser, ServerSentEventListener)}
 * 按接口约定立即返回，真正的读流在虚拟线程里。若上游长时间不产 token，
 * 读线程会阻塞到 HTTP 请求超时（{@code readTimeout}）为止；调用方（AgentTurnService）
 * 的 90 秒收口逻辑仍会先触发，但它无法强杀这条读线程 —— 这是与官方实现相同的
 * 取舍，后续若要支持“立即取消”，需要把 {@link InputStream} 暴露出来并在
 * abort 钩子里 close。
 */
@Slf4j
public final class VirtualThreadJdkHttpClient implements HttpClient, AutoCloseable {

    private static final String STREAM_THREAD_PREFIX = "agent-model-stream-";

    private final java.net.http.HttpClient jdkClient;
    private final JdkHttpClient delegate;
    private final ExecutorService streamExecutor;
    private final Duration readTimeout;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private VirtualThreadJdkHttpClient(Duration connectTimeout, Duration readTimeout) {
        this.readTimeout = readTimeout == null ? Duration.ofSeconds(120) : readTimeout;
        Duration connect = connectTimeout == null ? Duration.ofSeconds(15) : connectTimeout;
        this.jdkClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        // 非流式路径继续用官方实现：错误映射、multipart、超时行为都一致，
        // 也避免我们重复实现一套容易漂移的 HTTP 细节。
        this.delegate = JdkHttpClient.builder()
                .connectTimeout(connect)
                .readTimeout(this.readTimeout)
                .build();
        this.streamExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(STREAM_THREAD_PREFIX, 0).factory());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 非流式调用：直接复用官方 {@link JdkHttpClient}。 */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        return delegate.execute(request);
    }

    /**
     * 非流式异步调用也放到虚拟线程上，避免又落回 {@code CompletableFuture} 的默认池。
     */
    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> execute(request), streamExecutor);
    }

    /**
     * 流式调用：立即返回，读流在专用虚拟线程里进行。
     */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            notifyError(listener, new IllegalStateException("HTTP client 已关闭"));
            return;
        }
        try {
            streamExecutor.execute(() -> doStream(request, parser, listener));
        } catch (RejectedExecutionException e) {
            notifyError(listener, e);
        }
    }

    private void doStream(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        try {
            HttpResponse<InputStream> response = jdkClient.send(
                    toJdkRequest(request), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                notifyError(listener, new HttpException(response.statusCode(), readBody(response.body())));
                return;
            }

            SuccessfulHttpResponse successful = SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers().map())
                    // 流式响应没有“完整 body”；这里给空数组，避免下游 body() 返回 null。
                    .body(new byte[0])
                    .build();
            safeRun("onOpen", () -> listener.onOpen(successful));

            try (InputStream body = response.body()) {
                parser.parse(body, listener);
            }
            safeRun("onClose", listener::onClose);
        } catch (HttpTimeoutException e) {
            notifyError(listener, new TimeoutException(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyError(listener, e);
        } catch (Throwable t) {
            notifyError(listener, t);
        }
    }

    /**
     * 把 LangChain4j 的请求转成 JDK 请求。
     *
     * <p>流式对话只发 JSON；multipart 是音频/图片同步接口用的，本项目不涉及。
     * 真要用时应该走 {@link #execute(HttpRequest)} 的官方实现，而不是这里。
     */
    private java.net.http.HttpRequest toJdkRequest(HttpRequest request) {
        if (!request.formDataFields().isEmpty() || !request.formDataFiles().isEmpty()) {
            throw new UnsupportedOperationException(
                    "VirtualThreadJdkHttpClient 只支持 JSON 流式请求；multipart 请使用非流式 execute");
        }
        java.net.http.HttpRequest.Builder builder =
                java.net.http.HttpRequest.newBuilder(URI.create(request.url()));
        request.headers().forEach((name, values) -> {
            for (String value : values) {
                builder.header(name, value);
            }
        });
        java.net.http.HttpRequest.BodyPublisher body = request.body() == null
                ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);
        builder.method(request.method().name(), body);
        if (readTimeout != null) {
            builder.timeout(readTimeout);
        }
        return builder.build();
    }

    private static String readBody(InputStream body) {
        if (body == null) {
            return "";
        }
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void safeRun(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            // 回调抛异常不应打断读流；与官方 ServerSentEventListenerUtils.ignoringExceptions 一致。
            log.warn("[HTTP] SSE {} 回调异常（已忽略）：{}", what, t.toString());
        }
    }

    private static void notifyError(ServerSentEventListener listener, Throwable error) {
        try {
            listener.onError(error);
        } catch (Throwable t) {
            log.warn("[HTTP] SSE onError 回调异常（已忽略）：{}", t.toString());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        streamExecutor.shutdownNow();
        jdkClient.shutdownNow();
    }

    /**
     * 供 {@code OpenAiStreamingChatModelBuilder.httpClientBuilder(...)} 使用。
     *
     * <p>{@code OpenAiClient} 会先读 {@code connectTimeout()/readTimeout()}，
     * 再调用 {@code build()}。这里返回的 client 已经在构造时固定了超时，
     * 所以 builder 上的 setter 只做记录、不再二次修改共享 client ——
     * 多个模型共用一个 client，避免互相覆盖。
     */
    public HttpClientBuilder asBuilder() {
        return new SharedBuilder(this);
    }

    public static final class Builder {
        private Duration connectTimeout = Duration.ofSeconds(15);
        private Duration readTimeout = Duration.ofSeconds(120);

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public VirtualThreadJdkHttpClient build() {
            return new VirtualThreadJdkHttpClient(connectTimeout, readTimeout);
        }
    }

    private static final class SharedBuilder implements HttpClientBuilder {
        private final VirtualThreadJdkHttpClient client;

        private SharedBuilder(VirtualThreadJdkHttpClient client) {
            this.client = client;
        }

        @Override
        public Duration connectTimeout() {
            return null;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration connectTimeout) {
            return this;
        }

        @Override
        public Duration readTimeout() {
            return null;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            return this;
        }

        @Override
        public HttpClient build() {
            return client;
        }
    }
}
