package com.overseas.dorm.agent.tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：<b>工具名、中文标签、读写属性</b>的唯一真相。
 *
 * 读写分类决定了两个安全行为：
 * <ol>
 *   <li><b>只读工具</b>允许"同轮重复调用去重"（相同参数第二次调用直接复用结果）、
 *       允许对瞬时故障自动重试；</li>
 *   <li><b>写工具</b>绝不允许去重、也绝不自动重试，否则用户第二次真心想再提交一张申请
 *       会被静默吞掉，或者一次超时重试造成重复单据。</li>
 * </ol>
 * 如果靠"方法名里有没有 create/update"来猜，那么将来某个写工具命名成
 * {@code submitXxx} 就会被误判为只读，这个错误在测试里未必暴露，
 * 在生产上表现为"用户偶尔提交不上去"，极难排查。
 *
 * 在此之前，工具名在三个地方各写了一遍：{@code @Tool} 注解、本类的读写清单、
 * 以及 {@code AgentTurnService} 里一个 21 分支的 {@code switch}（用于给前端显示
 * "正在检索可用房源"这类中文标签）。三份清单意味着<b>新增工具时必然漏掉某一处</b>：
 * 漏了标签不会编译失败，只会让界面上出现一串英文方法名。
 *
 * <p>现在改为"一张描述符表推导出所有视图"，并由 {@code ToolCatalogTest}
 * 扫描全部 {@code @Tool} 方法做双向完整性校验：新增工具却忘了登记会直接测试失败；
 * 登记了不存在的工具同样会失败。
 */
public final class ToolCatalog {

    private ToolCatalog() {
    }

    /**
     * 单个工具的描述符。
     *
     * @param name     工具名，必须与 {@code @Tool} 方法名一致
     * @param label    展示给用户的中文标签（流式过程中显示"正在做什么"）
     * @param readOnly true=只读，false=有副作用
     */
    public record ToolDescriptor(String name, String label, boolean readOnly) {
    }

    /**
     * 全部工具的登记表（顺序即界面展示顺序）。
     *
     * <p>按业务分组排列，便于人工核对：房源 → 政策 → 申请 → 订单 → 资料记忆 → 运维。
     */
    private static final List<ToolDescriptor> DESCRIPTORS = List.of(
            // ---------------- 房源查询（只读） ----------------
            new ToolDescriptor("listRegions", "正在查看可选的海外区域", true),
            new ToolDescriptor("searchAvailableRooms", "正在检索可用房源", true),
            new ToolDescriptor("listBuildings", "正在查询公寓列表", true),
            new ToolDescriptor("getBuildingInfo", "正在查看公寓信息", true),
            new ToolDescriptor("getRoomBeds", "正在查看床位占用情况", true),
            new ToolDescriptor("getRegionOverview", "正在统计各区域房源", true),

            // ---------------- 政策检索（只读） ----------------
            new ToolDescriptor("searchDormPolicy", "正在查阅公司制度", true),
            new ToolDescriptor("listKnowledgeDocuments", "正在查看知识库目录", true),

            // ---------------- 申请（读 + 写） ----------------
            new ToolDescriptor("createStayApply", "正在提交入住申请", false),
            new ToolDescriptor("listMyApplies", "正在查询你的申请", true),
            new ToolDescriptor("getApplyDetail", "正在查询申请进度", true),
            new ToolDescriptor("cancelStayApply", "正在取消申请", false),
            new ToolDescriptor("getMyUpcomingTrips", "正在整理你的行程", true),

            // ---------------- 订单（读 + 写） ----------------
            new ToolDescriptor("listMyOrders", "正在查询你的订单", true),
            new ToolDescriptor("getOrderDetail", "正在查询订单详情", true),
            new ToolDescriptor("checkIn", "正在办理入住", false),
            new ToolDescriptor("checkOut", "正在办理退房", false),
            new ToolDescriptor("getOccupancy", "正在统计床位占用", true),

            // ---------------- 个人资料与记忆 ----------------
            new ToolDescriptor("getMyProfile", "正在读取你的资料", true),
            new ToolDescriptor("rememberPreference", "正在记住你的偏好", false),
            new ToolDescriptor("listMyMemories", "正在读取记忆", true),

            // ---------------- 运维（只读） ----------------
            new ToolDescriptor("getOperationOverview", "正在统计运营概况", true),
            new ToolDescriptor("listPendingApplies", "正在查询待审批申请", true),
            new ToolDescriptor("getRoomHealth", "正在检查房源健康度", true),
            new ToolDescriptor("getOverdueOrders", "正在查询逾期未退房订单", true)
    );

    private static final Map<String, ToolDescriptor> BY_NAME = buildIndex();

    /** 只读工具名集合（由描述符表推导，不再单独维护）。 */
    public static final Set<String> READ_ONLY = DESCRIPTORS.stream()
            .filter(ToolDescriptor::readOnly)
            .map(ToolDescriptor::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    /**
     * 写工具名集合（由描述符表推导）。
     *
     * <p>{@code rememberPreference} 虽然"只是记一条偏好"，但它会改变后续所有对话的行为，
     * 属于有副作用的操作，因此归入写工具。
     */
    public static final Set<String> WRITE = DESCRIPTORS.stream()
            .filter(d -> !d.readOnly())
            .map(ToolDescriptor::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    private static Map<String, ToolDescriptor> buildIndex() {
        Map<String, ToolDescriptor> map = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : DESCRIPTORS) {
            ToolDescriptor previous = map.put(descriptor.name(), descriptor);
            if (previous != null) {
                // 重复登记会让"这个工具是什么属性"产生歧义，属于配置错误，直接拒绝启动
                throw new IllegalStateException("工具重复登记：" + descriptor.name());
            }
        }
        return Map.copyOf(map);
    }

    public static boolean isReadOnly(String toolName) {
        return READ_ONLY.contains(toolName);
    }

    public static boolean isWrite(String toolName) {
        return WRITE.contains(toolName);
    }

    /** 全部已登记的工具名。 */
    public static Set<String> all() {
        return BY_NAME.keySet();
    }

    /** 全部描述符（供管理端展示"助手具备哪些能力"）。 */
    public static List<ToolDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    /**
     * 工具名 → 中文标签。
     *
     * <p>未登记的工具退回工具名本身，绝不返回 null：
     * 界面上出现一串英文方法名虽然不好看，但至少能看出是哪个工具；
     * 返回 null 会让前端渲染出空白卡片，反而更难定位。
     */
    public static String labelOf(String toolName) {
        if (toolName == null) {
            return "";
        }
        ToolDescriptor descriptor = BY_NAME.get(toolName);
        return descriptor == null ? toolName : descriptor.label();
    }
}
