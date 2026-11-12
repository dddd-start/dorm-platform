package com.overseas.dorm.common.security;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.exception.BizException;

/**
 * 登录用户上下文（ThreadLocal）。
 *
 * <p>写入时机：一次 HTTP 请求进入 {@code JwtAuthInterceptor} 并校验通过后；
 * 清理时机：请求结束的 {@code afterCompletion}，务必清理，否则线程池复用会串号。
 *
 * <p>流式/异步场景见 {@link #snapshot()}：SSE 生成可能发生在另一个线程，
 * 此时需要把 {@code LoginUser} 显式捕获并传递（见 AgentStreamService 的做法）。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 可能为 null：用于「允许匿名」的场景。 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 必须已登录，否则抛 2000。 */
    public static LoginUser require() {
        LoginUser u = HOLDER.get();
        if (u == null || u.getUserId() == null) {
            throw BizException.of(ResultCode.UNAUTHENTICATED);
        }
        return u;
    }

    public static Long requireUserId() {
        return require().getUserId();
    }

    /** 捕获当前身份快照，供异步线程恢复。 */
    public static LoginUser snapshot() {
        return HOLDER.get();
    }

    /** 在指定身份下执行逻辑，并在结束后恢复原身份（异步线程使用）。 */
    public static <T> T runAs(LoginUser user, java.util.function.Supplier<T> action) {
        LoginUser previous = HOLDER.get();
        HOLDER.set(user);
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

    public static void runAs(LoginUser user, Runnable action) {
        runAs(user, () -> {
            action.run();
            return null;
        });
    }

    public static void clear() {
        HOLDER.remove();
    }
}
