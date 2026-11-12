package com.overseas.dorm.agent.tool;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.dto.CheckInRequest;
import com.overseas.dorm.domain.dto.CheckOutRequest;
import com.overseas.dorm.agent.client.DormServiceClient;


import com.overseas.dorm.domain.vo.ApplyVO;
import com.overseas.dorm.domain.vo.OrderVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订单与入住退房工具集。
 *
 * <p>这里体现了工具权限分级的两种做法：
 * <ul>
 *   <li><b>数据权限收窄</b>（listMyOrders / getOrderDetail / checkIn / checkOut）：
 *       不额外限制角色，但服务端强制只返回、只操作当前用户自己的数据。
 *       员工完全可以授权 AI 替自己办理入住退房 —— 这正是助手的价值所在；</li>
 *   <li><b>角色白名单</b>（见 {@link OpsTools} 的管理类工具）：
 *       只有管理员可调，非管理员调用时工具抛出带纠偏提示的异常，
 *       模型据此转告用户"这需要管理员权限"，而不是把系统错误抛给用户。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    /** 订单列表默认返回条数（用户问"我的订单"，5 条足够覆盖近期行程）。 */
    private static final int DEFAULT_ORDER_LIMIT = 5;

    /** 订单列表单次最多返回条数：工具结果会全部进入模型上下文并计费，不能放开。 */
    private static final int MAX_ORDER_LIMIT = 20;

    private final DormServiceClient orderService;
    private final DormServiceClient applyService;
    private final DormServiceClient statsService;
    private final RegionResolver regionResolver;

    @Tool("""
            查询当前用户的入住订单列表。订单与申请的关系是：
            申请审批通过并分配房间后会自动生成订单；订单承载实际入住与退房。
            可按状态过滤：RESERVED 已预订 / CHECKED_IN 已入住 / CHECKED_OUT 已退房 / CANCELLED 已取消。
            """)
    public String listMyOrders(
            @P("订单状态（可空）：RESERVED/CHECKED_IN/CHECKED_OUT/CANCELLED") String status,
            @P("最多返回条数，默认 5") Integer limit) {
        UserContext.require();
        int max = limit == null || limit <= 0
                ? DEFAULT_ORDER_LIMIT : Math.min(limit, MAX_ORDER_LIMIT);
        List<OrderBriefVO> items = new ArrayList<>();
        for (OrderVO vo : orderService.myOrders()) {
            if (status != null && !status.isBlank()
                    && !vo.getStatus().name().equalsIgnoreCase(status.trim())
                    && !vo.getStatusLabel().equals(status.trim())) {
                continue;
            }
            items.add(brief(vo));
            if (items.size() >= max) {
                break;
            }
        }
        return JsonUtil.toJson(new OrderListVO(items.size(), items,
                items.isEmpty()
                        ? "该用户当前没有符合条件的订单。订单是在申请审批通过后才生成的，"
                                + "如果用户刚提交申请，可以先用 listMyApplies 查看申请进度。"
                        : null));
    }

    @Tool("""
            根据订单号查询订单详情（房间、床位、入住退房日期、实际入住时间、费用）。
            用于回答「我的订单什么情况」「我住哪个房间」「费用多少」这类问题。
            """)
    public String getOrderDetail(@P("订单号，如 DO202610140001") String orderNo) {
        OrderVO vo = findMyOrderByNo(orderNo);
        return JsonUtil.toJson(new OrderDetailVO(
                vo.getOrderNo(), vo.getApplyNo(), vo.getStatusLabel(), vo.getRegionName(),
                vo.getRoomNo(), vo.getBedNo(),
                String.valueOf(vo.getCheckInDate()), String.valueOf(vo.getCheckOutDate()),
                vo.getNights(), vo.getBuildingName(),
                String.valueOf(vo.getRealCheckInTime()), String.valueOf(vo.getRealCheckOutTime()),
                vo.getAmount(), vo.getCurrency(), vo.getEarlyCheckOutReason(),
                switch (vo.getStatus()) {
                    case RESERVED -> "到达公寓后办理入住即可（需在入住日当天至次日 23:59 之间）。";
                    case CHECKED_IN -> "退房时办理退房，请在退房日 12:00 前完成。";
                    case CHECKED_OUT -> "本订单已完成退房。";
                    case CANCELLED -> "本订单已取消。";
                }));
    }

    @Tool("""
            为用户办理入住（入住日到达公寓后使用）。
            只有「已通过」状态的申请才能办理入住，且必须在入住日当天至申请失效时间之间。
            办理前应确认用户已经到达公寓。这是有副作用的操作，执行前先跟用户确认。
            """)
    public String checkIn(
            @P("申请单号或订单号，如 DA202610140001 或 DO202610140001") String applyNoOrOrderNo) {
        LoginUser user = UserContext.require();
        Long applyId = resolveApplyId(applyNoOrOrderNo);
        ApplyVO vo = applyService.checkIn(new CheckInRequest(applyId, "用户通过 AI 助手办理入住"));
        log.info("[Agent工具] 办理入住 applyNo={} user={}", vo.getApplyNo(), user.getUsername());
        return JsonUtil.toJson(new CheckInResultVO(
                true, vo.getApplyNo(), vo.getStatusLabel(), vo.getRoomNo(), vo.getBedNo(),
                vo.getBuildingName(), String.valueOf(vo.getCheckInTime()),
                "入住办理成功。原定退房日期为 %s，请记得按时办理退房。"
                        .formatted(vo.getCheckOutDate())));
    }

    @Tool("""
            为用户办理退房。必须在「已入住」状态下调用。
            如果实际退房日期早于原定退房日期（提前退房），必须提供原因，否则会被系统拒绝。
            退房会释放床位，并按实际住宿晚数重算费用。这是有副作用的操作，执行前先跟用户确认。
            """)
    public String checkOut(
            @P("申请单号或订单号") String applyNoOrOrderNo,
            @P("提前退房原因；若按原定日期退房则留空") String earlyCheckOutReason) {
        LoginUser user = UserContext.require();
        Long applyId = resolveApplyId(applyNoOrOrderNo);
        ApplyVO vo = applyService.checkOut(new CheckOutRequest(
                null, applyId, earlyCheckOutReason, "用户通过 AI 助手办理退房"));
        log.info("[Agent工具] 办理退房 applyNo={} user={} 提前={}",
                vo.getApplyNo(), user.getUsername(), earlyCheckOutReason != null);
        return JsonUtil.toJson(new CheckOutResultVO(
                true, vo.getApplyNo(), vo.getStatusLabel(),
                String.valueOf(vo.getCheckOutTime()),
                "退房办理成功，床位已释放。感谢使用，祝旅途顺利。"));
    }

    @Tool("""
            查看某个区域当前的床位占用情况（已占用/可用/空闲床位数与入住率）。
            员工可用来判断哪个区域房源紧张；回答「还有房吗」这类问题时配合 searchAvailableRooms 使用。
            """)
    public String getOccupancy(
            @P("区域编码（可空），如 SEA/EU/ME/NEA；为空表示当前用户可见的全部区域") String regionCode) {
        Long regionId = regionResolver.resolveOrNull(regionCode);
        return JsonUtil.toJson(statsService.occupancy(regionId));
    }

    // ================================================================ 工具结果结构
    //
    // 这些 record 是**给模型看的接口**：字段名就是模型理解数据的依据。
    // 用 Map 拼装时键名笔误不会报错，模型只是"看不见"该字段，
    // 表现为回答缺信息 —— 只能靠肉眼比对 JSON 才能发现。

    /** 订单摘要（列表项）。 */
    public record OrderBriefVO(
            String orderNo, String applyNo, String status, String region,
            String roomNo, String bedNo, String checkInDate, String checkOutDate, Integer nights) {
    }

    /** 订单列表结果。 */
    public record OrderListVO(int count, List<OrderBriefVO> orders, String hint) {
    }

    /**
     * 订单详情。
     *
     * <p>在摘要基础上补充建筑、实际入住/退房时间、金额与下一步提示。
     */
    public record OrderDetailVO(
            String orderNo, String applyNo, String status, String region,
            String roomNo, String bedNo, String checkInDate, String checkOutDate, Integer nights,
            String building, String realCheckInTime, String realCheckOutTime,
            java.math.BigDecimal amount, String currency,
            String earlyCheckOutReason, String nextStep) {
    }

    /** 入住办理结果。 */
    public record CheckInResultVO(
            boolean success, String applyNo, String status, String roomNo,
            String bedNo, String building, String checkInTime, String message) {
    }

    /** 退房办理结果。 */
    public record CheckOutResultVO(
            boolean success, String applyNo, String status, String checkOutTime, String message) {
    }

    // ------------------------------------------------------------------ 内部工具

    private OrderBriefVO brief(OrderVO vo) {
        return new OrderBriefVO(
                vo.getOrderNo(), vo.getApplyNo(), vo.getStatusLabel(), vo.getRegionName(),
                vo.getRoomNo(), vo.getBedNo(),
                String.valueOf(vo.getCheckInDate()), String.valueOf(vo.getCheckOutDate()),
                vo.getNights());
    }

    private OrderVO findMyOrderByNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw BizException.forAgent(ResultCode.PARAM_MISSING, "请提供订单号",
                    "缺少订单号。请先调用 listMyOrders 获取用户自己的订单号。");
        }
        for (OrderVO vo : orderService.myOrders()) {
            if (vo.getOrderNo().equalsIgnoreCase(orderNo.trim())) {
                return vo;
            }
        }
        throw BizException.forAgent(ResultCode.ORDER_NOT_FOUND, "未找到订单 " + orderNo,
                "该订单号不存在或不属于当前用户。请调用 listMyOrders 确认。");
    }

    /**
     * 用申请单号或订单号定位申请。
     *
     * <p>为什么两种单号都接受：用户根本记不清自己手里的是 DA 开头还是 DO 开头，
     * 让模型为此反复追问体验很差。工具内部兼容两种输入即可。
     */
    private Long resolveApplyId(String applyNoOrOrderNo) {
        if (applyNoOrOrderNo == null || applyNoOrOrderNo.isBlank()) {
            throw BizException.forAgent(ResultCode.PARAM_MISSING, "请提供申请单号或订单号",
                    "缺少单号。请先调用 listMyApplies 或 listMyOrders 获取单号。");
        }
        String key = applyNoOrOrderNo.trim();
        for (OrderVO order : orderService.myOrders()) {
            if (order.getOrderNo().equalsIgnoreCase(key) || order.getApplyNo().equalsIgnoreCase(key)) {
                return order.getApplyId();
            }
        }
        // 也可能是还没生成订单的申请（例如尚未审批）
        var page = applyService.applyPage(1, 20, null, null, null, key, null, null);
        for (ApplyVO vo : page.getRecords()) {
            if (vo.getApplyNo().equalsIgnoreCase(key)) {
                return vo.getId();
            }
        }
        throw BizException.forAgent(ResultCode.ORDER_NOT_FOUND, "未找到单号 " + applyNoOrOrderNo,
                "该单号不存在或不属于当前用户。请调用 listMyOrders / listMyApplies 确认后再试。");
    }

}
