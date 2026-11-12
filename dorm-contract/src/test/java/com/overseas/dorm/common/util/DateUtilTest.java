package com.overseas.dorm.common.util;

import com.overseas.dorm.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日期工具测试。
 *
 * <p>住宿晚数的算法是「退房日 - 入住日」，也就是 hotel 惯例
 * （入住日算、退房日不算）。这个口径全系统必须一致：
 * 房源检索的区间重叠判断、费用计算、失效时间都建立在它之上。
 * 口径错了会直接导致钱算错。
 */
class DateUtilTest {

    @Test
    @DisplayName("住宿晚数按 hotel 惯例：10 号入住 12 号退房 = 2 晚")
    void nightsBetween() {
        assertThat(DateUtil.nightsBetween(
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12))).isEqualTo(2);
        assertThat(DateUtil.nightsBetween(
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11))).isEqualTo(1);
        assertThat(DateUtil.nightsBetween(
                LocalDate.of(2026, 11, 10), LocalDate.of(2027, 1, 8))).isEqualTo(59);
    }

    @Test
    @DisplayName("跨月跨年计算正确（不能只做天数相减）")
    void crossMonthAndYear() {
        assertThat(DateUtil.nightsBetween(
                LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2))).isEqualTo(3);
        assertThat(DateUtil.nightsBetween(
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 2))).isEqualTo(3);
        // 闰年 2 月
        assertThat(DateUtil.nightsBetween(
                LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1))).isEqualTo(2);
    }

    @Test
    @DisplayName("退房日不晚于入住日 → 拒绝")
    void invalidRange() {
        assertThatThrownBy(() -> DateUtil.nightsBetween(
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("退房日期必须晚于入住日期");
        assertThatThrownBy(() -> DateUtil.nightsBetween(
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 9)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> DateUtil.nightsBetween(null, LocalDate.now()))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("超出单次入住上限 → 拒绝")
    void exceedMaxNights() {
        assertThatThrownBy(() -> DateUtil.nightsBetween(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能超过");
    }

    @Test
    @DisplayName("区间重叠判断：端点相等算重叠，相邻不算")
    void overlaps() {
        LocalDate d1 = LocalDate.of(2026, 11, 1);
        LocalDate d5 = LocalDate.of(2026, 11, 5);
        LocalDate d10 = LocalDate.of(2026, 11, 10);

        // 完全包含
        assertThat(DateUtil.overlaps(d1, d10, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4))).isTrue();
        // 部分交叠
        assertThat(DateUtil.overlaps(d1, d5, LocalDate.of(2026, 11, 4), d10)).isTrue();
        // 端点相接：[1,5) 与 [5,10) 不重叠
        assertThat(DateUtil.overlaps(d1, d5, d5, d10)).isFalse();
        // 完全不相干
        assertThat(DateUtil.overlaps(d1, d5, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5))).isFalse();
        // 相同区间
        assertThat(DateUtil.overlaps(d1, d5, d1, d5)).isTrue();
        // 空值安全
        assertThat(DateUtil.overlaps(null, d5, d1, d10)).isFalse();
    }

    @Test
    @DisplayName("失效时间 = 入住日次日 23:59:59")
    void expireTime() {
        assertThat(DateUtil.expireTimeOf(LocalDate.of(2026, 11, 10)))
                .isEqualTo(LocalDate.of(2026, 11, 11).atTime(23, 59, 59));
    }

    @Test
    @DisplayName("格式化与解析")
    void formatAndParse() {
        assertThat(DateUtil.format(LocalDate.of(2026, 11, 2))).isEqualTo("2026-11-02");
        assertThat(DateUtil.parseDate("2026-11-02")).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(DateUtil.parseDate(null)).isNull();
        assertThat(DateUtil.parseDate("")).isNull();
        assertThatThrownBy(() -> DateUtil.parseDate("2026/11/02"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}
