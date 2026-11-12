package com.overseas.dorm.agent.tool;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.entity.StayApply;
import com.overseas.dorm.agent.client.DormServiceClient;


import com.overseas.dorm.domain.vo.RoomStatsVO;

import com.overseas.dorm.domain.vo.StatsVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运维类工具（仅管理员）。
 *
 * <h3>为什么管理类工具要单独放一个类</h3>
 * 不是为了"代码整洁"，而是为了<b>权限边界清晰可见</b>：
 * 装配 AiService 时，员工用的助手只挂载 {@link RoomQueryTools}、
 * {@link ApplyTools}、{@link OrderTools}、{@link PolicyTools}、{@link ProfileTools}；
 * 管理员助手额外挂载本类。
 *
 * <p>也就是说，员工角色下这些工具<b>根本不在模型的工具清单里</b> ——
 * 模型连"可以调用审批工具"这件事都不知道，自然不会去尝试。
 * 这比"挂上工具然后在执行时抛权限异常"更安全：
 * 后者会把权限模型暴露给模型，给提示词注入留下"试探"的空间。
 *
 * <h3>二道保险</h3>
 * 即便配置错了（比如把管理工具挂给了员工），每个方法内部仍会
 * 校验角色并抛出带纠偏提示的异常。这是"纵深防御"的标准做法：
 * 任何一层成立，攻击都不成功。
 *
 * <h3>本类全部只读</h3>
 * 运维工具不提供任何写操作。审批、分房这类需要承担责任的决策，
 * 一律要求管理员在管理界面上亲自操作 —— 这也是企业审计的基本要求：
 * 「谁批的」必须是人，不能是模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsTools {

    /** 清单类工具的默认返回条数。 */
    private static final int DEFAULT_LIST_LIMIT = 10;

    /** 清单类工具的硬上限：工具结果会全部进入模型上下文并计费，不能放开。 */
    private static final int MAX_LIST_LIMIT = 20;

    private final DormServiceClient statsService;
    private final DormServiceClient applyService;
    private final DormServiceClient roomService;
    private final DormServiceClient orderService;
    private final RegionResolver regionResolver;

    @Tool("""
            查看运营总览：房间与床位数量、入住率、各状态申请与订单的数量、近 14 天申请趋势。
            仅管理员可用。用于回答「现在整体入住情况怎么样」这类问题。
            """)
    public String getOperationOverview(
            @P("区域编码（可空），总管理员可按区域查看；区域管理员忽略此参数") String regionCode) {
        requireAdmin();
        Long regionId = regionResolver.resolveOrNull(regionCode);
        StatsVO stats = statsService.overview(regionId, 14);

        return JsonUtil.toJson(new OperationOverviewVO(
                regionId == null ? "全部区域" : "区域 " + regionCode,
                stats.getRoomCount(), stats.getOpenRoomCount(), stats.getClosedRoomCount(),
                stats.getMaintenanceRoomCount(), stats.getBedCount(), stats.getIdleBedCount(),
                stats.getOccupiedBedCount(),
                // 数值型入住率（不是 "12.5%" 字符串）：模型要能比较"哪个区域更紧张"
                stats.getOccupancyRate(),
                stats.getApplyStatusCount(), stats.getOrderStatusCount(),
                stats.getApplyTrend()));
    }

    @Tool("""
            列出待审批的入住申请（最多 20 条），供管理员了解当前有多少申请在排队。
            仅管理员可用，区域管理员只能看到本区域的申请。
            注意：本工具只能"查看"，不能审批。审批需要管理员在管理界面操作。
            """)
    public String listPendingApplies(@P("最多返回条数，默认 10") Integer limit) {
        requireAdmin();
        int max = limit == null || limit <= 0
                ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        var page = applyService.applyPage(1, max, ApplyStatus.PENDING, null, null, null, null, null);
        List<PendingApplyVO> items = page.getRecords().stream()
                .map(vo -> new PendingApplyVO(
                        vo.getApplyNo(), vo.getEmployeeName(), vo.getRegionName(), vo.getDestCity(),
                        String.valueOf(vo.getCheckInDate()), String.valueOf(vo.getCheckOutDate()),
                        vo.getNights(), String.valueOf(vo.getCreateTime())))
                .toList();
        return JsonUtil.toJson(new PendingListVO(page.getTotal(), items.size(), items,
                "审批与分房需要管理员在「申请审批」页面操作，AI 助手不代替管理员做审批决策。"));
    }

    @Tool("""
            查看房源健康度：哪些房间已关闭或维修中、以及各区域剩余可订床位。
            仅管理员可用。用于回答「有没有需要处理的房源问题」。
            """)
    public String getRoomHealth() {
        requireAdmin();
        List<RoomHealthItem> regionRows = new ArrayList<>();
        for (RoomStatsVO row : roomService.statisticsByRegion(null)) {
            long total = num(row.getBedCount());
            long disabled = num(row.getDisabledBedCount());
            long occupied = num(row.getOccupiedBedCount());
            long usable = total - disabled;
            regionRows.add(new RoomHealthItem(
                    row.getRegionName(),
                    row.getRoomCount(),
                    row.getClosedRoomCount(),
                    row.getMaintenanceRoomCount(),
                    row.getIdleBedCount(),
                    row.getOccupiedBedCount(),
                    usable <= 0 ? BigDecimal.ZERO
                            : BigDecimal.valueOf(occupied * 100.0 / usable)
                                .setScale(1, java.math.RoundingMode.HALF_UP)));
        }
        return JsonUtil.toJson(new RoomHealthVO(regionRows,
                "若某区域空闲床位长期为 0，可考虑协调其它公寓或调整该区域房间状态。"));
    }

    @Tool("查看已到退房日期但仍未办理退房的订单（逾期未退房），用于运营跟进。仅管理员可用。")
    public String getOverdueOrders(@P("最多返回条数，默认 10") Integer limit) {
        requireAdmin();
        int max = limit == null || limit <= 0
                ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        var overdue = orderService.overdueOrders(max);
        List<OverdueOrderVO> items = overdue.stream()
                .map(order -> new OverdueOrderVO(
                        order.getOrderNo(), order.getEmployeeName(),
                        String.valueOf(order.getCheckOutDate()),
                        order.getRoomId(), order.getBedId()))
                .toList();
        return JsonUtil.toJson(new OverdueListVO(items.size(), items,
                "可以提醒员工尽快办理退房，避免占住床位影响他人。"));
    }

    // ================================================================ 工具结果结构
    //
    // 运维工具的结果是**给模型看的接口**：管理员会追问"具体哪个区域、多少间"，
    // 字段名必须稳定。用 record 固定，避免键名笔误导致模型答不上来。

    /** 运营总览。 */
    public record OperationOverviewVO(
            String scope, Long roomCount, Long openRoomCount, Long closedRoomCount,
            Long maintenanceRoomCount, Long bedCount, Long idleBedCount, Long occupiedBedCount,
            java.math.BigDecimal occupancyRate,
            Map<String, Long> applyStatusCount, Map<String, Long> orderStatusCount,
            List<com.overseas.dorm.domain.vo.DailyCountVO> applyTrendLast14Days) {
    }

    /** 待审批申请项。 */
    public record PendingApplyVO(
            String applyNo, String employee, String region, String city,
            String checkInDate, String checkOutDate, Integer nights, String createTime) {
    }

    /** 待审批清单。 */
    public record PendingListVO(
            long pendingCount, int returned, List<PendingApplyVO> applies, String note) {
    }

    /** 房源健康度清单。 */
    public record RoomHealthVO(List<RoomHealthItem> regions, String hint) {
    }

    /** 逾期订单项。 */
    public record OverdueOrderVO(
            String orderNo, String employee, String checkOutDate, Long roomId, Long bedId) {
    }

    /** 逾期订单清单。 */
    public record OverdueListVO(int count, List<OverdueOrderVO> orders, String hint) {
    }

    // ------------------------------------------------------------------ 权限

    /**
     * 工具级权限校验。
     *
     * <p>注意异常里带的是 {@code agentHint}：模型会看到
     * 「该操作需要管理员权限，请告知用户联系区域管理员」这样的提示，
     * 于是它能给用户一个得体的回复，而不是把堆栈信息转述出来。
     */
    private void requireAdmin() {
        LoginUser user = UserContext.require();
        if (!user.isAdmin()) {
            log.warn("[Agent工具] 非管理员尝试调用运维工具 userId={} role={}", user.getUserId(), user.getRole());
            throw BizException.forAgent(ResultCode.TOOL_PERMISSION_DENIED,
                    "该操作需要管理员权限",
                    "当前用户是员工角色，无权查看运营数据。请不要重试，"
                            + "改为礼貌地告知用户：这类运营统计需要区域管理员或总管理员权限，"
                            + "如需了解可以联系所在区域的管理员。");
        }
    }


    private static long num(Number value) {
        return value == null ? 0L : value.longValue();
    }

    /**
     * 房源健康度条目。
     *
     * <p>{@code occupancyRate} 改为小数（1 位）而不是 {@code "12.5%"} 字符串：
     * 工具结果会被模型读取并复述，数值形式让它能做比较（"哪个区域最紧张"），
     * 字符串形式反而要它自己解析百分号。
     */
    public record RoomHealthItem(
            String regionName, Integer rooms, Integer closedRooms, Integer maintenanceRooms,
            Integer idleBeds, Integer occupiedBeds, BigDecimal occupancyRate) {
    }

}
