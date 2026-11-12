package com.overseas.dorm.common.util;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.exception.BizException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 日期工具。
 *
 * <p>海外宿舍场景的日期有两个容易出错的地方，集中在这里处理：
 * <ol>
 *   <li><b>住宿天数</b>：按「入住日算、退房日不算」计算（hotel 惯例），
 *       即 10 号入住、12 号退房 = 2 晚，而不是 3 天；</li>
 *   <li><b>跨时区</b>：员工在 A 国提交申请、宿舍在 B 国，
 *       系统统一以服务器时区（Asia/Shanghai）记录，区域时区只用于展示，
 *       避免出现「同一天在两地把日期算成不同结果」。</li>
 * </ol>
 */
public final class DateUtil {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 单次入住天数上限（超出需要走特批流程）。 */
    public static final int MAX_NIGHTS = 90;

    private DateUtil() {
    }

    public static LocalDate today() {
        return LocalDate.now(DEFAULT_ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }

    /**
     * 计算住宿晚数：退房日 - 入住日。必须为正数。
     */
    public static int nightsBetween(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw BizException.of(ResultCode.DATE_RANGE_INVALID, "入住日期与退房日期不能为空");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw BizException.of(ResultCode.DATE_RANGE_INVALID, "退房日期必须晚于入住日期");
        }
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > MAX_NIGHTS) {
            throw BizException.of(ResultCode.DATE_RANGE_EXCEED_LIMIT,
                    "单次入住天数不能超过 %d 晚".formatted(MAX_NIGHTS));
        }
        return (int) nights;
    }

    /** 申请失效时间 = 入住日次日 23:59:59（给员工留出一天的宽限）。 */
    public static LocalDateTime expireTimeOf(LocalDate checkInDate) {
        return checkInDate.plusDays(1).atTime(23, 59, 59);
    }

    public static String format(LocalDate date) {
        return date == null ? null : date.format(DATE_FMT);
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATETIME_FMT);
    }

    public static LocalDate parseDate(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text, DATE_FMT);
    }

    /** 两个日期区间是否重叠（端点相等视为重叠）。 */
    public static boolean overlaps(LocalDate start1, LocalDate end1, LocalDate start2, LocalDate end2) {
        if (start1 == null || end1 == null || start2 == null || end2 == null) {
            return false;
        }
        return start1.isBefore(end2) && start2.isBefore(end1);
    }

}
