package com.overseas.dorm.common.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 敏感字段加解密工具（AES-256-GCM + HMAC-SHA256）。
 *
 * <p>为什么用 GCM 而不是 CBC：GCM 自带完整性校验，密文被篡改会解密失败，
 * 而不是悄悄解出一段乱码被当成真实手机号写回去。
 *
 * <p>为什么还要一个 HMAC 摘要：密文是随机 IV 的，同一个手机号每次加密结果都不同，
 * 于是「按手机号精确查询」无法直接做。方案是额外存一列 HMAC(手机号)，
 * 查询时对入参做同样的 HMAC 再比对 —— 既支持精确检索，又不落明文，
 * 且因为加了独立密钥，拿到数据库也反推不出手机号。
 *
 * <p>密文格式：{@code Base64(IV(12) || CipherText || Tag(16))}
 */
public final class CryptoUtil {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    public static String encrypt(String plainText, String base64Key) {
        if (plainText == null) {
            return null;
        }
        // 密钥校验放在 try 之外：密钥配置错误属于「部署配置问题」，
        // 应该以 IllegalArgumentException 快速失败并明确指出原因，
        // 而不是被包装成 IllegalStateException("加密失败") 让人误以为数据有问题。
        SecretKeySpec key = aesKey(base64Key);
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /** 解密失败返回 null（例如历史脏数据 / 密钥轮换），由调用方决定如何降级展示。 */
    public static String decrypt(String base64Cipher, String base64Key) {
        if (base64Cipher == null || base64Cipher.isBlank()) {
            return null;
        }
        SecretKeySpec key = aesKey(base64Key);
        try {
            byte[] raw = Base64.getDecoder().decode(base64Cipher);
            if (raw.length <= IV_LENGTH) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 计算 HMAC-SHA256 十六进制摘要，用于密文检索。 */
    public static String hmacSha256(String value, String secret) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 生成一个合法的 32 字节（AES-256）Base64 密钥，供部署脚本初始化使用。 */
    public static String generateAesKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public static String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static SecretKeySpec aesKey(String base64Key) {
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException("AES 密钥长度必须是 16/24/32 字节");
        }
        return new SecretKeySpec(key, "AES");
    }
}
