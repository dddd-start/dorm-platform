package com.overseas.dorm.common.security;

import com.overseas.dorm.common.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 当前登录用户（认证主体）。
 *
 * <p><b>安全要点</b>：这个对象是「用户身份」进入业务层与 Agent 工具层的
 * <b>唯一通道</b>。它由 JWT 解析产生，存放在 {@link UserContext} 中：
 * <ul>
 *   <li>Controller / Service 通过 {@code UserContext.require()} 获取；</li>
 *   <li>Agent 工具的 {@code userId} 一律从此上下文取，
 *       <b>绝不</b>接受模型或请求体传入的用户标识，这是防越权的根防线；</li>
 *   <li>异步/流式线程通过显式传递 {@code LoginUser} 快照来继承身份，
 *       而不是依赖 ThreadLocal 自动传播（后者在流式回调里并不可靠）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String realName;
    private UserRole role;

    /** 数据权限范围：区域管理员与员工的所属区域；总管理员为 null 表示全区域。 */
    private Long regionId;

    private String employeeNo;
    private String department;

    /** 会话 ID（JWT 的 jti），用于「强制下线」与审计串联。 */
    private String sessionId;

    /**
     * 签发令牌时的认证版本（{@code dorm:auth:epoch:{userId}}；键不存在时为 0）。
     *
     * <p>拦截器每个请求都会把它与 Redis 里的当前值比对：<b>不相等即视为令牌已失效</b>。
     * 这样"禁用账号 / 重置口令 / 角色或区域变更 / 强制下线"都能对已签发的令牌立即生效，
     * 而不是等到 token 自然过期（最长 12 小时）。
     */
    private Long authEpoch;

    public boolean isSuperAdmin() {
        return role != null && role.isSuperAdmin();
    }

    public boolean isAdmin() {
        return role != null && role.isAdmin();
    }

    public boolean isEmployee() {
        return role != null && role.isEmployee();
    }

}
