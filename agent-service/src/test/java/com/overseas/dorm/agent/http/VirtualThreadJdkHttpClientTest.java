package com.overseas.dorm.agent.http;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 这条测试钉住的是“流式读循环跑在哪条线程上”。
 *
 * <p>改造前 {@code JdkHttpClient} 的 {@code sendAsync().thenAccept(...)} 会把
 * 整个 SSE 读循环放在 {@code ForkJoinPool.commonPool} 上；2 核容器上
 * {@code CompletableFuture} 甚至会退化成 thread-per-task。这里断言回调线程
 * 必须是本项目自己启动的 {@code agent-model-stream-*} 虚拟线程。
 */
class VirtualThreadJdkHttpClientTest {

    private HttpServer server;
    private VirtualThreadJdkHttpClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(20)
    void streamingReadRunsOnDedicatedVirtualThread() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: one\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("data: two\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        client = VirtualThreadJdkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean virtual = new AtomicBoolean(false);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        client.execute(request("/sse"), new DefaultServerSentEventParser(), new ServerSentEventListener() {
            @Override
            public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                events.add(event.data());
                threadName.compareAndSet(null, Thread.currentThread().getName());
                virtual.set(Thread.currentThread().isVirtual());
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }

            @Override
            public void onClose() {
                done.countDown();
            }
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(events).containsExactly("one", "two");
        assertThat(virtual.get()).isTrue();
        assertThat(threadName.get()).startsWith("agent-model-stream-");
    }

    @Test
    @Timeout(20)
    void non2xxIsReportedAsHttpException() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/limited", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":\"rate limit\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        client = VirtualThreadJdkHttpClient.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        client.execute(request("/limited"), new DefaultServerSentEventParser(), new ServerSentEventListener() {
            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(HttpException.class);
        HttpException http = (HttpException) error.get();
        assertThat(http.statusCode()).isEqualTo(429);
        assertThat(http.getMessage()).contains("rate limit");
    }

    private HttpRequest request(String path) {
        return HttpRequest.builder()
                .url("http://127.0.0.1:" + server.getAddress().getPort() + path)
                .method(HttpMethod.GET)
                .build();
    }
}
