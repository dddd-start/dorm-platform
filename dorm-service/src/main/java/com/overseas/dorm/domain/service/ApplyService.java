package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.constant.BusinessLimits;
import com.overseas.dorm.common.constant.RedisKeys;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.RoomStatus;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.statemachine.ApplyStateMachine;
import com.overseas.dorm.common.util.DateUtil;
import com.overseas.dorm.domain.dto.ApplyApproveRequest;
import com.overseas.dorm.domain.dto.ApplyCancelRequest;
import com.overseas.dorm.domain.dto.ApplyCreateRequest;
import com.overseas.dorm.domain.dto.ApplyReassignRequest;
import com.overseas.dorm.domain.dto.ApplyRejectRequest;
import com.overseas.dorm.domain.dto.CheckInRequest;
import com.overseas.dorm.domain.dto.CheckOutRequest;
import com.overseas.dorm.domain.entity.DormBed;
import com.overseas.dorm.domain.entity.DormBuilding;
import com.overseas.dorm.domain.entity.DormRoom;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.entity.StayApply;
import com.overseas.dorm.domain.entity.StayOrder;
import com.overseas.dorm.domain.mapper.StayApplyMapper;
import com.overseas.dorm.domain.support.BizNoGenerator;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.support.StatusSummaryBuilder;
import com.overseas.dorm.domain.support.DistributedLock;
import com.overseas.dorm.domain.support.PageMapper;
import com.overseas.dorm.domain.vo.ApplyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 入住申请服务 —— 业务核心。
 *
 * <p>这个类集中体现了本系统对「数据一致性」的取舍：
 * <ul>
 *   <li><b>所有状态变更都过状态机</b>（{@link ApplyStateMachine}），
 *       杜绝"已驳回却又被入住"这类脏状态；</li>
 *   <li><b>分床是三步合一的原子操作</b>：Redis 分布式锁收敛并发 →
 *       {@code UPDATE ... WHERE status='IDLE'} 条件更新兜底 →
 *       唯一索引/乐观锁防止重复；</li>
 *   <li><b>幂等键</b>：同一份请求重复提交只产生一张单，
 *       靠 {@code uk_apply_idempotency} 唯一索引保证，即便并发穿透也安全；</li>
 *   <li><b>副作用走 Outbox</b>：审批通过后的通知/统计不在主事务里做，
 *       避免"审批成功但通知失败导致整个审批回滚"。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplyService {

    private final StayApplyMapper applyMapper;
    private final com.overseas.dorm.domain.mapper.StayOrderMapper orderMapper;
    private final com.overseas.dorm.domain.mapper.DormBedMapper bedMapper;
    private final com.overseas.dorm.domain.mapper.DormRoomMapper roomMapper;
    private final com.overseas.dorm.domain.mapper.DormBuildingMapper buildingMapper;
    private final com.overseas.dorm.domain.mapper.RegionMapper regionMapper;
    private final com.overseas.dorm.domain.mapper.SysUserMapper userMapper;
    private final DataScope dataScope;
    private final DistributedLock lock;
    private final BizNoGenerator bizNoGenerator;
    private final AuditService auditService;
    private final OutboxService outboxService;
    /**
     * 自身代理（ObjectProvider 惰性解析，避免与自身形成构造期循环）。
     *
     * <p>用途只有一个：{@link #create} 需要在**真正的新事务**里重试 ——
     * Spring 的事务是代理实现的，类内部自调用会让 {@code @Transactional} 失效
     * （本项目在 {@code KnowledgeBaseLoader}、{@code ConversationService} 上都踩过）。
     */
    private final org.springframework.beans.factory.ObjectProvider<ApplyService> selfProvider;

    public static final String EVENT_APPLY_CREATED = "APPLY_CREATED";
    public static final String EVENT_APPLY_APPROVED = "APPLY_APPROVED";
    public static final String EVENT_APPLY_REJECTED = "APPLY_REJECTED";
    public static final String EVENT_APPLY_CANCELLED = "APPLY_CANCELLED";
    public static final String EVENT_APPLY_EXPIRED = "APPLY_EXPIRED";
    public static final String EVENT_ORDER_CHECKED_IN = "ORDER_CHECKED_IN";
    public static final String EVENT_ORDER_CHECKED_OUT = "ORDER_CHECKED_OUT";

    // ================================================================ 创建申请

    /**
     * 创建入住申请。
     *
     * <p>校验链条（顺序刻意如此，先便宜后昂贵、先参数后权限）：
     * <pre>
     *   参数合法性 → 日期窗口 → 区域存在且启用 → 幂等检查 →
     *   同人时间冲突检查 → 写库（唯一索引兜底）
     * </pre>
     */
    public ApplyVO create(ApplyCreateRequest request, ApplySource source) {
        // 【M8 修复（做到 20/20）】并发同一幂等键时，**换一个新事务重试**，而不是在
        // 已经"中毒"的旧事务里回查。
        //
        // 旧事务为什么不行：MySQL 默认 REPEATABLE-READ，一致性读沿用事务开始时的快照；
        // 胜者那一行是在本事务开始之后才提交的，快照里根本没有它。
        // 上一轮（6da2397）用"独立事务回查"绕过了查不到的问题，成功率从 1/20 提到 11/20，
        // 但剩下 9 个仍然会走到 detail() —— 那一步又在本事务里读，仍然看不见胜者，
        // 只能退化成「提交冲突，请重试」（诚实，但不是幂等重放语义）。
        //
        // 为什么重试一定能看到胜者：InnoDB 的重复键插入**会阻塞到胜者提交/回滚**，
        // 所以拿到 DuplicateKeyException 时胜者必然已经提交；此时开一个新事务，
        // 新快照必然包含它，第 3 步幂等检查就会命中并返回完整 detail。
        // 这也是"整段放进新事务 + 重试"而不是"局部回查"的原因（交接文档里的结论）。
        int maxAttempts = 4;
        for (int attempt = 1; ; attempt++) {
            try {
                return selfProvider.getObject().createInNewTransaction(request, source);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                if (attempt >= maxAttempts) {
                    log.warn("幂等键冲突重试 {} 次仍未取到既有单据 key={}",
                            maxAttempts, request.idempotencyKey(), e);
                    throw BizException.of(ResultCode.DUPLICATE_DATA, "提交冲突，请重试");
                }
                // 退避一拍：高峰期把"同一毫秒内所有败者一起重试"错开
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw BizException.of(ResultCode.DUPLICATE_DATA, "提交冲突，请重试");
                }
                log.info("并发重复提交被唯一索引拦截，换新事务重试（第 {} 次）key={}",
                        attempt + 1, request.idempotencyKey());
            }
        }
    }

    /**
     * 创建入住申请（单一事务内的完整实现；由 {@link #create} 在新事务里调用）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplyVO createInNewTransaction(ApplyCreateRequest request, ApplySource source) {
        LoginUser user = UserContext.require();

        // 1. 日期窗口
        int nights = DateUtil.nightsBetween(request.checkInDate(), request.checkOutDate());
        if (request.checkInDate().isBefore(DateUtil.today())) {
            throw BizException.of(ResultCode.DATE_RANGE_INVALID, "入住日期不能早于今天");
        }
        if (request.checkInDate().isAfter(DateUtil.today().plusMonths(6))) {
            throw BizException.of(ResultCode.DATE_RANGE_INVALID, "最多只能提前 6 个月提交申请");
        }

        // 2. 区域
        Region region = regionMapper.selectById(request.regionId());
        if (region == null) {
            throw BizException.of(ResultCode.REGION_NOT_FOUND);
        }
        if (region.getStatus() == null || region.getStatus() == 0) {
            throw BizException.param("区域「%s」已停用，暂不可申请".formatted(region.getName()));
        }
        if (request.buildingId() != null) {
            DormBuilding building = buildingMapper.selectById(request.buildingId());
            if (building == null) {
                throw BizException.of(ResultCode.BUILDING_NOT_FOUND);
            }
            if (!building.getRegionId().equals(request.regionId())) {
                throw BizException.param("所选公寓不属于该区域");
            }
        }

        // 3. 幂等：同一幂等键已存在则直接返回既有单据（这是"重复提交"的正常分支，不是错误）
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            StayApply existed = applyMapper.selectOne(new LambdaQueryWrapper<StayApply>()
                    .eq(StayApply::getIdempotencyKey, request.idempotencyKey())
                    .last("LIMIT 1"));
            if (existed != null) {
                // 只允许本人复用同一幂等键，防止通过猜测幂等键读取他人单据
                if (!existed.getEmployeeId().equals(user.getUserId())) {
                    throw BizException.forbidden("幂等键已被占用");
                }
                log.info("检测到重复提交，返回既有申请 applyNo={}", existed.getApplyNo());
                return detail(existed.getId());
            }
        }

        // 4. 同一时间段不允许重复申请
        List<StayApply> overlaps = applyMapper.selectOverlapping(user.getUserId(),
                request.checkInDate(), request.checkOutDate());
        if (!overlaps.isEmpty()) {
            StayApply conflict = overlaps.get(0);
            throw BizException.of(ResultCode.APPLY_DATE_OVERLAP,
                    ("你在 %s 至 %s 已有申请（%s，%s），同一时间段不能重复申请")
                            .formatted(conflict.getCheckInDate(), conflict.getCheckOutDate(),
                                    conflict.getApplyNo(), conflict.getStatus().getLabel()));
        }

        StayApply apply = new StayApply();
        apply.setApplyNo(bizNoGenerator.next(BizNoGenerator.TYPE_APPLY));
        apply.setEmployeeId(user.getUserId());
        apply.setEmployeeName(user.getRealName());
        apply.setEmployeeNo(user.getEmployeeNo());
        apply.setRegionId(request.regionId());
        apply.setDestCountry(request.destCountry() != null ? request.destCountry() : region.getCountry());
        apply.setDestCity(request.destCity());
        apply.setBuildingId(request.buildingId());
        apply.setPreferredRoomType(request.preferredRoomType());
        apply.setCheckInDate(request.checkInDate());
        apply.setCheckOutDate(request.checkOutDate());
        apply.setNights(nights);
        apply.setTripNo(request.tripNo());
        apply.setReason(request.reason());
        apply.setStatus(ApplyStatus.PENDING);
        apply.setApplySource(source == null ? ApplySource.WEB : source);
        apply.setIdempotencyKey(request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? null : request.idempotencyKey());

        // 唯一索引冲突（幂等键或单号撞车）：**不在这里回查**，直接抛给外层
        // create() 换一个新事务重试（原因见 create() 的注释）。
        applyMapper.insert(apply);

        outboxService.publish(EVENT_APPLY_CREATED, "APPLY", apply.getId(), Map.of(
                "applyNo", apply.getApplyNo(),
                "employeeId", apply.getEmployeeId(),
                "employeeName", apply.getEmployeeName(),
                "regionId", apply.getRegionId(),
                "checkInDate", String.valueOf(apply.getCheckInDate()),
                "checkOutDate", String.valueOf(apply.getCheckOutDate())));
        auditService.success(AuditEnums.Module.APPLY, "APPLY_CREATE", "APPLY", apply.getId(), null, apply);
        log.info("入住申请已创建 applyNo={} employee={} {} -> {} nights={} source={}",
                apply.getApplyNo(), apply.getEmployeeName(), apply.getCheckInDate(),
                apply.getCheckOutDate(), nights, apply.getApplySource());

        return detail(apply.getId());
    }

    // ================================================================ 审批 + 分房

    /**
     * 审批通过并分配床位。
     *
     * <p>「通过」与「分房」是一个不可分割的原子动作：不这样做的话，
     * 会出现大量「已通过但没床位」的申请，员工以为安排好了、到店却没地方住。
     */
    @Transactional
    public ApplyVO approve(ApplyApproveRequest request) {
        dataScope.assertAdmin();
        StayApply apply = requireApply(request.applyId());
        LoginUser approver = dataScope.currentUser();
        dataScope.assertRegionAccess(apply.getRegionId());
        if (!approver.isSuperAdmin() && !approver.getRegionId().equals(apply.getRegionId())) {
            throw BizException.of(ResultCode.APPROVE_ONLY_REGION,
                    "你只能审批自己负责区域（%s）的申请".formatted(approver.getRegionId()));
        }

        ApplyStateMachine.assertTransition(apply.getStatus(), ApplyStatus.APPROVED, "审批通过");

        DormBed bed = bedMapper.selectById(request.bedId());
        if (bed == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "床位不存在");
        }
        if (!bed.getRegionId().equals(apply.getRegionId())) {
            throw BizException.param("所选床位不属于该申请的目的地区域");
        }
        DormRoom room = roomMapper.selectById(request.roomId());
        if (room == null) {
            throw BizException.of(ResultCode.ROOM_NOT_FOUND);
        }
        if (!bed.getRoomId().equals(room.getId())) {
            throw BizException.param("床位与房间不匹配");
        }
        if (room.getStatus() != RoomStatus.OPEN) {
            throw BizException.of(ResultCode.ROOM_CLOSED,
                    "房间 %s 当前状态为「%s」，不可分配".formatted(room.getRoomNo(), room.getStatus().getLabel()));
        }

        // 关键：把"并发抢同一张床"收敛成串行；即使锁失效，条件更新也不会超卖
        lock.executeWithLock(RedisKeys.assignLock(bed.getId()), 3000, Duration.ofSeconds(15), () -> {
            int occupied = bedMapper.occupyBed(bed.getId(), apply.getId());
            if (occupied == 0) {
                throw BizException.of(ResultCode.BED_OCCUPIED,
                        "床位 %s%s 刚被别人占用，请重新选择".formatted(room.getRoomNo(), bed.getBedNo()));
            }
            return null;
        });

        StayApply update = new StayApply();
        update.setId(apply.getId());
        update.setStatus(ApplyStatus.APPROVED);
        update.setRoomId(room.getId());
        update.setBedId(bed.getId());
        update.setAssignTime(LocalDateTime.now());
        update.setApproverId(approver.getUserId());
        update.setApproverName(approver.getRealName());
        update.setApproveTime(LocalDateTime.now());
        update.setApproveRemark(request.remark());
        update.setExpireTime(DateUtil.expireTimeOf(apply.getCheckInDate()));
        update.setVersion(apply.getVersion());
        int affected = applyMapper.updateById(update);
        if (affected == 0) {
            throw BizException.of(ResultCode.CONCURRENT_CONFLICT);
        }

        StayOrder order = createOrder(apply, room, bed);
        outboxService.publish(EVENT_APPLY_APPROVED, "APPLY", apply.getId(), Map.of(
                "applyNo", apply.getApplyNo(),
                "orderNo", order.getOrderNo(),
                "employeeId", apply.getEmployeeId(),
                "employeeName", apply.getEmployeeName(),
                "roomNo", room.getRoomNo(),
                "bedNo", bed.getBedNo(),
                "approverName", approver.getRealName()));
        auditService.success(AuditEnums.Module.APPLY, "APPLY_APPROVE", "APPLY", apply.getId(),
                Map.of("status", apply.getStatus().name()),
                Map.of("status", "APPROVED", "roomNo", room.getRoomNo(), "bedNo", bed.getBedNo()));

        log.info("申请审批通过 applyNo={} room={} bed={} approver={}",
                apply.getApplyNo(), room.getRoomNo(), bed.getBedNo(), approver.getRealName());
        return detail(apply.getId());
    }

    private StayOrder createOrder(StayApply apply, DormRoom room, DormBed bed) {
        StayOrder order = new StayOrder();
        order.setOrderNo(bizNoGenerator.next(BizNoGenerator.TYPE_ORDER));
        order.setApplyId(apply.getId());
        order.setApplyNo(apply.getApplyNo());
        order.setEmployeeId(apply.getEmployeeId());
        order.setEmployeeName(apply.getEmployeeName());
        order.setRegionId(apply.getRegionId());
        order.setBuildingId(room.getBuildingId());
        order.setRoomId(room.getId());
        order.setBedId(bed.getId());
        order.setCheckInDate(apply.getCheckInDate());
        order.setCheckOutDate(apply.getCheckOutDate());
        order.setNights(apply.getNights());
        order.setStatus(com.overseas.dorm.common.enums.OrderStatus.RESERVED);
        order.setCurrency(room.getCurrency());
        order.setSource(apply.getApplySource());
        order.setAmount(amountOf(room.getPricePerNight(), apply.getNights()));
        order.setRemark("系统自动生成（申请 %s）".formatted(apply.getApplyNo()));
        orderMapper.insert(order);
        return order;
    }

    private static BigDecimal amountOf(BigDecimal pricePerNight, Integer nights) {
        if (pricePerNight == null || nights == null) {
            return BigDecimal.ZERO;
        }
        return pricePerNight.multiply(BigDecimal.valueOf(nights));
    }

    // ================================================================ 驳回 / 取消

    @Transactional
    public ApplyVO reject(ApplyRejectRequest request) {
        dataScope.assertAdmin();
        StayApply apply = requireApply(request.applyId());
        dataScope.assertRegionAccess(apply.getRegionId());
        ApplyStateMachine.assertTransition(apply.getStatus(), ApplyStatus.REJECTED, "驳回");

        StayApply update = new StayApply();
        update.setId(apply.getId());
        update.setStatus(ApplyStatus.REJECTED);
        update.setApproverId(dataScope.currentUser().getUserId());
        update.setApproverName(dataScope.currentUser().getRealName());
        update.setApproveTime(LocalDateTime.now());
        update.setRejectReason(request.reason());
        update.setVersion(apply.getVersion());
        if (applyMapper.updateById(update) == 0) {
            throw BizException.of(ResultCode.CONCURRENT_CONFLICT);
        }
        outboxService.publish(EVENT_APPLY_REJECTED, "APPLY", apply.getId(), Map.of(
                "applyNo", apply.getApplyNo(),
                "employeeId", apply.getEmployeeId(),
                "reason", request.reason()));
        auditService.success(AuditEnums.Module.APPLY, "APPLY_REJECT", "APPLY", apply.getId(),
                Map.of("status", apply.getStatus().name()),
                Map.of("status", "REJECTED", "reason", request.reason()));
        return detail(apply.getId());
    }

    /** 取消申请：员工只能取消自己的；管理员可取消本区域的。已分配的床位会被释放。 */
    @Transactional
    public ApplyVO cancel(ApplyCancelRequest request) {
        StayApply apply = requireApply(request.applyId());
        LoginUser user = UserContext.require();
        if (user.isAdmin()) {
            dataScope.assertRegionAccess(apply.getRegionId());
        } else {
            dataScope.assertSelfOrAdmin(apply.getEmployeeId());
        }
        ApplyStateMachine.assertTransition(apply.getStatus(), ApplyStatus.CANCELLED, "取消");

        releaseBedIfAny(apply);
        StayApply update = new StayApply();
        update.setId(apply.getId());
        update.setStatus(ApplyStatus.CANCELLED);
        update.setCancelTime(LocalDateTime.now());
        update.setVersion(apply.getVersion());
        if (applyMapper.updateById(update) == 0) {
            throw BizException.of(ResultCode.CONCURRENT_CONFLICT);
        }
        cancelOrderIfAny(apply.getId(), request.reason());
        outboxService.publish(EVENT_APPLY_CANCELLED, "APPLY", apply.getId(), Map.of(
                "applyNo", apply.getApplyNo(),
                "employeeId", apply.getEmployeeId(),
                "reason", String.valueOf(request.reason())));
        auditService.success(AuditEnums.Module.APPLY, "APPLY_CANCEL", "APPLY", apply.getId(),
                Map.of("status", apply.getStatus().name()),
                Map.of("status", "CANCELLED", "reason", String.valueOf(request.reason())));
        return detail(apply.getId());
    }

    // ================================================================ 入住 / 退房

    /**
     * 办理入住。
     *
     * <p>时间窗口校验：只能在 [入住日, 失效时间] 之间办理。
     * 太早办理会让"床位实际可用"与系统记录不一致；
     * 太晚则不办理，直接判定失效释放床位（把房子留给真正需要的人）。
     */
    @Transactional
    public ApplyVO checkIn(CheckInRequest request) {
        StayApply apply = requireApply(request.applyId());
        LoginUser user = UserContext.require();
        if (user.isAdmin()) {
            dataScope.assertRegionAccess(apply.getRegionId());
        } else {
            dataScope.assertSelfOrAdmin(apply.getEmployeeId());
        }
        ApplyStateMachine.assertTransition(apply.getStatus(), ApplyStatus.CHECKED_IN, "办理入住");

        LocalDate today = DateUtil.today();
        if (today.isBefore(apply.getCheckInDate())) {
            throw BizException.of(ResultCode.CHECK_IN_TOO_EARLY,
                    "你的入住日期是 %s，当天才能办理入住".formatted(apply.getCheckInDate()));
        }
        if (apply.getExpireTime() != null && LocalDateTime.now().isAfter(apply.getExpireTime())) {
            // 超期：顺手把它置为失效，避免用户反复尝试
            expireInternal(apply, "超过最晚入住时间，自动失效");
            throw BizException.of(ResultCode.CHECK_IN_TOO_LATE,
                    "该申请已于 %s 失效，请重新提交".formatted(apply.getExpireTime()));
        }

        LocalDateTime now = LocalDateTime.now();
        StayApply update = new StayApply();
        update.setId(apply.getId());
        update.setStatus(ApplyStatus.CHECKED_IN);
        update.setCheckInTime(now);
        update.setVersion(apply.getVersion());
        if (applyMapper.updateById(update) == 0) {
            throw BizException.of(ResultCode.CONCURRENT_CONFLICT);
        }

        StayOrder order = orderMapper.selectOne(new LambdaQueryWrapper<StayOrder>()
                .eq(StayOrder::getApplyId, apply.getId()).last("LIMIT 1"));
        if (order != null) {
            StayOrder orderUpdate = new StayOrder();
            orderUpdate.setId(order.getId());
            orderUpdate.setStatus(com.overseas.dorm.common.enums.OrderStatus.CHECKED_IN);
            orderUpdate.setRealCheckInTime(now);
            orderUpdate.setVersion(order.getVersion());
            orderMapper.updateById(orderUpdate);
        }
        outboxService.publish(EVENT_ORDER_CHECKED_IN, "ORDER", order == null ? apply.getId() : order.getId(),
                Map.of("applyNo", apply.getApplyNo(),
                        "orderNo", order == null ? "" : order.getOrderNo(),
                        "employeeId", apply.getEmployeeId(),
                        "roomId", String.valueOf(apply.getRoomId()),
                        "bedId", String.valueOf(apply.getBedId())));
        auditService.success(AuditEnums.Module.APPLY, "APPLY_CHECK_IN", "APPLY", apply.getId(),
                Map.of("status", apply.getStatus().name()), Map.of("status", "CHECKED_IN"));
        log.info("办理入住成功 applyNo={} employee={}", apply.getApplyNo(), apply.getEmployeeName());
        return detail(apply.getId());
    }

    /**
     * 办理退房。
     *
     * <p>三个要点：
     * <ol>
     *   <li><b>释放床位</b>：{@code releaseBedIfAny} 只在占用者是自己时才释放，
     *       避免把已经被重新分配出去的床误释放；</li>
     *   <li><b>费用按实际住宿天数重算</b>：提前退房不应按原计划收费；</li>
     *   <li><b>提前退房必须填原因</b>：这是财务核算与差旅合规的输入。</li>
     * </ol>
     */
    @Transactional
    public ApplyVO checkOut(CheckOutRequest request) {
        StayApply apply = resolveApplyForCheckOut(request);
        LoginUser user = UserContext.require();
        if (user.isAdmin()) {
            dataScope.assertRegionAccess(apply.getRegionId());
        } else {
            dataScope.assertSelfOrAdmin(apply.getEmployeeId());
        }
        if (apply.getStatus() != ApplyStatus.CHECKED_IN) {
            throw BizException.of(ResultCode.CHECK_OUT_NOT_DUE,
                    "该申请当前状态为「%s」，尚未办理入住或已退房".formatted(
                            apply.getStatus() == null ? "未知" : apply.getStatus().getLabel()));
        }
        ApplyStateMachine.assertTransition(apply.getStatus(), ApplyStatus.CHECKED_OUT, "办理退房");

        LocalDate today = DateUtil.today();
        boolean early = today.isBefore(apply.getCheckOutDate());
        if (early && (request.earlyCheckOutReason() == null || request.earlyCheckOutReason().isBlank())) {
            throw BizException.of(ResultCode.PARAM_MISSING,
                    "提前退房（原定 %s）需要填写原因".formatted(apply.getCheckOutDate()));
        }

        LocalDateTime now = LocalDateTime.now();
        releaseBedIfAny(apply);

        StayApply update = new StayApply();
        update.setId(apply.getId());
        update.setStatus(ApplyStatus.CHECKED_OUT);
        update.setCheckOutTime(now);
        update.setVersion(apply.getVersion());
        if (applyMapper.updateById(update) == 0) {
            throw BizException.of(ResultCode.CONCURRENT_CONFLICT);
        }

        StayOrder order = orderMapper.selectOne(new LambdaQueryWrapper<StayOrder>()
                .eq(StayOrder::getApplyId, apply.getId()).last("LIMIT 1"));
        if (order != null) {
            int actualNights = actualNights(order, today);
            DormRoom room = roomMapper.selectById(order.getRoomId());
            StayOrder orderUpdate = new StayOrder();
            orderUpdate.setId(order.getId());
            orderUpdate.setStatus(com.overseas.dorm.common.enums.OrderStatus.CHECKED_OUT);
            orderUpdate.setRealCheckOutTime(now);
            orderUpdate.setEarlyCheckOutReason(early ? request.earlyCheckOutReason() : null);
            orderUpdate.setNights(actualNights);
            orderUpdate.setAmount(amountOf(room == null ? null : room.getPricePerNight(), actualNights));
            orderUpdate.setVersion(order.getVersion());
            orderMapper.updateById(orderUpdate);
        }
        outboxService.publish(EVENT_ORDER_CHECKED_OUT, "ORDER", order == null ? apply.getId() : order.getId(),
                Map.of("applyNo", apply.getApplyNo(),
                        "orderNo", order == null ? "" : order.getOrderNo(),
                        "employeeId", apply.getEmployeeId(),
                        "early", String.valueOf(early),
                        "reason", String.valueOf(request.earlyCheckOutReason())));
        auditService.success(AuditEnums.Module.APPLY, "APPLY_CHECK_OUT", "APPLY", apply.getId(),
                Map.of("status", "CHECKED_IN"),
                Map.of("status", "CHECKED_OUT", "early", String.valueOf(early)));
        log.info("办理退房成功 applyNo={} 提前={} ", apply.getApplyNo(), early);
        return detail(apply.getId());
    }

    private StayApply resolveApplyForCheckOut(CheckOutRequest request) {
        if (request.orderId() != null) {
            StayOrder order = orderMapper.selectById(request.orderId());
            if (order == null) {
                throw BizException.of(ResultCode.ORDER_NOT_FOUND);
            }
            return requireApply(order.getApplyId());
        }
        if (request.applyId() != null) {
            return requireApply(request.applyId());
        }
        throw BizException.of(ResultCode.PARAM_MISSING, "请提供订单号或申请号");
    }

    /** 实际住宿晚数：退房日 - 入住日，至少 1 晚。 */
    private int actualNights(StayOrder order, LocalDate checkOutDate) {
        LocalDate start = order.getRealCheckInTime() != null
                ? order.getRealCheckInTime().toLocalDate() : order.getCheckInDate();
        long nights = java.time.temporal.ChronoUnit.DAYS.between(start, checkOutDate);
        return (int) Math.max(nights, 1);
    }

    // ================================================================ 换房

    /**
     * 换房 / 重新分配床位。
     *
     * <p>顺序至关重要：<b>先占新床，再放旧床</b>。
     * 反过来的话，在"释放旧床"和"占用新床"之间，
     * 新床可能被别人抢走，员工就变成了彻底没有床位。
     */
    @Transactional
    public ApplyVO reassign(ApplyReassignRequest request) {
        dataScope.assertAdmin();
        StayApply apply = requireApply(request.applyId());
        dataScope.assertRegionAccess(apply.getRegionId());
        if (apply.getStatus() != ApplyStatus.APPROVED && apply.getStatus() != ApplyStatus.CHECKED_IN) {
            throw BizException.of(ResultCode.APPLY_STATE_ILLEGAL,
                    "只有「已通过」或「已入住」的申请可以换房，当前状态：%s".formatted(apply.getStatus().getLabel()));
        }
        DormBed newBed = bedMapper.selectById(request.bedId());
        if (newBed == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "床位不存在");
        }
        DormRoom newRoom = roomMapper.selectById(newBed.getRoomId());
        if (newRoom == null || newRoom.getStatus() != RoomStatus.OPEN) {
            throw BizException.of(ResultCode.ROOM_CLOSED, "目标房间当前不可用");
        }
        if (newBed.getId().equals(apply.getBedId())) {
            throw BizException.param("新床位与当前床位相同，无需换房");
        }

        lock.executeWithLock(RedisKeys.assignLock(newBed.getId()), 3000, Duration.ofSeconds(15), () -> {
            if (bedMapper.occupyBed(newBed.getId(), apply.getId()) == 0) {
                throw BizException.of(ResultCode.BED_OCCUPIED, "目标床位已被占用，请重新选择");
            }
            return null;
        });

        Long oldBedId = apply.getBedId();
        StayApply update = new StayApply();
        update.setId(apply.getId());
        update.setRoomId(newRoom.getId());
        update.setBedId(newBed.getId());
        update.setAssignTime(LocalDateTime.now());
        update.setVersion(apply.getVersion());
        if (applyMapper.updateById(update) == 0) {
            throw BizException.of(ResultCode.CONCURRENT_CONFLICT);
        }
        if (oldBedId != null) {
            bedMapper.releaseBed(oldBedId, apply.getId());
        }
        StayOrder order = orderMapper.selectOne(new LambdaQueryWrapper<StayOrder>()
                .eq(StayOrder::getApplyId, apply.getId()).last("LIMIT 1"));
        if (order != null) {
            StayOrder orderUpdate = new StayOrder();
            orderUpdate.setId(order.getId());
            orderUpdate.setRoomId(newRoom.getId());
            orderUpdate.setBedId(newBed.getId());
            orderUpdate.setBuildingId(newRoom.getBuildingId());
            orderUpdate.setVersion(order.getVersion());
            orderMapper.updateById(orderUpdate);
        }
        auditService.success(AuditEnums.Module.APPLY, "APPLY_REASSIGN", "APPLY", apply.getId(),
                Map.of("bedId", String.valueOf(oldBedId)),
                Map.of("bedId", String.valueOf(newBed.getId()), "reason", String.valueOf(request.reason())));
        return detail(apply.getId());
    }

    // ================================================================ 失效扫描

    /**
     * 定时失效扫描：审批通过但超期未入住的申请自动失效并释放床位。
     *
     * <p>为什么要定时任务而不是"查询时过滤"：如果不主动释放，
     * 床位会被永久占住 —— 员工没来住，房间也订不了，这是资源上的实际损失。
     */
    @Scheduled(fixedDelayString = "${dorm.apply.expire-scan-ms:300000}")
    @Transactional
    public void expireOverdueApplies() {
        List<StayApply> expired = applyMapper.selectExpired(
                ApplyStatus.APPROVED.name(), BusinessLimits.SCHEDULED_SCAN_BATCH);
        if (expired.isEmpty()) {
            return;
        }
        int count = 0;
        for (StayApply apply : expired) {
            try {
                expireInternal(apply, "超过最晚入住时间，系统自动失效");
                count++;
            } catch (Exception e) {
                log.warn("申请失效处理失败 applyNo={}: {}", apply.getApplyNo(), e.getMessage());
            }
        }
        if (count > 0) {
            log.info("定时失效扫描完成，共处理 {} 条申请", count);
        }
    }

    private void expireInternal(StayApply apply, String reason) {
        releaseBedIfAny(apply);
        StayApply update = new StayApply();
        update.setId(apply.getId());
        update.setStatus(ApplyStatus.EXPIRED);
        update.setVersion(apply.getVersion());
        applyMapper.updateById(update);
        cancelOrderIfAny(apply.getId(), reason);
        outboxService.publish(EVENT_APPLY_EXPIRED, "APPLY", apply.getId(), Map.of(
                "applyNo", apply.getApplyNo(),
                "employeeId", apply.getEmployeeId(),
                "reason", reason));
        auditService.success(AuditEnums.Module.APPLY, "APPLY_EXPIRE", "APPLY", apply.getId(),
                Map.of("status", "APPROVED"), Map.of("status", "EXPIRED", "reason", reason));
    }

    private void releaseBedIfAny(StayApply apply) {
        if (apply.getBedId() == null) {
            return;
        }
        int released = bedMapper.releaseBed(apply.getBedId(), apply.getId());
        if (released > 0) {
            log.info("释放床位 bedId={} applyNo={}", apply.getBedId(), apply.getApplyNo());
        }
    }

    private void cancelOrderIfAny(Long applyId, String reason) {
        StayOrder order = orderMapper.selectOne(new LambdaQueryWrapper<StayOrder>()
                .eq(StayOrder::getApplyId, applyId).last("LIMIT 1"));
        if (order == null || order.getStatus().isFinal()) {
            return;
        }
        com.overseas.dorm.common.statemachine.OrderStateMachine.assertTransition(
                order.getStatus(), com.overseas.dorm.common.enums.OrderStatus.CANCELLED, "取消订单");
        StayOrder update = new StayOrder();
        update.setId(order.getId());
        update.setStatus(com.overseas.dorm.common.enums.OrderStatus.CANCELLED);
        update.setRemark(reason);
        update.setVersion(order.getVersion());
        orderMapper.updateById(update);
    }

    // ================================================================ 查询

    /**
     * 申请分页查询。
     *
     * <p>数据权限的三条分支：
     * <ul>
     *   <li>员工 —— 强制只查自己的（不允许通过传 employeeId 看别人）；</li>
     *   <li>区域管理员 —— 只能查本区域；</li>
     *   <li>总管理员 —— 全部，可按区域筛选。</li>
     * </ul>
     */
    public PageResult<ApplyVO> page(long current, long size, ApplyStatus status, Long regionId,
                                    Long employeeId, String keyword, LocalDate from, LocalDate to) {
        LoginUser user = UserContext.require();
        LambdaQueryWrapper<StayApply> wrapper = new LambdaQueryWrapper<>();
        if (user.isEmployee()) {
            wrapper.eq(StayApply::getEmployeeId, user.getUserId());
        } else {
            if (user.isSuperAdmin()) {
                wrapper.eq(regionId != null, StayApply::getRegionId, regionId);
            } else {
                wrapper.eq(StayApply::getRegionId, user.getRegionId());
            }
            wrapper.eq(employeeId != null, StayApply::getEmployeeId, employeeId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(StayApply::getApplyNo, keyword)
                    .or().like(StayApply::getEmployeeName, keyword)
                    .or().like(StayApply::getDestCity, keyword)
                    .or().like(StayApply::getTripNo, keyword));
        }
        wrapper.eq(status != null, StayApply::getStatus, status)
                .ge(from != null, StayApply::getCheckInDate, from)
                .le(to != null, StayApply::getCheckOutDate, to)
                .orderByDesc(StayApply::getCreateTime);

        var page = applyMapper.selectPage(PageMapper.page(current, size), wrapper);
        return PageMapper.of(page, a -> buildVO(a, false));
    }

    public ApplyVO detail(Long id) {
        StayApply apply = requireApply(id);
        LoginUser user = UserContext.require();
        if (user.isEmployee()) {
            dataScope.assertSelfOrAdmin(apply.getEmployeeId());
        } else {
            dataScope.assertRegionAccess(apply.getRegionId());
        }
        return buildVO(apply, true);
    }

    private ApplyVO buildVO(StayApply apply, boolean withTimeline) {
        ApplyVO vo = ApplyVO.of(apply);
        Region region = regionMapper.selectById(apply.getRegionId());
        vo.setRegionName(region == null ? null : region.getName());
        if (apply.getBuildingId() != null) {
            DormBuilding building = buildingMapper.selectById(apply.getBuildingId());
            vo.setBuildingName(building == null ? null : building.getName());
        }
        if (apply.getRoomId() != null) {
            DormRoom room = roomMapper.selectById(apply.getRoomId());
            if (room != null) {
                vo.setRoomNo(room.getRoomNo());
                vo.setPricePerNight(room.getPricePerNight());
                vo.setCurrency(room.getCurrency());
                if (vo.getBuildingName() == null) {
                    DormBuilding building = buildingMapper.selectById(room.getBuildingId());
                    vo.setBuildingName(building == null ? null : building.getName());
                }
            }
        }
        if (apply.getBedId() != null) {
            DormBed bed = bedMapper.selectById(apply.getBedId());
            vo.setBedNo(bed == null ? null : bed.getBedNo());
        }
        StayOrder order = orderMapper.selectOne(new LambdaQueryWrapper<StayOrder>()
                .eq(StayOrder::getApplyId, apply.getId()).last("LIMIT 1"));
        if (order != null) {
            vo.setOrderNo(order.getOrderNo());
        }
        vo.setAllowedActions(new ArrayList<>(ApplyStateMachine.allowedActions(apply.getStatus())));
        if (withTimeline) {
            vo.setTimeline(buildTimeline(apply, order));
        }
        return vo;
    }

    /**
     * 构建进度时间轴。
     *
     * <p>服务端生成而不是前端拼：保证「状态」与「时间轴」永远一致，
     * 并且可以被 AI 助手直接复用为「我的申请到哪一步了」的回答素材。
     */
    private List<ApplyVO.TimelineItem> buildTimeline(StayApply apply, StayOrder order) {
        List<ApplyVO.TimelineItem> items = new ArrayList<>();
        items.add(ApplyVO.TimelineItem.of("SUBMITTED", "提交申请", "#409EFF",
                apply.getCreateTime(), apply.getEmployeeName(),
                "来源：" + (apply.getApplySource() == null ? "网页端" : apply.getApplySource().getLabel())));

        switch (apply.getStatus()) {
            case PENDING -> items.add(ApplyVO.TimelineItem.of("PENDING", "等待区域管理员审批", "#E6A23C",
                    null, null, "通常在 24 小时内处理"));
            case APPROVED, CHECKED_IN, CHECKED_OUT -> {
                items.add(ApplyVO.TimelineItem.of("APPROVED", "审批通过并分配房间", "#409EFF",
                        apply.getApproveTime(), apply.getApproverName(),
                        "房间 %s / 床位 %s".formatted(
                                apply.getRoomId() == null ? "-" : roomLabel(apply.getRoomId()),
                                apply.getBedId() == null ? "-" : bedLabel(apply.getBedId()))));
                if (apply.getStatus() == ApplyStatus.APPROVED) {
                    items.add(ApplyVO.TimelineItem.of("AWAIT_CHECK_IN", "等待办理入住", "#E6A23C",
                            null, null, "请在 %s 前办理入住，否则申请将失效".formatted(apply.getExpireTime())));
                }
                if (apply.getStatus() == ApplyStatus.CHECKED_IN || apply.getStatus() == ApplyStatus.CHECKED_OUT) {
                    items.add(ApplyVO.TimelineItem.of("CHECKED_IN", "已办理入住", "#67C23A",
                            apply.getCheckInTime(), apply.getEmployeeName(), null));
                }
                if (apply.getStatus() == ApplyStatus.CHECKED_OUT) {
                    items.add(ApplyVO.TimelineItem.of("CHECKED_OUT", "已办理退房", "#909399",
                            apply.getCheckOutTime(), apply.getEmployeeName(),
                            order != null && order.getEarlyCheckOutReason() != null
                                    ? "提前退房：" + order.getEarlyCheckOutReason() : null));
                }
            }
            case REJECTED -> items.add(ApplyVO.TimelineItem.of("REJECTED", "申请被驳回", "#F56C6C",
                    apply.getApproveTime(), apply.getApproverName(), apply.getRejectReason()));
            case CANCELLED -> items.add(ApplyVO.TimelineItem.of("CANCELLED", "申请已取消", "#909399",
                    apply.getCancelTime(), apply.getEmployeeName(), null));
            case EXPIRED -> items.add(ApplyVO.TimelineItem.of("EXPIRED", "申请已失效", "#909399",
                    apply.getExpireTime(), "系统", "超过最晚入住时间未办理入住"));
            default -> {
            }
        }
        return items;
    }

    private String roomLabel(Long roomId) {
        DormRoom room = roomMapper.selectById(roomId);
        return room == null ? String.valueOf(roomId) : room.getRoomNo();
    }

    private String bedLabel(Long bedId) {
        DormBed bed = bedMapper.selectById(bedId);
        return bed == null ? String.valueOf(bedId) : bed.getBedNo();
    }

    public StayApply requireApply(Long id) {
        StayApply apply = applyMapper.selectById(id);
        if (apply == null) {
            throw BizException.of(ResultCode.APPLY_NOT_FOUND);
        }
        return apply;
    }

    /** 员工端「我的行程」：当前与未来的有效入住。 */
    public List<ApplyVO> myUpcoming() {
        Long userId = UserContext.requireUserId();
        List<StayApply> list = applyMapper.selectList(new LambdaQueryWrapper<StayApply>()
                .eq(StayApply::getEmployeeId, userId)
                .in(StayApply::getStatus, ApplyStatus.PENDING, ApplyStatus.APPROVED, ApplyStatus.CHECKED_IN)
                .orderByAsc(StayApply::getCheckInDate));
        return list.stream().map(a -> buildVO(a, false)).toList();
    }

    /**
     * 统计各状态数量（看板 / Agent 工具）。
     *
     * <p>返回 {@code Map<String, Long>}：状态名是动态键（用 Map 合适），
     * 但值恒为数量，声明成 Long 后调用方不再需要强转与空值判断。
     * 汇总逻辑与订单侧共用 {@link StatusSummaryBuilder}，避免两处实现漂移。
     */
    public Map<String, Long> statusSummary(Long regionId) {
        // 【H7 修复】粒度不再在这里手写（"superadmin 才用请求参数、其它角色用本区域"），
        // 而是走 DataScope.statisticsScope —— 员工必须只看自己的数据。
        // 原来的写法让员工拿到本区域所有人的申请量（实测 TOTAL 与总管理员一致）。
        // 规则集中在一处，以后新增统计入口只要调它就不会再漏。
        DataScope.StatsScope scope = dataScope.statisticsScope(regionId);
        return StatusSummaryBuilder.build(
                applyMapper.countByStatus(scope.regionId(), scope.employeeId()));
    }
}
