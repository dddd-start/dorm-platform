package com.overseas.dorm.domain.support;

import com.overseas.dorm.common.util.CryptoUtil;
import com.overseas.dorm.common.util.SensitiveMasker;
import com.overseas.dorm.domain.config.DormSecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 手机号字段级加密服务。
 *
 * <p>这就是「如何防止手机号等敏感信息进入模型」在<b>存储与读取</b>两端的执行点：
 * <pre>
 *   写入：明文 -> AES-GCM 密文（phone_cipher） + 掩码（phone_mask） + HMAC（phone_hash）
 *   读取：列表/详情只返回掩码；只有「本人查看自己资料」才解密返回明文
 *   拼 Prompt：只允许用掩码，且再过一遍 SensitiveMasker
 * </pre>
 * 三列各司其职的好处是：即使业务代码写错了，
 * 也不可能因为「顺手 select *」就把明文手机号带进 Prompt，库里压根没有明文。
 */
@Component
@RequiredArgsConstructor
public class PhoneCipher {

    private final DormSecurityProperties properties;

    /** 加密后的三元组，供实体写入。 */
    public record CipherBundle(String cipher, String mask, String hash) {
    }

    public CipherBundle encrypt(String plainPhone) {
        if (plainPhone == null || plainPhone.isBlank()) {
            return new CipherBundle(null, null, null);
        }
        String normalized = plainPhone.replaceAll("[\\s-]", "");
        String cipher = CryptoUtil.encrypt(normalized, aesKey());
        String mask = SensitiveMasker.maskPhone(normalized);
        String hash = CryptoUtil.hmacSha256(normalized, hmacSecret());
        return new CipherBundle(cipher, mask, hash);
    }

    /** 解密（仅用于本人资料页）；解不开返回 null，不抛异常。 */
    public String decrypt(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        return CryptoUtil.decrypt(cipher, aesKey());
    }

    /** 按明文手机号生成检索摘要。 */
    public String hash(String plainPhone) {
        if (plainPhone == null || plainPhone.isBlank()) {
            return null;
        }
        return CryptoUtil.hmacSha256(plainPhone.replaceAll("[\\s-]", ""), hmacSecret());
    }

    private String aesKey() {
        String key = properties.getPhone().getAesKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "未配置 dorm.security.phone.aes-key（环境变量 DORM_PHONE_AES_KEY），拒绝以明文方式处理 PII");
        }
        return key;
    }

    private String hmacSecret() {
        String secret = properties.getPhone().getHmacSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 dorm.security.phone.hmac-secret（环境变量 DORM_PHONE_HMAC_SECRET）");
        }
        return secret;
    }
}
