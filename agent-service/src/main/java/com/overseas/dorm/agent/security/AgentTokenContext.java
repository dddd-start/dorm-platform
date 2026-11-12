package com.overseas.dorm.agent.security;

/**
 * 当前请求的用户 JWT。
 *
 * <p>为什么需要它：Agent 的工具执行线程拿不到 HTTP 请求对象，但调用
 * dorm-service 的 {@code /internal/**} 时必须带上用户身份。把令牌放进
 * ThreadLocal，并随 {@code AgentTurnContext} 一起跨线程恢复，
 * 就可以保证工具调用始终以“真实用户”的身份落到业务服务上。
 *
 * <p>令牌绝不写日志、不进入 Prompt、不落库。
 */
public final class AgentTokenContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private AgentTokenContext() {
    }

    public static void set(String token) {
        if (token == null || token.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(token);
        }
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static <T> T runAs(String token, java.util.function.Supplier<T> action) {
        String previous = HOLDER.get();
        set(token);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    public static void runAs(String token, Runnable action) {
        runAs(token, () -> {
            action.run();
            return null;
        });
    }
}
