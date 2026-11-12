package com.overseas.dorm.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.domain.service.AuthService;
import com.overseas.dorm.domain.support.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 服务间内部接口鉴权。
 *
 * <p>两道校验同时通过才放行：
 * <ol>
 *   <li>{@code X-Internal-Token}：只有 agent-service 知道的服务令牌，
 *       阻止浏览器/外部直接访问内部接口；</li>
 *   <li>用户 JWT：身份不是由调用方声明的，而是从真实令牌解析并做会话白名单校验，
 *       因此 agent-service 无法伪造任意 userId 去读别人的数据。</li>
 * </ol>
 * 这与原单体里 Agent 工具直接调用 Service 的权限语义完全一致 ——
 * 只是把调用边界从进程内换成了 HTTP。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @Value("${dorm.internal.token:}")
    private String internalToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String provided = request.getHeader("X-Internal-Token");
        if (internalToken == null || internalToken.isBlank()
                || provided == null || !constantTimeEquals(provided, internalToken)) {
            return reject(response, ResultCode.UNAUTHENTICATED, "内部令牌无效");
        }

        // 目录类只读数据（区域编码 -> ID）在 Agent 启动时就要用，
        // 此时没有用户请求上下文，因此只校验服务令牌。
        if (request.getRequestURI().startsWith("/internal/catalog/")) {
            // 目录数据（区域编码等）是全局只读的，用“系统只读主体”执行，
            // 既不需要用户令牌，也不会把任何用户数据带出去。
            UserContext.set(LoginUser.builder()
                    .userId(0L).username("system")
                    .realName("内部服务").role(UserRole.SUPER_ADMIN)
                    .build());
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response, ResultCode.UNAUTHENTICATED, "缺少用户令牌");
        }
        String token = header.substring(7).trim();
        LoginUser loginUser = tokenProvider.parse(token);
        if (loginUser == null || !authService.verifySession(loginUser.getUserId(),
                loginUser.getSessionId(), loginUser.getAuthEpoch())) {
            return reject(response, ResultCode.UNAUTHENTICATED, "用户令牌已失效");
        }

        String traceId = request.getHeader("X-Trace-Id");
        if (traceId != null && !traceId.isBlank()) {
            TraceContext.set(traceId);
        }
        UserContext.set(loginUser);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
        TraceContext.clear();
    }

    private boolean reject(HttpServletResponse response, ResultCode code, String message)
            throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.fail(code, message);
        body.setTraceId(TraceContext.getOrCreate());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
