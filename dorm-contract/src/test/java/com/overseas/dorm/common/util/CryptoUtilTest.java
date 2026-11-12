package com.overseas.dorm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 敏感字段加解密测试。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>同一个明文两次加密得到<b>不同</b>的密文（IV 随机化）—— 否则等于明文；</li>
 *   <li>反过来都能解回原值（可用性）；</li>
 *   <li>HMAC 摘要<b>稳定</b>（同一明文同一密钥结果一致）—— 否则无法按手机号检索。</li>
 * </ol>
 */
class CryptoUtilTest {

    private static final String KEY = CryptoUtil.generateAesKey();
    private static final String SECRET = "unit-test-hmac-secret";

    @Test
    @DisplayName("AES-GCM 加解密往返正确")
    void encryptDecryptRoundTrip() {
        String plain = "13812348000";
        String cipher = CryptoUtil.encrypt(plain, KEY);
        assertThat(cipher).isNotNull().isNotEqualTo(plain);
        assertThat(CryptoUtil.decrypt(cipher, KEY)).isEqualTo(plain);
    }

    @Test
    @DisplayName("相同明文两次加密得到不同密文（IV 随机化，防止密文比对）")
    void samePlainDifferentCipher() {
        String plain = "13812348000";
        String c1 = CryptoUtil.encrypt(plain, KEY);
        String c2 = CryptoUtil.encrypt(plain, KEY);
        assertThat(c1).isNotEqualTo(c2);
        assertThat(CryptoUtil.decrypt(c1, KEY)).isEqualTo(plain);
        assertThat(CryptoUtil.decrypt(c2, KEY)).isEqualTo(plain);
    }

    @Test
    @DisplayName("用错误密钥解密返回 null 而不是抛异常（密钥轮换场景）")
    void wrongKeyReturnsNull() {
        String cipher = CryptoUtil.encrypt("13812348000", KEY);
        String otherKey = CryptoUtil.generateAesKey();
        assertThat(CryptoUtil.decrypt(cipher, otherKey)).isNull();
    }

    @Test
    @DisplayName("密文被篡改时解密失败返回 null（GCM 完整性校验）")
    void tamperedCipherReturnsNull() {
        String cipher = CryptoUtil.encrypt("13812348000", KEY);
        // 改掉最后一个字符，破坏 GCM 认证标签
        char last = cipher.charAt(cipher.length() - 1);
        String tampered = cipher.substring(0, cipher.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat(CryptoUtil.decrypt(tampered, KEY)).isNull();
    }

    @Test
    @DisplayName("空值与异常输入的边界处理")
    void edgeCases() {
        assertThat(CryptoUtil.encrypt(null, KEY)).isNull();
        assertThat(CryptoUtil.decrypt(null, KEY)).isNull();
        assertThat(CryptoUtil.decrypt("", KEY)).isNull();
        assertThat(CryptoUtil.decrypt("not-base64!!", KEY)).isNull();
        assertThat(CryptoUtil.hmacSha256(null, SECRET)).isNull();
        assertThatThrownBy(() -> CryptoUtil.encrypt("x", "dG9vc2hvcnQ="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("HMAC 摘要稳定且不可逆，可用于精确检索")
    void hmacIsStable() {
        String phone = "13812348000";
        String h1 = CryptoUtil.hmacSha256(phone, SECRET);
        String h2 = CryptoUtil.hmacSha256(phone, SECRET);
        assertThat(h1).isEqualTo(h2).hasSize(64);
        assertThat(h1).doesNotContain(phone);
        // 换密钥后摘要不同：拿到数据库也反推不出手机号
        assertThat(CryptoUtil.hmacSha256(phone, SECRET + "x")).isNotEqualTo(h1);
    }

    @Test
    @DisplayName("SHA-256 摘要用于向量化缓存键")
    void sha256IsStable() {
        String text = "入住申请流程";
        assertThat(CryptoUtil.sha256(text)).isEqualTo(CryptoUtil.sha256(text)).hasSize(64);
        assertThat(CryptoUtil.sha256(text)).isNotEqualTo(CryptoUtil.sha256(text + " "));
    }
}
