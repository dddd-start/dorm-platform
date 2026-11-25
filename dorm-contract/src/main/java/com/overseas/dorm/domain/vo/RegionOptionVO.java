package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 区域下拉选项。
 *
 * <p>这是一个"固定形状"的返回值，因此用 record 而不是 {@code Map<String, Object>}：
 * <ul>
 *   <li>字段名、类型、必填性都由编译器保证，前端拿到的东西不会因为有人
 *       改了一个 {@code put} 的 key 而悄悄变了形状；</li>
 *   <li>IDE 与调用方能直接跳转、重构、补全；</li>
 *   <li>Swagger 能自动生成准确的 schema，{@code Map<String, Object>}
 *       在接口文档里只能显示成"一个对象"，等于没有文档。</li>
 * </ul>
 *
 * <p>注意：<b>动态键</b>的场景（例如"各状态数量分布"）仍然应该用 Map，
 * 那种地方用 record 反而要写一堆字段。区分标准是"键是否固定"。
 */
public record RegionOptionVO(
        Long id,
        String code,
        String name,
        String country,
        String currency
) implements Serializable {
}
