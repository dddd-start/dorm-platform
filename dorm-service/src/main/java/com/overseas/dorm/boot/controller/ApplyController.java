package com.overseas.dorm.boot.controller;

import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.common.statemachine.ApplyStateMachine;
import com.overseas.dorm.boot.security.RequireRole;
import com.overseas.dorm.domain.dto.ApplyApproveRequest;
import com.overseas.dorm.domain.dto.ApplyCancelRequest;
import com.overseas.dorm.domain.dto.ApplyCreateRequest;
import com.overseas.dorm.domain.dto.ApplyReassignRequest;
import com.overseas.dorm.domain.dto.ApplyRejectRequest;
import com.overseas.dorm.domain.dto.CheckInRequest;
import com.overseas.dorm.domain.dto.CheckOutRequest;
import com.overseas.dorm.domain.service.ApplyService;
import com.overseas.dorm.domain.vo.ApplyVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 入住申请接口，全系统的业务主流程。
 *
 * <pre>
 *   员工：POST /api/applies            提交申请
 *   管理员：POST /api/applies/approve   审批通过 + 分配床位（原子）
 *   管理员：POST /api/applies/reject    驳回
 *   员工/管理员：POST /api/applies/cancel   取消（释放床位）
 *   员工：POST /api/applies/check-in    到达后办理入住
 *   员工：POST /api/applies/check-out   到期办理退房（释放床位）
 *   管理员：POST /api/applies/reassign  换房
 * </pre>
 */
@Tag(name = "06-入住申请", description = "申请、审批、分房、入住、退房、换房")
@RestController
@RequestMapping("/api/applies")
@RequiredArgsConstructor
public class ApplyController {

    private final ApplyService applyService;

    @Operation(summary = "提交入住申请",
            description = "idempotencyKey 为幂等键：同一键重复提交只会产生一张申请单")
    @PostMapping
    public Result<ApplyVO> create(@Valid @RequestBody ApplyCreateRequest request) {
        return Result.ok(applyService.create(request, ApplySource.WEB));
    }

    @Operation(summary = "申请分页",
            description = "员工只能看到自己的；区域管理员只看本区域；总管理员可跨区域")
    @GetMapping
    public Result<PageResult<ApplyVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) ApplyStatus status,
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(applyService.page(current, size, status, regionId, employeeId, keyword, from, to));
    }

    @Operation(summary = "申请详情（含进度时间轴与可执行动作）")
    @GetMapping("/{id}")
    public Result<ApplyVO> detail(@PathVariable Long id) {
        return Result.ok(applyService.detail(id));
    }

    @Operation(summary = "我的待办行程", description = "当前与未来有效的入住安排")
    @GetMapping("/mine/upcoming")
    public Result<List<ApplyVO>> myUpcoming() {
        return Result.ok(applyService.myUpcoming());
    }

    @Operation(summary = "审批通过并分配床位",
            description = "审批与分房是原子的：不允许出现「已通过但没有床位」的申请")
    @PostMapping("/approve")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<ApplyVO> approve(@Valid @RequestBody ApplyApproveRequest request) {
        return Result.ok(applyService.approve(request));
    }

    @Operation(summary = "驳回申请")
    @PostMapping("/reject")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<ApplyVO> reject(@Valid @RequestBody ApplyRejectRequest request) {
        return Result.ok(applyService.reject(request));
    }

    @Operation(summary = "取消申请", description = "已分配床位的会自动释放")
    @PostMapping("/cancel")
    public Result<ApplyVO> cancel(@Valid @RequestBody ApplyCancelRequest request) {
        return Result.ok(applyService.cancel(request));
    }

    @Operation(summary = "办理入住", description = "仅在入住日当天至失效时间之间可办理")
    @PostMapping("/check-in")
    public Result<ApplyVO> checkIn(@Valid @RequestBody CheckInRequest request) {
        return Result.ok(applyService.checkIn(request));
    }

    @Operation(summary = "办理退房", description = "提前退房需填写原因，费用按实际住宿晚数重算")
    @PostMapping("/check-out")
    public Result<ApplyVO> checkOut(@Valid @RequestBody CheckOutRequest request) {
        return Result.ok(applyService.checkOut(request));
    }

    @Operation(summary = "换房/重新分配床位", description = "先占新床再放旧床，避免出现无床可住的中间态")
    @PostMapping("/reassign")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<ApplyVO> reassign(@Valid @RequestBody ApplyReassignRequest request) {
        return Result.ok(applyService.reassign(request));
    }

    /** 申请页所需的字典：状态、来源、以及各状态下允许的动作。 */
    public record ApplyMetaVO(
            List<com.overseas.dorm.common.api.OptionVO> statuses,
            List<com.overseas.dorm.common.api.OptionVO> sources,
            Map<String, java.util.Set<String>> actions) {
    }

    @Operation(summary = "申请状态枚举与允许动作")
    @GetMapping("/meta")
    public Result<ApplyMetaVO> meta() {
        // actions 的键是状态名（动态键），因此这里用 Map；值是该状态允许的动作列表。
        // 动作集合由 ApplyStateMachine 唯一定义，前端只做展示，不做权限判断。
        Map<String, java.util.Set<String>> actions = new java.util.LinkedHashMap<>();
        actions.put(ApplyStatus.PENDING.name(), ApplyStateMachine.allowedActions(ApplyStatus.PENDING));
        actions.put(ApplyStatus.APPROVED.name(), ApplyStateMachine.allowedActions(ApplyStatus.APPROVED));
        actions.put(ApplyStatus.CHECKED_IN.name(), ApplyStateMachine.allowedActions(ApplyStatus.CHECKED_IN));
        return Result.ok(new ApplyMetaVO(ApplyStatus.options(), ApplySource.options(), actions));
    }

    /**
     * 申请状态统计。
     *
     * <p>返回 {@code Map<String, Long>} 是<b>有意为之</b>：状态名是动态键
     * （由库里实际存在的状态决定），硬造固定字段会得到一堆永远为 0 的字段。
     * 值类型已收紧为 {@code Long}，动态键用 Map 正确，"值为 Object"才是问题。
     * 键名与 {@code ApplyStatus} 枚举一致，并额外包含一个 {@code TOTAL} 项。
     */
    @Operation(summary = "申请状态统计")
    @GetMapping("/statistics")
    public Result<Map<String, Long>> statistics(@RequestParam(required = false) Long regionId) {
        return Result.ok(applyService.statusSummary(regionId));
    }
}
