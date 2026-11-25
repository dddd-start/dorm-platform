package com.overseas.dorm.domain.vo;

import com.overseas.dorm.common.enums.UserRole;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录成功返回。
 *
 * <p>刻意不返回手机号、密码哈希、区域对象等冗余信息，* 登录响应是最容易被前端 localStorage 长期保存的数据，
 * 里面放什么，就等于把这些数据长期留在了浏览器里。
 */
@Data
@Builder
public class LoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String token;
    private String tokenType;
    private Long expiresInSeconds;

    private Long userId;
    private String username;
    private String realName;
    private UserRole role;
    private String roleLabel;
    private Long regionId;
    private String regionName;
    private String employeeNo;
    private String department;
    /** 会话 ID（JWT jti），登出/强制下线时使用。 */
    private String sessionId;
}
