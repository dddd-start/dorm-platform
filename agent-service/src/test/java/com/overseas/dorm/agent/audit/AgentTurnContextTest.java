package com.overseas.dorm.agent.audit;

import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨线程上下文：<b>工具在别的线程上跑，凭什么还能拿到正确的身份与轮次</b>。
 *
 * LangChain4j 的工具执行与流式回调不在我们发起调用的线程上，
 * 而在模型 HTTP 客户端的读流线程里（本项目固定为 {@code agent-model-stream-*} 虚拟线程）。于是：
 * <pre>
 *   ✗ ThreadLocal 里读不到上下文 → 工具审计的 conversationId 记成 0、turnId 记成 null
 *   ✗ 没有 turnId 就没有"同轮去重"的指纹键 → 去重守卫静默失效
 *   ✗ UserContext 为空 → 工具里的 UserContext.require() 抛 401，用户看到"偶尔查不到数据"
 *   ✗ traceId 断链 → 排查时串不起一次请求
 * </pre>
 * 解决办法是 {@code AgentTurnContext} 里的那张"会话 ID → 上下文"注册表：
 * 工具执行前用 chatMemoryId（等于 conversationId）把它取回来并显式恢复。
 *
 * <p>本测试把这条机制连同"<b>为什么必须这样</b>"一起测：
 * 先用一个反向用例证明 ThreadLocal 确实跨不了线程（否则注册表就成了没必要的复杂度），
 * 再验证恢复之后一切正常。只有一半的测试无法说明注册表存在的必要性。
 *
 * <p>另一个被钉住的东西是 SSE 事件契约：前端按事件名分支，
 * 名字一改前端就哑了，而这类改动在编译期完全无声。
 */
class AgentTurnContextTest {

    private static final Long CONVERSATION_ID = 3301L;
    private static final Long OTHER_CONVERSATION_ID = 3302L;
    private static final Long MESSAGE_ID = 9901L;

    @AfterEach
    void tearDown() {
        AgentTurnContext.clear();
    }

    private static LoginUser user(long id, String name) {
        return LoginUser.builder().userId(id).username(name).role(UserRole.EMPLOYEE).build();
    }

    private static AgentTurnContext begin(Long conversationId) {
        return AgentTurnContext.begin(conversationId, MESSAGE_ID, "turn-ctx-test",
                user(7L, "zhangwei"), "trace-ctx-test");
    }

    // ==================================================================
    // 一、ThreadLocal 的边界：为什么必须有注册表
    // ==================================================================

    @Test
    @DisplayName("反向用例：ThreadLocal 确实跨不了线程（这就是注册表存在的理由）")
    void threadLocalDoesNotCrossThreads() throws Exception {
        begin(CONVERSATION_ID);
        assertThat(AgentTurnContext.current())
                .as("正向前提：本线程里上下文是有的")
                .isNotNull();

        AtomicReference<AgentTurnContext> inOtherThread = new AtomicReference<>();
        Thread worker = new Thread(() -> inOtherThread.set(AgentTurnContext.current()));
        worker.start();
        worker.join();

        assertThat(inOtherThread.get())
                .as("另一个线程里 current() 必须是 null —— 若这里不为 null，"
                        + "说明上下文靠的是别的东西，注册表那段复杂度就该被质疑")
                .isNull();
    }

    @Test
    @DisplayName("注册表按会话 ID 可取回上下文，恢复后身份与轮次都在")
    void registryRestoresContextOnForeignThread() throws Exception {
        AgentTurnContext started = begin(CONVERSATION_ID);

        AtomicReference<AgentTurnContext> seen = new AtomicReference<>();
        AtomicReference<String> usernameSeen = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            AgentTurnContext restored = AgentTurnContext.byConversation(CONVERSATION_ID);
            seen.set(restored);
            AgentTurnContext.runAs(restored, () ->
                    usernameSeen.set(AgentTurnContext.current().user().getUsername()));
        });
        worker.start();
        worker.join();

        assertThat(seen.get())
                .as("按会话 ID 必须能取回同一个上下文实例")
                .isSameAs(started);
        assertThat(usernameSeen.get())
                .as("恢复之后，工具线程里读到的身份必须是真的那个人 —— "
                        + "工具不越权靠的就是'身份来自服务端上下文'，"
                        + "而这条链路在跨线程时最容易断")
                .isEqualTo("zhangwei");
    }

    @Test
    @DisplayName("会话之间互不串扰：注册表是按会话 ID 分键的")
    void registryIsKeyedPerConversation() {
        AgentTurnContext first = begin(CONVERSATION_ID);
        AgentTurnContext second = AgentTurnContext.begin(OTHER_CONVERSATION_ID, MESSAGE_ID,
                "turn-other", user(8L, "liyang"), "trace-other");

        assertThat(AgentTurnContext.byConversation(CONVERSATION_ID)).isSameAs(first);
        assertThat(AgentTurnContext.byConversation(OTHER_CONVERSATION_ID)).isSameAs(second);
        assertThat(AgentTurnContext.byConversation(9999L))
                .as("未知会话返回 null，而不是随便给一个上下文")
                .isNull();
    }

    @Test
    @DisplayName("clear() 会把注册表里的条目摘掉，避免跨轮次泄漏")
    void clearRemovesRegistration() {
        begin(CONVERSATION_ID);
        assertThat(AgentTurnContext.byConversation(CONVERSATION_ID)).isNotNull();

        AgentTurnContext.clear();

        assertThat(AgentTurnContext.current()).isNull();
        assertThat(AgentTurnContext.byConversation(CONVERSATION_ID))
                .as("不清注册表的话，上一轮的用户会被下一轮的工具'认领'——"
                        + "那是最严重的越权形态：以别人的身份查数据")
                .isNull();
    }

    @Test
    @DisplayName("runAs 是嵌套安全的：返回后恢复原值，不会把外层上下文弄丢")
    void runAsRestoresPreviousContext() {
        AgentTurnContext outer = begin(CONVERSATION_ID);
        AgentTurnContext inner = AgentTurnContext.begin(OTHER_CONVERSATION_ID, MESSAGE_ID,
                "turn-inner", user(9L, "wangwu"), "trace-inner");

        // begin 已经把当前线程的上下文换成了 inner；手动恢复成 outer 再嵌套一次
        AgentTurnContext.runAs(outer, () -> {
            assertThat(AgentTurnContext.current()).isSameAs(outer);
            AgentTurnContext.runAs(inner, () ->
                    assertThat(AgentTurnContext.current()).isSameAs(inner));
            assertThat(AgentTurnContext.current())
                    .as("内层退出后必须回到外层，否则后面所有逻辑都会用错身份")
                    .isSameAs(outer);
        });
    }

    // ==================================================================
    // 二、轮次内序号
    // ==================================================================

    @Test
    @DisplayName("工具调用序号在轮次内单调递增，从 1 开始")
    void toolSequenceIsMonotonicWithinTurn() {
        AgentTurnContext context = begin(CONVERSATION_ID);

        assertThat(context.nextToolSequence()).isEqualTo(1);
        assertThat(context.nextToolSequence()).isEqualTo(2);
        assertThat(context.nextToolSequence()).isEqualTo(3);
    }

    @Test
    @DisplayName("引用按 title+snippet 去重，且最多保留 20 条")
    void referencesAreDeduplicatedAndCapped() {
        AgentTurnContext context = begin(CONVERSATION_ID);
        context.addReferences(List.of(
                Map.of("title", "制度A", "snippet", "片段1"),
                Map.of("title", "制度A", "snippet", "片段1"),
                Map.of("title", "制度B", "snippet", "片段2")));

        assertThat(context.references())
                .as("同一来源同一片段只能出现一次，否则前端引用列表会重复")
                .hasSize(2);

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(Map.of("title", "制度" + i, "snippet", "片段" + i));
        }
        context.addReferences(many);
        assertThat(context.references())
                .as("长回答可能多次检索，引用必须有上限，避免无界增长")
                .hasSize(20);
    }

    // ==================================================================
    // 三、SSE 事件契约（前端按名字分支，改名在编译期无声）
    // ==================================================================

    @Test
    @DisplayName("SSE 事件名集合被钉住：改名会让前端静默失效")
    void streamEventContractIsStable() {
        Set<String> names = Arrays.stream(AgentEnums.StreamEvent.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        assertThat(names)
                .as("事件名是前后端之间的契约。前端按名字分支，"
                        + "改一个名字不会编译失败、也不会让后端测试变红 —— 只会让界面上少一块内容")
                .containsExactlyInAnyOrder(
                        "START", "REASONING", "DELTA", "TOOL", "REFERENCE",
                        "HUMAN_REQUIRED", "DONE", "BLOCKED", "INTERRUPTED", "ERROR");
    }

    @Test
    @DisplayName("终态事件集合：客户端只应在这四种事件上结束等待")
    void terminalEventsAreIdentifiable() {
        // 这四个是"这一轮结束了"的信号，其余都是过程事件。
        // 客户端据此判断"还要不要继续等"，所以它们必须与后端的收口路径一一对应：
        // 正常完成 DONE / 被安全审核拦下 BLOCKED / 被打断或超时 INTERRUPTED / 出错 ERROR。
        List<String> terminal = List.of("DONE", "BLOCKED", "INTERRUPTED", "ERROR");

        Set<String> all = Arrays.stream(AgentEnums.StreamEvent.values())
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(all)
                .as("每一个终态事件都必须在枚举里真实存在")
                .containsAll(terminal);

        // HUMAN_REQUIRED 目前不会由后端产生（需要人工确认的两阶段写操作尚未实现）。
        // 这里显式记录这一点，避免读者看到枚举里有它就以为功能已经存在 ——
        // 仓库的独立审计把相关项列为"部分实现"，本教程照实标注。
        assertThat(all)
                .as("HUMAN_REQUIRED 是预留的事件名：它在契约里，但后端目前不会发出")
                .contains("HUMAN_REQUIRED");
    }
}
