package com.overseas.dorm.common.api;

import lombok.Getter;

/**
 * 业务状态码。
 *
 * <p>码段规划（便于前端与 Agent 工具做机器判定）：
 * <pre>
 *      0        成功
 *   1000~1999   通用参数/校验错误
 *   2000~2999   认证与权限
 *   3000~3999   业务规则（房源、申请、订单状态机）
 *   4000~4999   并发与幂等
 *   5000~5999   外部依赖（AI 模型、Redis、DB）
 *   6000~6999   Agent 专属（限流、预算、护栏、注入拦截）
 * </pre>
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "操作成功"),

    // ---------------- 通用 ----------------
    PARAM_INVALID(1000, "参数不合法"),
    PARAM_MISSING(1001, "缺少必填参数"),
    DATE_RANGE_INVALID(1002, "日期区间不合法"),
    ILLEGAL_STATE(1003, "当前状态不允许该操作"),
    NOT_FOUND(1004, "数据不存在"),
    DUPLICATE_DATA(1005, "数据已存在"),

    // ---------------- 认证与权限 ----------------
    UNAUTHENTICATED(2000, "未登录或登录已过期"),
    BAD_CREDENTIALS(2001, "用户名或密码错误"),
    ACCOUNT_LOCKED(2002, "账号已被锁定，请稍后再试"),
    ACCOUNT_DISABLED(2003, "账号已被禁用"),
    FORBIDDEN(2004, "无权访问该资源"),
    DATA_SCOPE_DENIED(2005, "超出你的数据权限范围"),
    TOOL_PERMISSION_DENIED(2006, "你没有调用该能力的权限"),

    // ---------------- 业务规则 ----------------
    REGION_NOT_FOUND(3000, "区域不存在"),
    BUILDING_NOT_FOUND(3001, "公寓不存在"),
    ROOM_NOT_FOUND(3002, "房间不存在"),
    ROOM_CLOSED(3003, "房间已关闭，暂不可入住"),
    ROOM_FULL(3004, "该房间床位已满"),
    BED_OCCUPIED(3005, "床位已被占用"),
    ROOM_NOT_MATCH_GENDER(3006, "房型与性别要求不匹配"),
    APPLY_NOT_FOUND(3007, "入住申请不存在"),
    APPLY_STATE_ILLEGAL(3008, "申请当前状态不允许该操作"),
    APPLY_DATE_OVERLAP(3009, "你在这段时间已有有效申请，请勿重复提交"),
    APPROVE_ONLY_REGION(3010, "只能审批你负责区域的申请"),
    CHECK_IN_TOO_EARLY(3011, "尚未到可办理入住的日期"),
    CHECK_IN_TOO_LATE(3012, "已超过最晚入住日期，申请已自动失效"),
    CHECK_OUT_NOT_DUE(3013, "尚未办理入住，无法退房"),
    ORDER_NOT_FOUND(3014, "入住订单不存在"),
    ORDER_STATE_ILLEGAL(3015, "订单当前状态不允许该操作"),
    DATE_RANGE_EXCEED_LIMIT(3016, "单次入住天数超出上限"),

    // ---------------- 并发与幂等 ----------------
    CONCURRENT_CONFLICT(4000, "数据已被他人修改，请刷新后重试"),
    LOCK_ACQUIRE_FAILED(4001, "系统繁忙，请稍后重试"),
    IDEMPOTENT_REPLAY(4002, "重复请求，已返回首次处理结果"),

    // ---------------- 外部依赖 ----------------
    DB_ERROR(5000, "数据库操作失败"),
    CACHE_ERROR(5001, "缓存操作失败"),
    AI_MODEL_UNAVAILABLE(5002, "AI 模型服务暂不可用，已为你切换备用模型"),
    AI_TIMEOUT(5003, "AI 响应超时，请重试或换一种问法"),
    AI_TOOL_ERROR(5004, "工具调用失败"),

    // ---------------- Agent 专属 ----------------
    AGENT_DISABLED(6000, "智能助手当前未启用"),
    AGENT_RATE_LIMITED(6001, "提问过于频繁，请稍后再试"),
    AGENT_CONCURRENCY_LIMIT(6002, "你已有进行中的对话，请先等待或停止它"),
    AGENT_BUDGET_EXCEEDED(6003, "今日智能助手额度已用完，可继续使用常规功能"),
    AGENT_INPUT_BLOCKED(6004, "输入内容未通过安全审核"),
    AGENT_OUTPUT_BLOCKED(6005, "输出内容未通过安全审核，已中断"),
    AGENT_TURN_NOT_FOUND(6006, "对话轮次不存在或已过期"),
    AGENT_NEED_HUMAN(6007, "该操作需要人工确认"),
    AGENT_TASK_QUEUED(6008, "任务已提交后台执行"),
    AGENT_INJECTION_DETECTED(6009, "检测到疑似提示词注入，已拒绝执行"),

    // ---------------- 兜底 ----------------
    SYSTEM_ERROR(9999, "系统开小差了，请稍后再试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 按数字码反查枚举，供跨服务调用方还原业务错误类型。
     * 未知码统一归为 {@link #SYSTEM_ERROR}，避免调用方因新增码而崩溃。
     */
    public static ResultCode fromCode(int code) {
        for (ResultCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return SYSTEM_ERROR;
    }
}
