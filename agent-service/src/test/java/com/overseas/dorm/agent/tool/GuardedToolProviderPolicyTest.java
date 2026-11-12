package com.overseas.dorm.agent.tool;

import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.audit.AgentTurnContext;
import com.overseas.dorm.agent.stream.AgentStreamBuffer;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具层的横切策略：<b>哪一条真的生效了，哪一条没有</b>。
 *
 * <h3>本章最重要的产出：一个"没生效"的结论，以及它的根因</h3>
 * {@link GuardedToolProvider} 的类注释声明了四件事：同轮去重、有限重试、熔断、异常转提示。
 * 写测试之前，我以为四条都能验；写完之后发现<b>只有第一条是活的</b>。
 *
 * <p>根因不在守卫层，而在它委托的执行器：守卫层用
 * {@code new DefaultToolExecutor(toolObject, method)} 构造执行器，
 * 而这个构造器把 {@code propagateToolExecutionExceptions} 留在了默认值
 * {@code false} —— 于是 LangChain4j 会把工具抛出的异常<b>吞掉，
 * 把异常消息当成"工具结果"返回</b>。守卫层拿到的是一次"成功"的返回值，
 * 它的 catch 分支自然永远不执行。
 *
 * <p>本类因此是一组<b>特征化测试</b>（characterization test）：它断言的是
 * <b>当前的真实行为</b>，包括那三条不生效的策略。这样做的价值是：
 * 将来有人修好它（把 {@code propagateToolExecutionExceptions(true)} 打开），
 * 这些断言会红，修改者被迫回来读这段注释、更新本章与文档 ——
 * 而不是让一处安全相关的行为变化悄悄溜过。
 *
 * <p>照项目 constitution C3（不达标项必须留在文档里，不得静默删除），
 * 这个缺口记在：本章 README「常见坑」、`docs/milestone-04-*.md`、`PROGRESS.md`。
 *
 * <p><b>本教程没有修它</b>，因为练习约定是"只做纯增益的改动"（新增文件/新增测试），
 * 不改动生产行为。修复只需要一行，见 {@link #propagateFlagMakesExceptionVisible()}。
 */
class GuardedToolProviderPolicyTest {

    private static final Long CONVERSATION_ID = 5150L;
    private static final Long MESSAGE_ID = 7001L;
    private static final String ARGUMENTS = "{\"reason\":\"同参数重复调用\"}";

    /** 类名里含 "connect" → {@code isTransient} 会把它判成瞬时故障（本意是触发只读重试）。 */
    static class ConnectFailure extends RuntimeException {
        ConnectFailure(String message) {
            super(message);
        }
    }

    /** 只读假工具：方法名借用 ToolCatalog 里真实登记的只读工具名，以便命中真实分类。 */
    static class FakeReadOnlyTool {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static final AtomicInteger FAIL_TIMES = new AtomicInteger();

        @Tool("查询可选区域（只读）")
        public String listRegions(String city) {
            EXECUTIONS.incrementAndGet();
            if (FAIL_TIMES.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new ConnectFailure("模拟数据库连接失败");
            }
            return "{\"code\":0,\"data\":[\"SEA\"]}";
        }
    }

    /** 写假工具：方法名同上，{@code createStayApply} 在 ToolCatalog 里是写工具。 */
    static class FakeWriteTool {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static RuntimeException failure;

        @Tool("提交入住申请（写操作）")
        public String createStayApply(String reason) {
            EXECUTIONS.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return "{\"code\":0,\"data\":{\"applyNo\":\"A-1001\"}}";
        }
    }

    private AgentAuditService auditService;
    private GuardedToolProvider provider;
    private Map<String, String> dedupCache;

    @BeforeEach
    void setUp() {
        FakeReadOnlyTool.EXECUTIONS.set(0);
        FakeReadOnlyTool.FAIL_TIMES.set(0);
        FakeWriteTool.EXECUTIONS.set(0);
        FakeWriteTool.failure = null;

        // 用真的 Map 冒充去重缓存：让"写入 → 读回"的往返是真的，
        // 而不是我预先规定 lookup 返回什么（后者会把被测逻辑测成空转）。
        dedupCache = new HashMap<>();
        auditService = mock(AgentAuditService.class);
        when(auditService.lookupToolResult(anyString(), anyString()))
                .thenAnswer(invocation -> dedupCache.get(
                        invocation.getArgument(0) + "|" + invocation.getArgument(1)));
        doAnswer(invocation -> {
            dedupCache.put(invocation.getArgument(0) + "|" + invocation.getArgument(1),
                    invocation.getArgument(2));
            return null;
        }).when(auditService).cacheToolResult(anyString(), anyString(), anyString(), anyLong());

        AgentStreamBuffer streamBuffer = mock(AgentStreamBuffer.class);
        when(streamBuffer.isCancelled(anyLong())).thenReturn(false);

        provider = new GuardedToolProvider(
                List.of(new FakeReadOnlyTool(), new FakeWriteTool()), auditService, streamBuffer);
    }

    @AfterEach
    void tearDown() {
        AgentTurnContext.clear();
    }

    private ToolExecutor executor(String toolName) {
        ToolProviderResult result = provider.provideTools(null);
        ToolExecutor executor = result.toolExecutorByName(toolName);
        assertThat(executor).as("工具 %s 应被注册进工具集", toolName).isNotNull();
        return executor;
    }

    private String call(String toolName) {
        LoginUser user = LoginUser.builder()
                .userId(7L).username("tester").role(UserRole.EMPLOYEE).build();
        AgentTurnContext.begin(CONVERSATION_ID, MESSAGE_ID, "turn-policy-test", user, "trace-policy-test");
        return executor(toolName).execute(
                ToolExecutionRequest.builder().name(toolName).arguments(ARGUMENTS).build(),
                CONVERSATION_ID);
    }

    // ==================================================================
    // 一、生效的那一条：同轮去重（只给只读工具）
    // ==================================================================

    @Test
    @DisplayName("生效：只读工具同轮同参数重复调用被去重，底层只执行一次")
    void readOnlyToolIsDeduplicatedWithinTheTurn() {
        String first = call("listRegions");
        String second = call("listRegions");

        assertThat(FakeReadOnlyTool.EXECUTIONS.get())
                .as("只读工具第二次同参数调用应命中缓存，不再打数据库")
                .isEqualTo(1);
        assertThat(second)
                .as("去重后返回的必须是第一次的结果本身，而不是一个空壳")
                .isEqualTo(first)
                .contains("SEA");
    }

    @Test
    @DisplayName("生效：写工具同轮同参数绝不去重（用户可能是真心想再提交一张）")
    void writeToolIsNeverDeduplicated() {
        call("createStayApply");
        call("createStayApply");

        assertThat(FakeWriteTool.EXECUTIONS.get())
                .as("写工具去重会造成'用户第二次提交被静默吞掉、还返回上一次的单号'——"
                        + "这比报错难查得多，所以宁可不优化")
                .isEqualTo(2);
    }

    // ==================================================================
    // 二、根因：默认执行器把异常吞成了返回值
    // ==================================================================

    @Test
    @DisplayName("根因：默认构造的执行器不抛异常，而是把异常消息当成工具结果返回")
    void defaultExecutorSwallowsToolException() {
        Method method = method("createStayApply");
        FakeWriteTool.failure = new ConnectFailure("模拟连接中断");

        ToolExecutor delegate = new DefaultToolExecutor(new FakeWriteTool(), method);
        String output = delegate.execute(request("createStayApply"), CONVERSATION_ID);

        assertThat(output)
                .as("这就是根因：守卫层期待的是'抛出来'，拿到手的却是'返回值'，"
                        + "于是它的 catch 分支永远不执行")
                .contains("模拟连接中断");
        assertThat(FakeWriteTool.EXECUTIONS.get()).as("工具确实被执行了一次").isEqualTo(1);
    }

    @Test
    @DisplayName("修复路径：把 propagateToolExecutionExceptions 打开，异常才会浮出水面")
    void propagateFlagMakesExceptionVisible() {
        Method method = method("createStayApply");
        FakeWriteTool.failure = new ConnectFailure("模拟连接中断");

        ToolExecutor delegate = new DefaultToolExecutor.Builder()
                .object(new FakeWriteTool())
                .originalMethod(method)
                // 两个方法字段都要给：originalMethod 用于生成 Schema 与报错信息，
                // methodToInvoke 才是真正被反射调用的那一个（只给前者会在 build 时报 null）
                .methodToInvoke(method)
                .propagateToolExecutionExceptions(true)
                .build();

        assertThatThrownBy(() -> delegate.execute(request("createStayApply"), CONVERSATION_ID))
                .as("同一个工具、同一个异常，只改了执行器的构造方式 —— "
                        + "异常就能被守卫层看见，重试/熔断/提示三条策略才有机会生效")
                .isInstanceOf(RuntimeException.class);
    }

    // ==================================================================
    // 三、后果：三条策略不可达，而且失败被记成了成功
    // ==================================================================

    @Test
    @DisplayName("后果一：只读工具遇到瞬时故障不会重试")
    void readOnlyRetryBranchIsUnreachable() {
        FakeReadOnlyTool.FAIL_TIMES.set(1);

        String result = call("listRegions");

        assertThat(FakeReadOnlyTool.EXECUTIONS.get())
                .as("重试分支不可达：本该'失败一次再重试一次'变成只执行一次")
                .isEqualTo(1);
        assertThat(result)
                .as("用户拿到的是异常消息本身，而不是重试后的正常结果")
                .contains("模拟数据库连接失败");
    }

    @Test
    @DisplayName("后果二：瞬时故障会被写进去重缓存，接下来 10 分钟同参数查询都直接返回这条错误")
    void transientFailurePoisonsTheDedupCache() {
        FakeReadOnlyTool.FAIL_TIMES.set(1);
        call("listRegions");

        // 故障已经过去（FAIL_TIMES 归零），但缓存里躺着的是错误文本
        String second = call("listRegions");

        assertThat(FakeReadOnlyTool.EXECUTIONS.get())
                .as("第二次连试都没试 —— 命中缓存直接返回")
                .isEqualTo(1);
        assertThat(second)
                .as("这条最难受：一次瞬时故障被当成'查询结果'缓存 600 秒，"
                        + "期间用户每次问同一个问题都会被告知'数据库连接失败'，"
                        + "而数据库其实早就好了")
                .contains("模拟数据库连接失败");
    }

    @Test
    @DisplayName("后果三：连续失败不会熔断，而且失败在审计里被记成 success=true")
    void breakerNeverOpensAndFailuresAreAuditedAsSuccess() {
        FakeWriteTool.failure = new ConnectFailure("模拟持续故障");
        for (int i = 0; i < 6; i++) {
            call("createStayApply");
        }

        assertThat(FakeWriteTool.EXECUTIONS.get())
                .as("熔断分支同样不可达：连续失败 6 次，第 6 次仍然真的执行了")
                .isEqualTo(6);
        verify(auditService, times(6)).recordToolCall(
                eq("createStayApply"), anyString(), anyString(), eq(true), isNull(),
                eq(true), eq(false), anyLong());
        assertThat(auditService.toString()).isNotNull();
    }

    @Test
    @DisplayName("后果四：BizException 的 agentHint 不会回灌给模型")
    void bizExceptionHintIsNotDelivered() {
        FakeWriteTool.failure = new BizException(ResultCode.PARAM_INVALID,
                "入住日期格式不正确", "请让用户按 yyyy-MM-dd 补充入住日期，不要自己猜日期。");

        String result = call("createStayApply");

        assertThat(result)
                .as("模型只拿到异常消息本身，拿不到那段专门写给它看的纠偏提示")
                .contains("入住日期格式不正确")
                .doesNotContain("agentHint")
                .doesNotContain("不要自己猜日期");
    }

    @Test
    @DisplayName("后果五：权限拒绝不会被标记为'未通过权限校验'")
    void permissionDenialIsNotFlaggedInAudit() {
        FakeWriteTool.failure = new BizException(ResultCode.TOOL_PERMISSION_DENIED, "无权调用该能力");

        call("createStayApply");

        // 期望（修好之后）：permissionChecked=false，好把"越权尝试"从审计报表里单独筛出来
        // 现状：仍然记成 true —— 越权尝试和正常调用在审计里长得一模一样
        verify(auditService).recordToolCall(
                eq("createStayApply"), anyString(), eq("无权调用该能力"), eq(true), isNull(),
                eq(true), eq(false), anyLong());
    }

    // ------------------------------------------------------------------

    private static Method method(String name) {
        try {
            return FakeWriteTool.class.getMethod(name, String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolExecutionRequest request(String toolName) {
        return ToolExecutionRequest.builder().name(toolName).arguments(ARGUMENTS).build();
    }
}
