package com.overseas.dorm.domain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全相关配置（JWT 与 PII 字段级加密）。
 *
 * <p>密钥全部来自环境变量，且<b>绝不</b>写入代码或版本库：
 * 一旦密钥进了 git，加密就退化成了「编码」，只要拿到库就能还原手机号。
 * 生产环境的正确姿势是接入 KMS / Vault，并支持密钥轮换
 * （{@code decrypt} 对解不开的历史密文返回 null 而不是抛异常，就是为轮换留的余地）。
 */
@Data
@ConfigurationProperties(prefix = "dorm.security")
public class DormSecurityProperties {

    private Jwt jwt = new Jwt();
    private Phone phone = new Phone();

    /** 连续登录失败多少次后锁定账号。 */
    private int loginMaxFail = 5;

    /** 锁定持续分钟数。 */
    private int loginLockMinutes = 15;

    @Data
    public static class Jwt {
        private String issuer = "overseas-dorm";
        private String secret;
        private long expireMinutes = 720;
        private long refreshExpireMinutes = 10080;
    }

    @Data
    public static class Phone {
        /** AES-256-GCM 密钥（Base64，32 字节）。 */
        private String aesKey;
        /** HMAC-SHA256 检索密钥（Base64）。 */
        private String hmacSecret;
    }
}
