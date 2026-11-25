package com.overseas.dorm.boot.security;

import com.overseas.dorm.common.enums.UserRole;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 功能权限声明（接口级）。
 *
 * <p>本系统有三层权限，各司其职，缺一不可：
 * <ol>
 *   <li><b>功能权限</b>（本注解），能不能进这个接口（角色白名单）；</li>
 *   <li><b>数据权限</b>（{@code DataScope}），进得来之后，能看到/改到哪些行；</li>
 *   <li><b>工具权限</b>（{@code ToolPermission}），AI 助手能不能替你调用某个能力。</li>
 * </ol>
 *
 * <p>为什么三层都要有：只做功能权限会出现「区域管理员能改别的区域房间」的越权；
 * 只做数据权限则会让员工有机会进入管理接口再去撞权限校验，攻击面变大。
 * 声明式注解放在 Controller 上，让「谁能调什么」一眼可见，便于代码评审。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色；为空表示只要登录即可。 */
    UserRole[] value() default {};

    /** 是否同时要求总管理员（比写在 value 里更直观的快捷方式）。 */
    boolean superAdminOnly() default false;

    /** 拒绝时的提示。 */
    String message() default "当前角色无权执行该操作";
}
