package com.overseas.dorm.agent.security;

import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.security.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 校验（与 dorm-service 共用同一签发密钥与 Redis 会话白名单）。
 *
 * <p>Agent 服务不签发令牌，只验证。会话白名单与认证版本（epoch）直接读共享 Redis：
 * 用户在业务端登出、被禁用、改角色后，Agent 的旧令牌也会立刻失效。
 */
@Slf4j
@Component
public class JwtVerifier {

    private final String issuer;
    private final SecretKey key;
    private final StringRedisTemplate redis;

    public JwtVerifier(@Value("${dorm.security.jwt.issuer:overseas-dorm}") String issuer,
                       @Value("${dorm.security.jwt.secret:}") String secret,
                       StringRedisTemplate redis) {
        this.issuer = issuer;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.redis = redis;
    }

    /** 解析并校验签名/过期；失败返回 null。 */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return LoginUser.builder()
                    .userId(Long.valueOf(claims.getSubject()))
                    .username(claims.get("username", String.class))
                    .realName(claims.get("realName", String.class))
                    .role(UserRole.of(claims.get("role", String.class)))
                    .regionId(claims.get("regionId") == null ? null
                            : ((Number) claims.get("regionId")).longValue())
                    .employeeNo(claims.get("employeeNo", String.class))
                    .department(claims.get("department", String.class))
                    .sessionId(claims.get("sid", String.class))
                    .authEpoch(claims.get("epoch") == null ? null
                            : ((Number) claims.get("epoch")).longValue())
                    .build();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }

    /** 会话白名单 + 认证版本校验；Redis 不可用时 fail-closed。 */
    public boolean verifySession(LoginUser user) {
        if (user == null || user.getSessionId() == null || user.getAuthEpoch() == null) {
            return false;
        }
        try {
            List<String> values = redis.opsForValue().multiGet(List.of(
                    RedisKeys.session(user.getUserId(), user.getSessionId()),
                    RedisKeys.authEpoch(user.getUserId())));
            String session = values == null || values.size() < 2 ? null : values.get(0);
            String epochValue = values == null || values.size() < 2 ? null : values.get(1);
            if (session == null) {
                return false;
            }
            long current = 0L;
            if (epochValue != null && !epochValue.isBlank()) {
                try {
                    current = Long.parseLong(epochValue);
                } catch (NumberFormatException e) {
                    current = Long.MAX_VALUE;
                }
            }
            return user.getAuthEpoch() == current;
        } catch (Exception e) {
            log.error("会话校验失败（Redis 异常），按已失效处理 userId={}", user.getUserId(), e);
            return false;
        }
    }
}
