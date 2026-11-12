package com.overseas.dorm.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Agent 服务的认证与功能权限拦截器。
 *
 * <p>与 dorm-service 使用同一套 JWT 与 Redis 会话白名单，因此用户登出、
 * 被禁用或角色变更后，Agent 侧也会立即失效。
 *
 * <p>用户令牌同时写入 {@link AgentTokenContext}，供工具通过 HTTP
 * 调用 dorm-service 时透传。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = resolveToken(request);
        if (token == null) {
            return reject(response, ResultCode.UNAUTHENTICATED, "请先登录");
        }
        LoginUser user = jwtVerifier.parse(token);
        if (user == null || !jwtVerifier.verifySession(user)) {
            return reject(response, ResultCode.UNAUTHENTICATED, "登录已失效，请重新登录");
        }

        String traceId = request.getHeader(TraceContext.HEADER);
        if (traceId != null && !traceId.isBlank()) {
            TraceContext.set(traceId);
        }
        UserContext.set(user);
        AgentTokenContext.set(token);

        boolean allowed = checkRole(handler, user);
        if (!allowed) {
            UserContext.clear();
            AgentTokenContext.clear();
            return reject(response, ResultCode.FORBIDDEN, "没有权限执行该操作");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
        AgentTokenContext.clear();
        TraceContext.clear();
    }

    private boolean checkRole(Object handler, LoginUser user) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireRole requireRole = method.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = method.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }
        if (requireRole.superAdminOnly()) {
            return user.isSuperAdmin();
        }
        return requireRole.value().length == 0
                || Arrays.asList(requireRole.value()).contains(user.getRole());
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        String queryToken = request.getParameter("token");
        return queryToken == null || queryToken.isBlank() ? null : queryToken.trim();
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
}
