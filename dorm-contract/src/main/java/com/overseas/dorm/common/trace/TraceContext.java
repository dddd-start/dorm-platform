package com.overseas.dorm.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路追踪上下文。
 *
 * <p>一次用户请求可能横跨：HTTP 接入 -> 业务事务 -> 模型调用 -> 工具调用 ->
 * 多次数据库查询 -> SSE 事件推送 -> 异步 Outbox 消费。要把这些串成一条链，
 * 必须有统一的 traceId，它需要满足三个条件：
 * <ol>
 *   <li><b>能跨线程</b>：SSE 生成线程与异步任务线程都要带上，靠显式传递
 *       （{@link #wrap}）而不是只依赖 MDC 的自动继承；</li>
 *   <li><b>能落库</b>：审计表、消息表、工具调用表、检索日志都记录 traceId，
 *       于是「用户投诉某句话」可以反查到当时的检索片段与工具参数；</li>
 *   <li><b>能透传前端</b>：SSE 的每条事件都带 traceId，前端报错时可直接定位。</li>
 * </ol>
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String get() {
        String t = HOLDER.get();
        if (t == null) {
            t = MDC.get(MDC_KEY);
        }
        return t;
    }

    public static String getOrCreate() {
        String t = get();
        if (t == null || t.isBlank()) {
            t = newTraceId();
            set(t);
        }
        return t;
    }

    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        HOLDER.set(traceId);
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 捕获当前 traceId，用于跨线程传递。 */
    public static String snapshot() {
        return get();
    }

    /** 在指定 traceId 下执行逻辑，结束后恢复（异步/流式线程使用）。 */
    public static <T> T runWith(String traceId, java.util.function.Supplier<T> action) {
        String previous = HOLDER.get();
        set(traceId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }

    public static void runWith(String traceId, Runnable action) {
        runWith(traceId, () -> {
            action.run();
            return null;
        });
    }

    /** 包装 Runnable，使子线程继承 traceId（配合线程池使用时务必调用）。 */
    public static Runnable wrap(Runnable task) {
        String traceId = get();
        return () -> runWith(traceId, task);
    }
}
