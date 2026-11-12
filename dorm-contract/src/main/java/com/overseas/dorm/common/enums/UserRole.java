package com.overseas.dorm.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户角色。
 *
 * <p>三级权限模型的最小实现：
 * <ul>
 *   <li>{@link #SUPER_ADMIN} —— 总管理员：全部区域、全部房源、全局配置与审计</li>
 *   <li>{@link #REGION_ADMIN} —— 区域管理员：仅本区域的审批、分房、房源维护</li>
 *   <li>{@link #EMPLOYEE} —— 员工：提交/查看自己的申请、查房源、办理入住退房</li>
 * </ul>
 * 角色决定「功能权限」，区域决定「数据权限」，两者在
 * {@code com.overseas.dorm.domain.support.DataScope} 中组合校验。
 */
@Getter
public enum UserRole {

    SUPER_ADMIN("总管理员"),
    REGION_ADMIN("区域管理员"),
    EMPLOYEE("员工");

    private final String label;

    UserRole(String label) {
        this.label = label;
    }

    /** 是否具备管理员能力（区域管理员 + 总管理员）。 */
    public boolean isAdmin() {
        return this == SUPER_ADMIN || this == REGION_ADMIN;
    }

    public boolean isSuperAdmin() {
        return this == SUPER_ADMIN;
    }

    public boolean isEmployee() {
        return this == EMPLOYEE;
    }

    public static UserRole of(String name) {
        if (name == null) {
            return null;
        }
        for (UserRole r : values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }

    public static List<com.overseas.dorm.common.api.OptionVO> options() {
        return Arrays.stream(values())
                .map(e -> com.overseas.dorm.common.api.OptionVO.ofEnum(e, e.label))
                .toList();
    }

}
