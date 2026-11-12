package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 房间性别限制 / 员工性别。
 *
 * <p>注意：员工性别本身也属于相对敏感的信息，系统中仅在「分房校验」这一个
 * 必要场景使用，不进入任何 Prompt（见 `SensitiveDataMasker`）。
 */
@Getter
public enum GenderLimit {

    ANY("不限"),
    MALE("男"),
    FEMALE("女");

    private final String label;

    GenderLimit(String label) {
        this.label = label;
    }

    /** 字典（值 + 中文标签）。 */
    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values()).map(e -> com.overseas.dorm.common.api.OptionVO.ofEnum(e, e.label)).toList();
    }
}
