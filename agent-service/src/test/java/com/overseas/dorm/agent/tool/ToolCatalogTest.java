package com.overseas.dorm.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具目录一致性测试，用测试守住「每个工具都必须被显式分类」这个约定。
 *
 * {@link ToolCatalog} 的读写分类决定了两个安全行为：
 * <ul>
 *   <li>只读工具允许同轮重复调用去重；</li>
 *   <li>写工具绝不能去重（否则用户第二次真的想再提交一张申请时会被静默吞掉），
 *       也不能自动重试（重试已成功的写操作会造成重复业务单据）。</li>
 * </ul>
 * 如果新加了一个写工具却忘了登记，系统会把它当成只读工具：
 * 去重 + 自动重试会悄悄作用在一个有副作用的操作上。
 * 这类缺陷在功能测试里极难发现，但用「清单完整性」测试可以一次拦住。
 */
class ToolCatalogTest {

    /** 全部工具类（新增工具类时必须同步加到这里，否则本测试会失败提醒你）。 */
    private static final List<Class<?>> TOOL_CLASSES = List.of(
            RoomQueryTools.class,
            PolicyTools.class,
            ApplyTools.class,
            OrderTools.class,
            ProfileTools.class,
            OpsTools.class
    );

    @Test
    @DisplayName("所有 @Tool 方法都必须被显式分类为只读或写")
    void everyToolMethodMustBeClassified() {
        Set<String> declared = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();

        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                String name = method.getName();
                declared.add(name);
                if (!ToolCatalog.READ_ONLY.contains(name) && !ToolCatalog.WRITE.contains(name)) {
                    missing.add(toolClass.getSimpleName() + "#" + name);
                }
            }
        }

        assertThat(declared).as("应至少发现若干工具方法").isNotEmpty();
        assertThat(missing)
                .as("以下 @Tool 方法未在 ToolCatalog 中分类（只读还是写？必须显式登记）")
                .isEmpty();
    }

    @Test
    @DisplayName("目录中登记的工具都真实存在（防止改名后残留旧条目）")
    void catalogEntriesMustExist() {
        Set<String> actual = new LinkedHashSet<>();
        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    actual.add(method.getName());
                }
            }
        }
        Set<String> stale = new LinkedHashSet<>(ToolCatalog.all());
        stale.removeAll(actual);
        assertThat(stale)
                .as("ToolCatalog 中登记了不存在的工具（可能是方法被重命名/删除了）")
                .isEmpty();
    }

    @Test
    @DisplayName("只读与写集合互斥")
    void readOnlyAndWriteAreDisjoint() {
        Set<String> intersection = new LinkedHashSet<>(ToolCatalog.READ_ONLY);
        intersection.retainAll(ToolCatalog.WRITE);
        assertThat(intersection).as("同一个工具不能既是只读又是写").isEmpty();
    }

    @Test
    @DisplayName("关键写工具必须被登记为写（回归保护）")
    void criticalWriteToolsAreMarkedAsWrite() {
        assertThat(ToolCatalog.isWrite("createStayApply")).isTrue();
        assertThat(ToolCatalog.isWrite("cancelStayApply")).isTrue();
        assertThat(ToolCatalog.isWrite("checkIn")).isTrue();
        assertThat(ToolCatalog.isWrite("checkOut")).isTrue();
        // 记忆写入会改变后续所有对话的行为，同样属于有副作用
        assertThat(ToolCatalog.isWrite("rememberPreference")).isTrue();

        // 只读工具不得被误标为写
        assertThat(ToolCatalog.isReadOnly("searchAvailableRooms")).isTrue();
        assertThat(ToolCatalog.isReadOnly("searchDormPolicy")).isTrue();
        assertThat(ToolCatalog.isReadOnly("listMyApplies")).isTrue();
        assertThat(ToolCatalog.isReadOnly("getOperationOverview")).isTrue();
    }

    @Test
    @DisplayName("每个工具都有非空的 @Tool 描述（模型靠描述决定是否调用）")
    void everyToolHasDescription() throws Exception {
        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                // LangChain4j 的 @Tool.value() 是 String[]（按行），需要先拼起来
                assertThat(String.join("\n", tool.value()).trim())
                        .as("%s#%s 的 @Tool 描述不能为空", toolClass.getSimpleName(), method.getName())
                        .isNotBlank();
            }
        }
    }
}
