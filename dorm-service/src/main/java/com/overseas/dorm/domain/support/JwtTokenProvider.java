package com.overseas.dorm.domain.support;

import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.domain.config.DormSecurityProperties;
import com.overseas.dorm.domain.entity.SysUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 令牌签发与校验。
 *
 * <p>几个有意的设计选择：
 * <ol>
 *   <li><b>不存服务端会话</b>，但保留 jti（sessionId）写入 Redis 白名单，*       纯无状态 JWT 无法「强制下线」，而管理员禁用某人账号后，
 *       他的 token 还能用到过期，这在安全上是不可接受的；</li>
 *   <li><b>角色与区域一并写入 Claims</b>：避免每个请求都查库；</li>
 *   <li><b>只放身份，不放权限明细</b>：权限判断始终走服务端逻辑（@RequireRole + DataScope），
 *       令牌里的 role 只用于"这个角色是谁"，它不是权限凭据本身，防止"改 token 提权"；</li>
 *   <li><b>【F4 修复】claim 的新鲜度由认证版本保证</b>：原先这里只写了"角色变更需要重新登录
 *       （可接受）"，但没有任何机制保证这一点，管理员被降权后，在 token 有效期内（最长 12h）仍是超级管理员。
 *       现在角色/区域变更会递增 epoch，旧令牌立即失效，
 *       claim 与库中状态不会再长期不一致。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_REAL_NAME = "realName";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_REGION_ID = "regionId";
    private static final String CLAIM_EMPLOYEE_NO = "employeeNo";
    private static final String CLAIM_DEPARTMENT = "department";
    private static final String CLAIM_SESSION_ID = "sid";
    /** 认证版本：与 Redis 里的 dorm:auth:epoch:{userId} 比对，落后即失效。 */
    private static final String CLAIM_AUTH_EPOCH = "epoch";

    private final DormSecurityProperties properties;

    /**
     * 签发令牌。
     *
     * @param authEpoch 当前认证版本（Redis 里的值；键不存在传 0）。
     *                  写在令牌里，之后每个请求都会比对，禁用/重置口令/角色变更
     *                  通过递增它让旧令牌立即失效。
     */
    public String createToken(SysUser user, String sessionId, long authEpoch) {
        Instant now = Instant.now();
        Instant expire = now.plusSeconds(properties.getJwt().getExpireMinutes() * 60);
        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(String.valueOf(user.getId()))
                .id(sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expire))
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_REAL_NAME, user.getRealName())
                .claim(CLAIM_ROLE, user.getRole() == null ? null : user.getRole().name())
                .claim(CLAIM_REGION_ID, user.getRegionId())
                .claim(CLAIM_EMPLOYEE_NO, user.getEmployeeNo())
                .claim(CLAIM_DEPARTMENT, user.getDepartment())
                .claim(CLAIM_SESSION_ID, sessionId)
                .claim(CLAIM_AUTH_EPOCH, authEpoch)
                .signWith(signingKey())
                .compact();
    }

    /** 解析并校验令牌；失败返回 null（由拦截器统一转 401）。 */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(properties.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return LoginUser.builder()
                    .userId(Long.valueOf(claims.getSubject()))
                    .username(claims.get(CLAIM_USERNAME, String.class))
                    .realName(claims.get(CLAIM_REAL_NAME, String.class))
                    .role(UserRole.of(claims.get(CLAIM_ROLE, String.class)))
                    .regionId(claims.get(CLAIM_REGION_ID) == null ? null
                            : ((Number) claims.get(CLAIM_REGION_ID)).longValue())
                    .employeeNo(claims.get(CLAIM_EMPLOYEE_NO, String.class))
                    .department(claims.get(CLAIM_DEPARTMENT, String.class))
                    .sessionId(claims.get(CLAIM_SESSION_ID, String.class))
                    // 旧版本（本次上线前）签发的令牌没有 epoch 声明 → 保持 null，
                    // 由 AuthService.verifySession 判为失效（不拆箱，因此不会 NPE）。
                    //
                    // 这是有意选择的 fail-closed：代价是"上线后所有已登录用户需要重新登录一次"，
                    // 收益是"上线前被禁用的账号、其旧令牌不可能因为缺少版本而被放行"。
                    // 该影响已写入 docs/defects-round35.md，避免被当成故障排查。
                    .authEpoch(claims.get(CLAIM_AUTH_EPOCH) == null ? null
                            : ((Number) claims.get(CLAIM_AUTH_EPOCH)).longValue())
                    .build();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }

    public long expireSeconds() {
        return properties.getJwt().getExpireMinutes() * 60;
    }

    public static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SecretKey signingKey() {
        byte[] key = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            // HS256 要求密钥至少 256 bit；这里显式失败而不是默默用弱密钥
            throw new IllegalStateException(
                    "JWT 密钥过短（至少 32 字节）。请设置环境变量 DORM_JWT_SECRET");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }
}
