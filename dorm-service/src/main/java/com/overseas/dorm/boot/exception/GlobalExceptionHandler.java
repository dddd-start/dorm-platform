package com.overseas.dorm.boot.exception;

import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>核心原则：<b>把"给用户看的"和"给开发看的"分开</b>。
 * <ul>
 *   <li>业务异常（{@link BizException}）—— 消息是写给用户的，直接返回；</li>
 *   <li>系统异常 —— 返回统一兜底文案，真实堆栈只进日志，
 *       响应里只带 traceId。这样既不泄露内部结构（表名、SQL、类名），
 *       又让用户报障时可以凭 traceId 精确找回日志。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("业务异常 [{}] {} {} -> {}", e.getResultCode().name(),
                request.getMethod(), request.getRequestURI(), e.getMessage());
        return withTrace(Result.fail(e.getResultCode(), e.getMessage()));
    }

    /** @Valid 校验失败（请求体）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return withTrace(Result.fail(ResultCode.PARAM_INVALID, message));
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return withTrace(Result.fail(ResultCode.PARAM_INVALID, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        return withTrace(Result.fail(ResultCode.PARAM_INVALID, message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return withTrace(Result.fail(ResultCode.PARAM_MISSING, "缺少参数：" + e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return withTrace(Result.fail(ResultCode.PARAM_INVALID, "参数格式不正确：" + e.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return withTrace(Result.fail(ResultCode.PARAM_INVALID, "请求体格式不正确"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return withTrace(Result.fail(ResultCode.PARAM_INVALID, "不支持的请求方法：" + e.getMethod()));
    }

    /** 唯一索引冲突：这是并发幂等的正常表现，转成明确的业务码而不是 500。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一索引冲突: {}", e.getMessage());
        return withTrace(Result.fail(ResultCode.DUPLICATE_DATA, "数据已存在，请勿重复提交"));
    }

    /**
     * 资源/接口不存在。
     *
     * <h3>为什么这里要显式返回 HTTP 404（而其它接口统一是 HTTP 200）</h3>
     * 本项目的接口约定是「HTTP 状态码恒为 200，业务结果放在响应体的 code 里」——
     * 这样前端只需要判断业务码，不用同时处理"网络层错误"与"业务层错误"两套分支。
     *
     * <p>但<b>路径不存在</b>是唯一的例外，必须给出真实的 404：
     * <ul>
     *   <li>浏览器与 CDN 用状态码判断资源是否存在。返回 200 会让缺失的
     *       {@code /assets/xxx.js} 被当成"加载成功但内容不对"，
     *       报出与真实原因无关的 MIME 错误；</li>
     *   <li>监控与探针依赖状态码统计 404 率，全部 200 会让"路由配错、
     *       静态资源没构建出来"这类问题在监控上完全隐形；</li>
     *   <li>接口联调时，路径拼错应当立刻表现为 404，而不是一个需要读懂
     *       业务码才知道含义的 200 响应。</li>
     * </ul>
     * 响应体仍然保持统一的 {@link Result} 结构，因此前端处理逻辑不变。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return withTrace(Result.fail(ResultCode.NOT_FOUND, "接口或资源不存在：" + e.getResourcePath()));
    }

    /**
     * 客户端在流式输出过程中断开 —— <b>这不是服务端错误</b>。
     *
     * <p>SSE 长连接断开是常态（关页面、切网络、手机后台），
     * 此时再往已失效的响应里写数据会抛 {@code AsyncRequestNotUsableException}。
     * 改造前它落到兜底 handler，被记为 {@code ERROR 系统异常} 并返回 9999 —— 后果有两个：
     * <ul>
     *   <li><b>告警噪音</b>：千级并发下每次断线都产生一条系统异常日志，
     *       真正的故障被淹没，运维会逐渐忽略这个日志；</li>
     *   <li><b>错误归类</b>：把"用户关掉了页面"统计成"服务端故障"，
     *       故障率指标从此不可信。</li>
     * </ul>
     * 这里按 DEBUG 记录并且不返回响应体：响应已经提交/失效，
     * 再写任何内容都只会二次失败。
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleClientDisconnected(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        log.debug("客户端已断开，停止推送（非服务端错误）：{}", e.getMessage());
    }

    /**
     * 响应已提交后写失败：同样按"客户端/连接层"处理，不打 ERROR。
     *
     * <p>但**必须区分**：如果响应尚未提交，说明可能是真的序列化缺陷，
     * 仍然走兜底 handler 记 ERROR —— 不能让这类问题被"当成断连"掩盖掉。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotWritableException.class)
    public void handleNotWritable(
            org.springframework.http.converter.HttpMessageNotWritableException e,
            HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("响应已提交后写失败（通常是客户端断开）：{}", e.getMessage());
        } else {
            log.error("响应序列化失败（响应尚未提交，可能是真实缺陷）", e);
        }
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletRequest request) {
        String traceId = TraceContext.getOrCreate();
        log.error("系统异常 traceId={} {} {}", traceId, request.getMethod(), request.getRequestURI(), e);
        Result<Void> result = Result.fail(ResultCode.SYSTEM_ERROR);
        result.setTraceId(traceId);
        return result;
    }

    private Result<Void> withTrace(Result<Void> result) {
        result.setTraceId(TraceContext.getOrCreate());
        return result;
    }
}
