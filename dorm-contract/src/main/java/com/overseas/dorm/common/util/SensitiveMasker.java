package com.overseas.dorm.common.util;

import java.util.regex.Pattern;

/**
 * 敏感信息脱敏工具。
 *
 * <p>这是「防止手机号、订单号等敏感信息进入模型」这条要求的<b>执行点</b>。
 * 系统里有两条互补的防线：
 * <ol>
 *   <li><b>存储层</b>：手机号等 PII 以 AES-GCM 密文入库，
 *       另存 HMAC 摘要供精确检索，列表展示只用掩码；</li>
 *   <li><b>模型层</b>：任何要拼进 Prompt 的文本（含数据库字段、知识库片段、
 *       用户输入）都先过一遍 {@link #maskForPrompt}，把可识别信息替换为
 *       占位符 {@code [PHONE_1]}，模型输出后再按需回填。</li>
 * </ol>
 * 为什么用占位符而不是直接删掉：直接删会让模型失去「这里有个联系方式」的语义，
 * 导致它回答「我没有看到联系方式」；而占位符既保护了真实值，又保留了结构信息。
 */
public final class SensitiveMasker {

    private static final char MASK = '*';

    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    private static final Pattern PHONE_INTL = Pattern.compile("(?<!\\d)(\\+?\\d{1,3}[- ]?)(\\d{2,4})[- ]?(\\d{3,4})[- ]?(\\d{3,4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,3})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})(\\d{8})(\\d{3})([0-9Xx])(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(\\d{4})\\d{8,11}(\\d{4})(?!\\d)");
    /** 申请单号 / 订单单号：保留前缀与后 4 位。 */
    private static final Pattern ORDER_NO = Pattern.compile("\\b([A-Z]{2})(\\d{8})(\\d{4})\\b");

    private SensitiveMasker() {
    }

    // ------------------------------------------------------------------ 展示用掩码

    /** 手机号展示掩码：138****8000。 */
    public static String maskPhone(String phone) {
        if (isBlank(phone)) {
            return phone;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() < 7) {
            return repeat(MASK, digits.length());
        }
        int keepHead = digits.startsWith("+") ? 4 : 3;
        int keepTail = 4;
        if (digits.length() <= keepHead + keepTail) {
            return digits.charAt(0) + repeat(MASK, digits.length() - 2) + digits.charAt(digits.length() - 1);
        }
        return digits.substring(0, keepHead) + repeat(MASK, digits.length() - keepHead - keepTail)
                + digits.substring(digits.length() - keepTail);
    }

    /**
     * 邮箱展示掩码：abc***@example.com。
     *
     * <p>有意<b>保留域名</b>：域名本身不是个人标识（同事之间共享同一个企业域名），
     * 保留它能让"这是谁的邮箱"更容易辨认，同时不泄露账号；
     * 反过来把整个邮箱全部打码，会让列表页完全无法区分不同的人。
     * 只有在输入连基本的邮箱结构都不成立时才整体打码。
     */
    public static String maskEmail(String email) {
        if (isBlank(email)) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return repeat(MASK, email.length());
        }
        String name = email.substring(0, at);
        String domain = email.substring(at);
        String head = name.length() <= 2 ? name.substring(0, 1) : name.substring(0, 2);
        return head + repeat(MASK, 3) + domain;
    }

    /** 姓名展示掩码：张* / 欧阳**。 */
    public static String maskName(String name) {
        if (isBlank(name)) {
            return name;
        }
        if (name.length() == 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + String.valueOf(MASK);
        }
        return name.charAt(0) + repeat(MASK, name.length() - 1);
    }

    /** 证件号展示掩码：110101********1234。 */
    public static String maskIdCard(String idCard) {
        if (isBlank(idCard) || idCard.length() < 8) {
            return isBlank(idCard) ? idCard : repeat(MASK, idCard.length());
        }
        return idCard.substring(0, 4) + repeat(MASK, idCard.length() - 8) + idCard.substring(idCard.length() - 4);
    }

    // ------------------------------------------------------------------ 进入模型前

    /**
     * 把文本中所有可识别的敏感信息替换为占位符，供拼装 Prompt 使用。
     *
     * @return 脱敏后的文本
     */
    public static String maskForPrompt(String text) {
        if (isBlank(text)) {
            return text;
        }
        String out = text;
        out = ID_CARD.matcher(out).replaceAll("[ID_CARD]");
        out = BANK_CARD.matcher(out).replaceAll("[BANK_CARD]");
        out = PHONE_CN.matcher(out).replaceAll("[PHONE]");
        out = EMAIL.matcher(out).replaceAll("[EMAIL]");
        out = ORDER_NO.matcher(out).replaceAll("[ORDER_NO]");
        // 国际号码放在最后，避免把已经替换掉的占位符再匹配一遍
        out = PHONE_INTL.matcher(out).replaceAll(mr -> {
            String candidate = mr.group();
            // 仅当看起来像电话号码（长度 >= 8 且包含分隔符或 +）时才替换
            String digits = candidate.replaceAll("[^0-9]", "");
            if (digits.length() >= 8 && (candidate.contains("+") || candidate.contains("-") || candidate.contains(" "))) {
                return "[PHONE]";
            }
            return candidate;
        });
        return out;
    }

    /** 检测文本中是否包含疑似敏感信息（用于审计与告警，不阻断业务）。 */
    public static boolean containsSensitive(String text) {
        if (isBlank(text)) {
            return false;
        }
        return PHONE_CN.matcher(text).find()
                || EMAIL.matcher(text).find()
                || ID_CARD.matcher(text).find();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String repeat(char c, int n) {
        if (n <= 0) {
            return "";
        }
        return String.valueOf(c).repeat(n);
    }
}
