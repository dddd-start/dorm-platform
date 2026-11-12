package com.overseas.dorm.agent.tool;

import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.audit.AgentTurnContext;
import com.overseas.dorm.agent.stream.AgentStreamBuffer;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.security.LoginUser;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H10 正向对照：用户点了"停止"之后，**写类工具不得再执行**。
 *
 * <h3>为什么必须有"不取消"的那一半</h3>
 * 只证明"取消后工具没执行"是没有说服力的 —— 那也可能只是因为这条路径压根不执行工具
 * （mock 模型不会产生 tool_calls，所以端到端根本造不出这个场景）。
 * 因此本测试两个用例成对存在：
 * <ol>
 *   <li><b>不取消</b>：同一个工具、同一条执行路径 → 副作用**必须真的发生**（证明这条路走得通）；</li>
 *   <li><b>已取消</b>：只有"取消标记"这一个自变量不同 → 副作用**必须不发生**。</li>
 * </ol>
 *
 * <p>这条缺陷的真实风险：H10 的取消发生在模型流中途，而工具是模型 {@code tool_calls} 驱动的，
 * "不再消费 token"并不能阻止 LangChain4j 继续执行工具 ——
 * 用户喊停之后，写工具（例如提交入住申请）仍可能真的把单子提交了。
 */
class GuardedToolProviderCancelTest {

    /** 只要被真正执行就会留下"副作用"的写工具。 */
    static class FakeWriteTool {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        @Tool("提交一条入住申请（写操作）")
        public String createApply(String reason) {
            EXECUTIONS.incrementAndGet();
            return "{\"code\":0,\"data\":{\"id\":12345}}";
        }
    }

    private static final Long CONVERSATION_ID = 4242L;
    private static final Long MESSAGE_ID = 9001L;

    @AfterEach
    void tearDown() {
        FakeWriteTool.EXECUTIONS.set(0);
        AgentTurnContext.clear();
    }

    private static ToolExecutor executor(boolean cancelled) {
        AgentStreamBuffer buffer = mock(AgentStreamBuffer.class);
        when(buffer.isCancelled(anyLong())).thenReturn(cancelled);
        GuardedToolProvider provider =
                new GuardedToolProvider(List.of(new FakeWriteTool()), mock(AgentAuditService.class), buffer);
        ToolProviderResult result = provider.provideTools(null);
        ToolExecutor executor = result.toolExecutorByName("createApply");
        assertThat(executor).as("写工具应被注册进工具集").isNotNull();
        return executor;
    }

    private static String call(ToolExecutor executor) {
        LoginUser user = LoginUser.builder().userId(7L).username("tester").role(UserRole.EMPLOYEE).build();
        AgentTurnContext.begin(CONVERSATION_ID, MESSAGE_ID, "turn-cancel-test", user, "trace-cancel-test");
        return executor.execute(ToolExecutionRequest.builder().name("createApply")
                .arguments("{\"reason\":\"H10 取消对照\"}").build(), CONVERSATION_ID);
    }

    @Test
    @DisplayName("正向前提：未取消时，写工具真的会执行并产生副作用")
    void writeToolExecutesWhenNotCancelled() {
        String output = call(executor(false));
        assertThat(FakeWriteTool.EXECUTIONS.get())
                .as("未取消时必须真的执行（否则'取消后没执行'不能说明任何问题）")
                .isEqualTo(1);
        assertThat(output).contains("12345");
    }

    @Test
    @DisplayName("H10：已取消时，写工具一次都不能执行")
    void writeToolSkippedWhenCancelled() {
        String output = call(executor(true));
        assertThat(FakeWriteTool.EXECUTIONS.get())
                .as("用户已停止，写工具不得执行")
                .isZero();
        assertThat(output).contains("用户已停止本轮生成");
    }
}
