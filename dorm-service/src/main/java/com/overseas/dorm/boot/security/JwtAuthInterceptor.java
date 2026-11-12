package com.overseas.dorm.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.trace.TraceContext;
import com.overseas.dorm.domain.service.AuthService;
import com.overseas.dorm.domain.service.AuditService;
import com.overseas.dorm.domain.support.JwtTokenProvider;
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
 * 认证与功能权限拦截器。
 *
 * <p>执行顺序（务必保持）：
 * <pre>
 *   解析 token → 校验签名与过期 → 校验会话白名单 + 认证版本 → 写入 UserContext → 校验 @RequireRole
 * </pre>
 *
 * <p><b>【订正】</b>这里原先还写着一步「校验账号是否被禁用」，但代码里**从来没有这一步**，
 * 而禁用也没有清会话（F 系列的"权限/会话变更立即生效"修复之前，禁用对已签发令牌完全无效）。
 * 现在吊销是通过<b>会话白名单 + 认证版本（epoch）</b>实现的：禁用 / 重置口令 / 角色或区域变更
 * 都会递增该用户的认证版本，令牌里的 epoch 一旦落后即被拒 —— 不再需要每请求回查账号状态。
 * 注释与实现必须一致：本项目已经因为"注释承诺了但没实现"踩过三次坑（F1/F2/H11）。
 *
 * <p>为什么不用 Spring Security 的过滤器链：本系统的权限模型很简单
 * （三角色 + 区域数据权限），用拦截器 + 注解能把每一步都摊开在代码里，
 * 出问题时一眼能定位；而 Spring Security 的默认配置容易让人产生
 * 「配置了就安全了」的错觉，反而掩盖了真正的越权风险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final AuthService authService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行预检请求：否则浏览器跨域开发时全部 401
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = resolveToken(request);
        if (token == null) {
            return reject(response, ResultCode.UNAUTHENTICATED);
        }

        LoginUser loginUser = tokenProvider.parse(token);
        if (loginUser == null) {
            return reject(response, ResultCode.UNAUTHENTICATED);
        }
        // 会话白名单 + 认证版本：登出/被禁用/重置口令/角色区域变更/强制下线后，token 立即失效。
        // 两条校验用一次 MGET 完成（与原来一次 hasKey 的往返次数相同）。
        if (!authService.verifySession(loginUser.getUserId(), loginUser.getSessionId(),
                loginUser.getAuthEpoch())) {
            return reject(response, ResultCode.UNAUTHENTICATED);
        }

        UserContext.set(loginUser);
        boolean allowed = checkRole(request, response, handler, loginUser);
        if (!allowed) {
            // 【F5 修复】preHandle 返回 false 时 Spring **不会**调用 afterCompletion，
            // 于是被拒的那次请求会把 LoginUser 留在 Tomcat 工作线程的 ThreadLocal 里：
            // 下一个复用该线程的请求若在写入 UserContext 之前读取它（例如某个工具/审计逻辑），
            // 就会读到上一个用户的身份。拒绝路径必须自己清干净。
            UserContext.clear();
        }
        return allowed;
    }

    private boolean checkRole(HttpServletRequest request, HttpServletResponse response,
                              Object handler, LoginUser loginUser) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }
        if (requireRole.superAdminOnly()) {
            if (loginUser.isSuperAdmin()) {
                return true;
            }
            auditPermissionDenied(request, loginUser, "SUPER_ADMIN_ONLY");
            return reject(response, ResultCode.FORBIDDEN, "该操作仅总管理员可执行");
        }
        if (requireRole.value().length == 0) {
            return true;
        }
        boolean allowed = Arrays.asList(requireRole.value()).contains(loginUser.getRole());
        if (allowed) {
            return true;
        }
        auditPermissionDenied(request, loginUser, requireRole.message());
        return reject(response, ResultCode.FORBIDDEN, requireRole.message());
    }

    /**
     * 记录越权尝试。
     *
     * <p>护拦失败本身是重要的安全信号：一个员工账号反复请求管理接口，
     * 要么是前端 bug，要么是在试探。必须留痕，否则事后无法回溯。
     */
    private void auditPermissionDenied(HttpServletRequest request, LoginUser user, String message) {
        try {
            auditService.fail(AuditEnums.Module.ADMIN, "PERMISSION_DENIED",
                    request.getMethod() + " " + request.getRequestURI(), null,
                    "角色 %s 访问被拒：%s".formatted(user.getRole(), message));
        } catch (Exception ignored) {
            // 审计失败不影响拒绝本身
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        // 线程池复用会串号，必须清理
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        // SSE 场景：EventSource 无法自定义请求头，因此额外支持查询参数传 token
        String queryToken = request.getParameter("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken.trim();
        }
        return null;
    }

    private boolean reject(HttpServletResponse response, ResultCode code) throws Exception {
        return reject(response, code, code.getMessage());
    }

    private boolean reject(HttpServletResponse response, ResultCode code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.fail(code, message);
        body.setTraceId(TraceContext.getOrCreate());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}
