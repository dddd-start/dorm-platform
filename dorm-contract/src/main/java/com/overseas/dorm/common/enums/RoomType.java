package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 房型。容量（床位数）由房型决定，见 {@link #capacity}。
 */
@Getter
public enum RoomType {

    SINGLE("单人间", 1),
    DOUBLE("双人间", 2),
    TRIPLE("三人间", 3),
    QUAD("四人间", 4);

    private final String label;
    private final int capacity;

    RoomType(String label, int capacity) {
        this.label = label;
        this.capacity = capacity;
    }

    /**
     * 房型字典。
     *
     * <p>比其它字典多一个 {@code capacity}（可住人数），这是前端排房与展示必须的信息。
     * 单独定义记录而不是复用 {@link com.overseas.dorm.common.api.OptionVO}：多一个字段就用独立类型，
     * 免得为了"统一"而把 capacity 塞进通用字典的某个 Map 里。
     */
    public record RoomTypeOption(String value, String label, int capacity) {
    }

    public static List<RoomTypeOption> options() {
        return Arrays.stream(values())
                .map(e -> new RoomTypeOption(e.name(), e.label, e.capacity))
                .toList();
    }
}
