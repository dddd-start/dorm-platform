package com.overseas.dorm.boot.config;

import com.overseas.dorm.boot.security.InternalAuthInterceptor;
import com.overseas.dorm.boot.security.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 *
 * <p>两条拦截链彼此独立：
 * <ul>
 *   <li>{@code /api/**}：面向浏览器，JWT 校验；</li>
 *   <li>{@code /internal/**}：面向 agent-service，内部令牌 + 用户 JWT 校验。</li>
 * </ul>
 * 业务接口不提供 SPA 的 history 回退 —— 前端由 ui-service 独立托管，
 * 后端接口的 404 必须保持真实的 404。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final InternalAuthInterceptor internalAuthInterceptor;

    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/meta/enums",
            "/api/health",
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(PUBLIC_PATHS)
                .order(1);

        // /internal/health 曾在排除列表里，等于把内部面暴露成"无鉴权健康检查"：
        // 服务绑 0.0.0.0 时任何人 curl 8081 就能摸到内部接口族。
        // 现在统一要求内部令牌；健康检查请走 /actuator/health（本服务已暴露）。
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**")
                .order(2);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*",
                        "http://192.168.*.*:*", "http://10.*.*.*:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
