package com.overseas.dorm.agent.tool;

import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.dto.ApplyCancelRequest;
import com.overseas.dorm.domain.dto.ApplyCreateRequest;
import com.overseas.dorm.agent.client.DormServiceClient;
import com.overseas.dorm.domain.vo.ApplyVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 入住申请工具集 —— Agent 真正"能办事"的地方。
 *
 * <h3>为什么写操作必须依赖 idempotencyKey</h3>
 * LLM 的工具调用是<b>可能重复</b>的：模型可能因为超时重试、
 * 也可能在同一轮里把同一个工具调用两次。如果 createStayApply 没有幂等键，
 * 用户就会莫名其妙地多出两张申请单（而且会触发"同时间段重复申请"的业务异常，
 * 让用户以为系统坏了）。
 *
 * <p>因此本工具用「会话 ID + 轮次 ID + 申请要素的哈希」拼出幂等键：
 * 同一轮里重复调用得到同一张单，不同轮提交相同要素也会复用
 * （因为业务上"同一时间段同一目的地"本来就只应有一张单）。
 *
 * <h3>身份从哪来</h3>
 * <b>绝不从参数传 userId</b>。工具内部通过 {@link UserContext} 取当前登录身份，
 * 这个上下文由 HTTP 层的 JWT 拦截器写入，在流式线程里通过
 * `UserContext.runAs(loginUser, ...)` 显式传递。
 * 于是模型无论如何都无法"扮演"别的用户 —— 它连 userId 这个参数都看不到。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplyTools {

    private final DormServiceClient applyService;
    private final RegionResolver regionResolver;

    @Tool("""
            为用户提交宿舍入住申请。这是有副作用的写操作，调用前必须确认四件事：
            1) 目的地区域（regionCode，如 SEA/EU/ME/NEA）；
            2) 入住日期与退房日期（格式 yyyy-MM-dd，且必须晚于今天）；
            3) 出差事由；
            4) 用户已经明确表示"确认提交"。
            如果用户只是问"能不能申请"，不要调用本工具，先说明流程并询问是否提交。
            """)
    public String createStayApply(
            @P("目的地区域编码，如 SEA/EU/ME/NEA") String regionCode,
            @P("入住日期，格式 yyyy-MM-dd") String checkInDate,
            @P("退房日期，格式 yyyy-MM-dd") String checkOutDate,
            @P("目的地城市（可空），如 新加坡、东京") String city,
            @P("出差事由（建议填写，审批时会参考）") String reason,
            @P("出差单号（可空）") String tripNo,
            @P("期望房型（可空）：SINGLE/DOUBLE/TRIPLE/QUAD") String preferredRoomType,
            @P("客户端轮次ID，用于幂等去重；如果不知道就传当前对话轮次标识") String turnId) {
        LoginUser user = UserContext.require();

        LocalDate checkIn = requireDate(checkInDate, "入住日期");
        LocalDate checkOut = requireDate(checkOutDate, "退房日期");
        Long regionId = regionResolver.resolveRequired(regionCode);

        // 幂等键：轮次 + 关键要素。同一轮重复调用、或不同轮提交相同要素，都只会有一张单。
        String idempotencyKey = "AGENT-%d-%s-%s-%s-%s".formatted(
                user.getUserId(),
                turnId == null || turnId.isBlank() ? "T" + System.currentTimeMillis() / 1000 : turnId,
                regionId, checkIn, checkOut);

        ApplyCreateRequest request = new ApplyCreateRequest(
                regionId,
                null,
                city,
                null,
                parseRoomType(preferredRoomType),
                checkIn,
                checkOut,
                tripNo,
                reason,
                idempotencyKey);

        ApplyVO vo = applyService.createApply(request);
        log.info("[Agent工具] 提交入住申请 applyNo={} user={} source=AGENT", vo.getApplyNo(), user.getUsername());

        return JsonUtil.toJson(new ApplyCreatedVO(
                true, vo.getApplyNo(), vo.getStatusLabel(), vo.getRegionName(),
                String.valueOf(vo.getCheckInDate()), String.valueOf(vo.getCheckOutDate()),
                vo.getNights(),
                "申请已提交，状态为「待审批」，区域管理员通常会在 24 小时内处理。"
                        + "审批通过后你会看到一个入住订单号，届时可凭订单号办理入住。"));
    }

    @Tool("""
            查询当前用户自己的入住申请列表。
            可按状态过滤：PENDING 待审批 / APPROVED 已通过 / REJECTED 已驳回 /
            CHECKED_IN 已入住 / CHECKED_OUT 已退房 / CANCELLED 已取消 / EXPIRED 已失效。
            不传状态则返回最近的全部申请。
            """)
    public String listMyApplies(
            @P("申请状态（可空）") String status,
            @P("最多返回条数，默认 5") Integer limit) {
        LoginUser user = UserContext.require();
        ApplyStatus applyStatus = parseStatus(status);
        int max = limit == null || limit <= 0 ? 5 : Math.min(limit, 20);

        List<ApplyVO> all = new ArrayList<>();
        // 分页查询会自动按当前用户过滤（服务端数据权限强制生效）
        var page = applyService.applyPage(1, max, applyStatus, null, null, null, null, null);
        all.addAll(page.getRecords());

        List<ApplyBriefVO> items = all.stream()
                .map(vo -> new ApplyBriefVO(
                        vo.getApplyNo(), vo.getStatusLabel(), vo.getRegionName(), vo.getDestCity(),
                        String.valueOf(vo.getCheckInDate()), String.valueOf(vo.getCheckOutDate()),
                        vo.getNights(), vo.getRoomNo(), vo.getBedNo(),
                        vo.getOrderNo(), vo.getRejectReason()))
                .toList();
        return JsonUtil.toJson(new ApplyListVO(
                page.getTotal(), items.size(), items,
                items.isEmpty()
                        ? "该用户当前没有符合条件的申请。如果用户想申请，可以引导他提供目的地区域与日期。"
                        : null));
    }

    @Tool("""
            根据申请单号查询申请详情与进度时间轴。
            用于回答「我的申请到哪一步了」「为什么被驳回」「分到哪个房间了」这类问题。
            """)
    public String getApplyDetail(@P("申请单号，如 DA202610140001") String applyNo) {
        ApplyVO vo = findMyApplyByNo(applyNo);
        List<TimelineNodeVO> timeline = vo.getTimeline() == null ? List.of()
                : vo.getTimeline().stream()
                    .map(node -> new TimelineNodeVO(node.getLabel(),
                            String.valueOf(node.getTime()), node.getOperator(), node.getRemark()))
                    .toList();
        return JsonUtil.toJson(new ApplyDetailVO(
                vo.getApplyNo(), vo.getStatusLabel(), vo.getRegionName(), vo.getDestCity(),
                String.valueOf(vo.getCheckInDate()), String.valueOf(vo.getCheckOutDate()),
                vo.getNights(), vo.getReason(),
                vo.getRoomNo(), vo.getBedNo(), vo.getBuildingName(), vo.getOrderNo(),
                vo.getApproverName(), String.valueOf(vo.getApproveTime()), vo.getRejectReason(),
                String.valueOf(vo.getExpireTime()),
                vo.getExpireTime() == null ? null
                        : "必须在入住日当天至该时间之前办理入住，否则申请自动失效并释放床位。",
                timeline));
    }

    @Tool("""
            取消一个尚未完成的入住申请。
            只有「待审批」或「已通过」状态的申请可以取消；已经入住的申请需要走退房流程。
            取消会释放已分配的床位。调用前应向用户确认。
            """)
    public String cancelStayApply(
            @P("申请单号，如 DA202610140001") String applyNo,
            @P("取消原因") String reason) {
        ApplyVO existing = findMyApplyByNo(applyNo);
        if (existing.getStatus() == ApplyStatus.CHECKED_IN) {
            throw BizException.forAgent(ResultCode.APPLY_STATE_ILLEGAL,
                    "该申请已入住，不能取消",
                    "用户已入住，无法取消申请。请引导用户办理退房（调用 checkOutOrder），"
                            + "或者如果只是要提前离开，说明提前退房需要填写原因。");
        }
        ApplyVO vo = applyService.cancelApply(new ApplyCancelRequest(existing.getId(),
                reason == null || reason.isBlank() ? "用户通过 AI 助手取消" : reason));
        return JsonUtil.toJson(new ApplyCancelledVO(
                true, vo.getApplyNo(), vo.getStatusLabel(), "申请已取消，占用的床位已释放。"));
    }

    @Tool("查询当前用户接下来与正在进行的入住安排（当前待审批/待入住/在住的行程）。用于回答「我下周的住宿安排好了吗」。")
    public String getMyUpcomingTrips() {
        List<ApplyVO> list = applyService.upcomingApplies();
        List<TripVO> items = list.stream()
                .map(vo -> new TripVO(
                        vo.getApplyNo(), vo.getStatusLabel(), vo.getRegionName(), vo.getDestCity(),
                        String.valueOf(vo.getCheckInDate()), String.valueOf(vo.getCheckOutDate()),
                        vo.getRoomNo(), vo.getBedNo(), vo.getBuildingName(),
                        actionHint(vo.getStatus())))
                .toList();
        return JsonUtil.toJson(new TripListVO(items.size(), items,
                items.isEmpty() ? "当前没有进行中的入住安排。" : null));
    }

    // ================================================================ 工具结果结构
    //
    // 这些 record 是**给模型看的接口**：字段名就是模型理解数据的依据。
    // 用 Map 拼装时一个键名笔误（applyNo 写成 applyNO）不会报错，
    // 模型只是"看不见"这个字段，表现为回答里缺少信息 —— 只能靠肉眼比对 JSON 才能发现。
    // record 还顺带固定了字段顺序，工具输出可以被快照测试比对。

    /** 提交申请的结果。 */
    public record ApplyCreatedVO(
            boolean success, String applyNo, String status, String region,
            String checkInDate, String checkOutDate, Integer nights, String message) {
    }

    /** 申请列表项。 */
    public record ApplyBriefVO(
            String applyNo, String status, String region, String city,
            String checkInDate, String checkOutDate, Integer nights,
            String roomNo, String bedNo, String orderNo, String rejectReason) {
    }

    /** 申请列表结果。 */
    public record ApplyListVO(long total, int returned, List<ApplyBriefVO> applies, String hint) {
    }

    /** 进度时间轴节点。 */
    public record TimelineNodeVO(String node, String time, String operator, String remark) {
    }

    /** 申请详情。 */
    public record ApplyDetailVO(
            String applyNo, String status, String region, String city,
            String checkInDate, String checkOutDate, Integer nights, String reason,
            String roomNo, String bedNo, String building, String orderNo,
            String approver, String approveTime, String rejectReason,
            String expireTime, String expireHint, List<TimelineNodeVO> timeline) {
    }

    /** 取消申请的结果。 */
    public record ApplyCancelledVO(boolean success, String applyNo, String status, String message) {
    }

    /** 行程项。 */
    public record TripVO(
            String applyNo, String status, String region, String city,
            String checkInDate, String checkOutDate,
            String roomNo, String bedNo, String building, String actionHint) {
    }

    /** 行程列表结果。 */
    public record TripListVO(int count, List<TripVO> trips, String hint) {
    }

    private String actionHint(ApplyStatus status) {
        return switch (status) {
            case PENDING -> "等待区域管理员审批";
            case APPROVED -> "审批已通过，到达后请办理入住";
            case CHECKED_IN -> "正在入住中，退房时请办理退房";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 按单号找到「自己的」申请。
     *
     * <p>这里刻意先按单号查、再用数据权限校验归属：
     * 如果直接用"按单号 + employeeId"查，单号不存在与不属于自己会返回同样的
     * "未找到"，用户无法区分，模型的纠错提示也就写不准。
     */
    private ApplyVO findMyApplyByNo(String applyNo) {
        if (applyNo == null || applyNo.isBlank()) {
            throw BizException.forAgent(ResultCode.PARAM_MISSING, "请提供申请单号",
                    "缺少申请单号。请先调用 listMyApplies 获取用户的申请单号，"
                            + "或询问用户具体是哪一张申请。");
        }
        LoginUser user = UserContext.require();
        // 用列表接口按单号过滤，天然带上数据权限
        var page = applyService.applyPage(1, 20, null, null,
                user.isAdmin() ? null : user.getUserId(), applyNo.trim(), null, null);
        for (ApplyVO vo : page.getRecords()) {
            if (vo.getApplyNo().equalsIgnoreCase(applyNo.trim())) {
                return applyService.applyDetail(vo.getId());
            }
        }
        throw BizException.forAgent(ResultCode.APPLY_NOT_FOUND,
                "未找到申请单 " + applyNo,
                "该申请单号不存在或不属于当前用户。请调用 listMyApplies 确认用户自己的单号。");
    }

    private LocalDate requireDate(String text, String field) {
        if (text == null || text.isBlank()) {
            throw BizException.forAgent(ResultCode.PARAM_MISSING, "缺少" + field,
                    "缺少%s。请向用户确认后重试（格式 yyyy-MM-dd）。".formatted(field));
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            throw BizException.forAgent(ResultCode.DATE_RANGE_INVALID, field + "格式不正确",
                    "%s 的格式应为 yyyy-MM-dd。请据此重新调用；如果用户说的是「下周一」这类相对时间，"
                            + "请先结合今天的日期换算出具体日期。".formatted(field));
        }
    }


    private RoomType parseRoomType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return RoomType.valueOf(text.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    private ApplyStatus parseStatus(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (ApplyStatus status : ApplyStatus.values()) {
            if (status.name().equalsIgnoreCase(text.trim()) || status.getLabel().equals(text.trim())) {
                return status;
            }
        }
        return null;
    }
}
