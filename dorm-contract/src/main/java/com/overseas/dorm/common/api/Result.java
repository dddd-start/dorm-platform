package com.overseas.dorm.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应包装。
 *
 * <p>设计要点（面向 Agent 调用场景）：
 * <ul>
 *   <li>{@code traceId} 回填全链路追踪 ID，前端可凭此在审计日志中定位整轮请求；</li>
 *   <li>分页数据统一放在 {@link PageResult} 中，避免前端对两种结构做适配；</li>
 *   <li>错误信息区分 {@code code}（机器可判定）与 {@code message}（人可读），
 *       便于 Agent 工具判断「是权限问题还是参数问题」并给出不同的自然语言反馈。</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码，0 表示成功。 */
    private int code;

    /** 人可读消息。 */
    private String message;

    /** 业务数据。 */
    private T data;

    /** 全链路追踪 ID。 */
    private String traceId;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok(T data, String message) {
        Result<T> r = ok(data);
        r.message = message;
        return r;
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return fail(resultCode.getCode(), message);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
