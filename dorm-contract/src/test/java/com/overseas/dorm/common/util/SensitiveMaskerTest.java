package com.overseas.dorm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感信息脱敏测试。
 *
 * <p>这是「防止手机号等敏感信息进入模型」这条要求在代码层面的验证点。
 * 测试用例刻意覆盖了各种真实写法：带国际区号、带分隔符、
 * 中文姓名长短、身份证、订单号，以及「不能误伤」的反例
 * （例如普通数字不应被当成手机号）。
 */
class SensitiveMaskerTest {

    @Test
    @DisplayName("手机号掩码：保留号段与后四位")
    void maskPhone() {
        assertThat(SensitiveMasker.maskPhone("13812348000")).isEqualTo("138****8000");
        assertThat(SensitiveMasker.maskPhone("+6591234567")).isEqualTo("+659***4567");
        assertThat(SensitiveMasker.maskPhone("123")).isEqualTo("***");
        assertThat(SensitiveMasker.maskPhone(null)).isNull();
        assertThat(SensitiveMasker.maskPhone("")).isEmpty();
    }

    @Test
    @DisplayName("邮箱掩码：保留前两位与域名（域名非个人标识，保留它列表页才可辨识）")
    void maskEmail() {
        assertThat(SensitiveMasker.maskEmail("zhangwei@example-corp.com"))
                .isEqualTo("zh***@example-corp.com");
        assertThat(SensitiveMasker.maskEmail("a@b.com")).isEqualTo("a***@b.com");
        // 结构不成立时整体打码
        assertThat(SensitiveMasker.maskEmail("not-an-email")).isEqualTo("************");
        assertThat(SensitiveMasker.maskEmail(null)).isNull();
    }

    @Test
    @DisplayName("姓名掩码：单字保留，双字掩一个，多字只留姓")
    void maskName() {
        assertThat(SensitiveMasker.maskName("张")).isEqualTo("张");
        assertThat(SensitiveMasker.maskName("张伟")).isEqualTo("张*");
        assertThat(SensitiveMasker.maskName("欧阳锋")).isEqualTo("欧**");
        assertThat(SensitiveMasker.maskName("欧阳锋锐")).isEqualTo("欧***");
    }

    @Test
    @DisplayName("身份证掩码")
    void maskIdCard() {
        assertThat(SensitiveMasker.maskIdCard("110101199001011234"))
                .isEqualTo("1101**********1234");
    }

    @Test
    @DisplayName("Prompt 脱敏：手机号/邮箱/身份证/订单号都替换为占位符")
    void maskForPrompt() {
        String input = "我的手机号是 13812348000，邮箱 zhangwei@corp.com，"
                + "身份证 110101199001011234，申请单 DA202610140001。";
        String masked = SensitiveMasker.maskForPrompt(input);

        assertThat(masked).doesNotContain("13812348000");
        assertThat(masked).doesNotContain("zhangwei@corp.com");
        assertThat(masked).doesNotContain("110101199001011234");
        assertThat(masked).doesNotContain("DA202610140001");
        assertThat(masked).contains("[PHONE]");
        assertThat(masked).contains("[EMAIL]");
        assertThat(masked).contains("[ID_CARD]");
        assertThat(masked).contains("[ORDER_NO]");
        // 占位符保留了结构，模型仍能理解"这里有个联系方式"
        assertThat(masked).contains("我的手机号是");
    }

    @Test
    @DisplayName("Prompt 脱敏不误伤普通文本与数字")
    void maskForPromptShouldNotOverMask() {
        String input = "房间号 301，可住 2 人，每晚 120 SGD，共 3 晚。";
        String masked = SensitiveMasker.maskForPrompt(input);
        assertThat(masked).isEqualTo(input);
    }

    @Test
    @DisplayName("Prompt 脱敏不误伤日期与时间")
    void maskForPromptShouldKeepDates() {
        String input = "入住日期 2026-11-02，退房日期 2026-11-05，单号 DA202610140001";
        String masked = SensitiveMasker.maskForPrompt(input);
        assertThat(masked).contains("2026-11-02");
        assertThat(masked).contains("2026-11-05");
        assertThat(masked).contains("[ORDER_NO]");
    }

    @Test
    @DisplayName("敏感信息存在性检测")
    void containsSensitive() {
        assertThat(SensitiveMasker.containsSensitive("电话 13812348000")).isTrue();
        assertThat(SensitiveMasker.containsSensitive("a@b.com")).isTrue();
        assertThat(SensitiveMasker.containsSensitive("房间 301 每晚 120")).isFalse();
        assertThat(SensitiveMasker.containsSensitive(null)).isFalse();
    }
}
