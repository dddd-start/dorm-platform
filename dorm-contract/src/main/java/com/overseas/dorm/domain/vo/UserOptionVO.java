package com.overseas.dorm.domain.vo;

import java.io.Serializable;

/**
 * 用户下拉选项（用于管理员指派负责人、选择员工）。
 *
 * <p>有意不包含手机号与邮箱：下拉选项只需要"能区分出是谁"的信息，
 * 把 PII 塞进一个会在页面上重复渲染几十次的接口，是数据最小化原则下最不该做的事。
 */
public record UserOptionVO(
        Long id,
        String username,
        String realName,
        String employeeNo,
        String role,
        String roleLabel
) implements Serializable {
}
