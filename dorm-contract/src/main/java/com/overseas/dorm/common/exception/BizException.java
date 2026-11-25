package com.overseas.dorm.common.exception;

import com.overseas.dorm.common.api.ResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常。
 *
 * <p>约定：
 * <ul>
 *   <li>携带 {@link ResultCode}，让调用方（Controller / Agent 工具）能按码做机器判定；</li>
 *   <li>{@code agentHint} 是给 AI 的「自然语言纠偏提示」，*       工具抛异常时，这段提示会被回灌给模型，引导它换个说法或换条路径，
 *       而不是把堆栈信息暴露给用户。</li>
 * </ul>
 */
@Getter
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;

    /** 给模型的纠偏提示（可为空，空则使用 resultCode 的默认文案）。 */
    private final String agentHint;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
        this.agentHint = null;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
        this.agentHint = message;
    }

    public BizException(ResultCode resultCode, String message, String agentHint) {
        super(message);
        this.resultCode = resultCode;
        this.agentHint = agentHint;
    }

    public static BizException of(ResultCode code) {
        return new BizException(code);
    }

    public static BizException of(ResultCode code, String message) {
        return new BizException(code, message);
    }

    public static BizException param(String message) {
        return new BizException(ResultCode.PARAM_INVALID, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ResultCode.FORBIDDEN, message);
    }

    /** 供 Agent 工具使用：附带给模型的纠偏提示。 */
    public static BizException forAgent(ResultCode code, String message, String agentHint) {
        return new BizException(code, message, agentHint);
    }

    /** 回灌给模型的提示文本。 */
    public String toAgentHint() {
        return agentHint != null ? agentHint : getMessage();
    }
}
