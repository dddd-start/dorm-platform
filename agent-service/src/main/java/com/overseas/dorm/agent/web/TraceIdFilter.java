package com.overseas.dorm.agent.web;

import com.overseas.dorm.common.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全链路追踪过滤器。
 *
 * <p>职责很单一，但位置很关键 —— 它必须是<b>最外层</b>的过滤器：
 * <ul>
 *   <li>接收上游（网关/前端）传下来的 {@code X-Trace-Id}，没有就新生成一个；</li>
 *   <li>写入 MDC 让日志格式自动带上 traceId；</li>
 *   <li>回写响应头，让前端在报错时能把 traceId 提供给客服；</li>
 *   <li>请求结束清理，防止线程复用导致 traceId 串号。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.set(traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (cost > 3000) {
                logger.warn("[慢请求] %s %s 耗时 %dms traceId=%s".formatted(
                        request.getMethod(), request.getRequestURI(), cost, traceId));
            }
            TraceContext.clear();
        }
    }
}
