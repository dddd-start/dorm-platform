package com.overseas.dorm.boot.controller;

import com.overseas.dorm.domain.vo.BatchResultVO;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.RoomStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.common.enums.UserRole;
import com.overseas.dorm.boot.security.RequireRole;
import com.overseas.dorm.domain.dto.BedStatusRequest;
import com.overseas.dorm.domain.dto.RoomAvailabilityRow;
import com.overseas.dorm.domain.dto.RoomSaveRequest;
import com.overseas.dorm.domain.dto.RoomStatusRequest;
import com.overseas.dorm.domain.service.RoomService;
import com.overseas.dorm.domain.vo.RoomVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 房间与床位接口。
 *
 * <p>其中 {@code /api/rooms/availability} 是员工与 AI 助手查房的核心接口。
 *
 * <p><b>可订口径（H8/H9 修复后）</b>：{@code availableBeds} 表示<b>此刻真正能被分配</b>
 * 的床位数，与审批写路径 {@code occupyBed(WHERE status='IDLE')} 完全一致，* 因此"接口说可订"等价于"审批一定能分配成功"，前端据此启用预订按钮是安全的。
 *
 * <p>日期参数用于确定入住窗口，并额外给出 {@code bookableBeds}（该区间内<b>时间上</b>
 * 不冲突的床位数，表示"届时会有房"）。它<b>不代表此刻可分配</b>：
 * 一张当前被占用、对方下个月退房的床会算进 {@code bookableBeds}，
 * 但走审批时会被 {@code occupyBed} 拒绝。旧实现把两者混为一谈，
 * 实测出现 76（宣称可订）vs 59（真能分配）的落差，详见 {@code DormRoomMapper.xml}。
 */
@Tag(name = "05-房间与床位", description = "房源维护、可用性检索、床位管理")
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @Operation(summary = "房间分页（管理端）")
    @GetMapping
    public Result<PageResult<RoomVO>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Long buildingId,
                                          @RequestParam(required = false) Long regionId,
                                          @RequestParam(required = false) RoomType roomType,
                                          @RequestParam(required = false) RoomStatus status) {
        return Result.ok(roomService.page(current, size, keyword, buildingId, regionId, roomType, status));
    }

    @Operation(summary = "房源可用性检索",
            description = "按区域/公寓/城市/房型/性别与入住退房日期检索可订房间。"
                    + "availableBeds 恒为「此刻可被分配的床位数」（与审批分配口径一致）；"
                    + "bookableBeds 为指定日期区间内时间上不冲突的床位数（仅作提示）。")
    @GetMapping("/availability")
    public Result<List<RoomAvailabilityRow>> availability(
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) RoomType roomType,
            @RequestParam(required = false) GenderLimit gender,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate,
            @RequestParam(defaultValue = "1") int minBeds) {
        return Result.ok(roomService.searchAvailability(regionId, buildingId, city, roomType,
                gender, checkInDate, checkOutDate, minBeds));
    }

    @Operation(summary = "房间详情（含床位明细）")
    @GetMapping("/{id}")
    public Result<RoomVO> detail(@PathVariable Long id) {
        return Result.ok(roomService.detail(id));
    }

    @Operation(summary = "房间床位明细", description = "含每个床位当前占用者与起止日期，分房界面使用")
    @GetMapping("/{id}/beds")
    public Result<List<RoomAvailabilityRow.BedDetail>> beds(@PathVariable Long id) {
        return Result.ok(roomService.bedDetails(id));
    }

    @Operation(summary = "新增/编辑房间", description = "床位按房型容量自动增减；缩容时不允许减少在住床位")
    @PostMapping
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<RoomVO> save(@Valid @RequestBody RoomSaveRequest request) {
        return Result.ok(roomService.save(request));
    }

    @Operation(summary = "批量开关房/置维修",
            description = "关闭前会检查是否仍有床位被占用；返回逐间的成功与失败原因")
    @PutMapping("/status")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<BatchResultVO> changeStatus(@Valid @RequestBody RoomStatusRequest request) {
        return Result.ok(roomService.changeRoomStatus(request));
    }

    @Operation(summary = "批量启用/停用床位")
    @PutMapping("/beds/status")
    @RequireRole({UserRole.SUPER_ADMIN, UserRole.REGION_ADMIN})
    public Result<BatchResultVO> changeBedStatus(@Valid @RequestBody BedStatusRequest request) {
        return Result.ok(roomService.changeBedStatus(request));
    }
}
