package com.overseas.dorm.common.api;

import java.io.Serializable;

/**
 * 通用"选项"结构（枚举字典、下拉列表）。
 *
 * <p>此前这类接口返回 {@code List<Map<String, String>>}（例如 {@code Map.of("value","1","label","启用")}），
 * 问题是：
 * <ul>
 *   <li>Swagger 只能显示成"对象数组"，前端无法生成类型；</li>
 *   <li>键名 {@code value}/{@code label} 靠约定维护，某处写成 {@code text} 也不会报错，
 *       前端只会渲染出一片空白；</li>
 *   <li>{@code value} 的类型在不同接口里不一致（有的给数字、有的给字符串），
 *       前端提交表单时容易出现"1"与 1 不相等导致的选中态丢失。</li>
 * </ul>
 *
 * <p>统一为 {@code OptionVO(String value, String label)}：值一律用字符串
 * （下拉框的值本来就是字符串，由使用方在提交时按需转换），避免数字/字符串混用。
 */
public record OptionVO(String value, String label, String color) implements Serializable {

    /**
     * @param color 展示色（可空）。状态类字典用它让前端渲染标签颜色 ——
     *              放在后端是为了避免"前端再维护一份状态→颜色映射"，那种映射必然漏项。
     */
    public OptionVO {
    }

    public static OptionVO of(String value, String label) {
        return new OptionVO(value, label, null);
    }

    public static OptionVO of(String value, String label, String color) {
        return new OptionVO(value, label, color);
    }

    /** 数字型取值（如启停状态 1/0）。值一律用字符串：下拉框的值本来就是字符串。 */
    public static OptionVO ofNumber(Number value, String label) {
        return new OptionVO(value == null ? null : String.valueOf(value.longValue()), label, null);
    }

    /** 枚举型取值：值用枚举名，标签用中文。 */
    public static OptionVO ofEnum(Enum<?> value, String label) {
        return new OptionVO(value == null ? null : value.name(), label, null);
    }
}
