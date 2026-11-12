package com.overseas.dorm.agent.security;

import com.overseas.dorm.common.enums.UserRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 功能权限声明（与原单体的 {@code com.overseas.dorm.boot.security.RequireRole} 语义一致）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    UserRole[] value() default {};

    boolean superAdminOnly() default false;

    String message() default "没有权限执行该操作";
}
