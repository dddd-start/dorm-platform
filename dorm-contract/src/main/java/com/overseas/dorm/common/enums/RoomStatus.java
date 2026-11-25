package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 房间状态。
 *
 * <p>有意用状态而非「删除」表达房间下线：
 * 历史申请/订单仍要能引用该房间，软删会让唯一索引与历史数据都变得难以处理。
 */
@Getter
public enum RoomStatus {

    OPEN("开放", "#67C23A"),
    CLOSED("已关闭", "#F56C6C"),
    MAINTENANCE("维修中", "#E6A23C");

    private final String label;
    private final String color;

    RoomStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    /** 状态字典（值 + 中文标签 + 展示色）。 */
    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values())
                .map(e -> com.overseas.dorm.common.api.OptionVO.of(e.name(), e.label, e.color))
                .toList();
    }
}
