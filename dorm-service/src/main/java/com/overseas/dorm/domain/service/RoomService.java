package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.BedStatus;
import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.RoomStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.common.util.DateUtil;
import com.overseas.dorm.domain.dto.BedStatusRequest;
import com.overseas.dorm.domain.dto.RoomAvailabilityRow;
import com.overseas.dorm.domain.dto.RoomSaveRequest;
import com.overseas.dorm.domain.dto.RoomStatusRequest;
import com.overseas.dorm.domain.entity.DormBed;
import com.overseas.dorm.domain.entity.DormBuilding;
import com.overseas.dorm.domain.entity.DormRoom;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.mapper.DormBedMapper;
import com.overseas.dorm.domain.mapper.DormBuildingMapper;
import com.overseas.dorm.domain.mapper.DormRoomMapper;
import com.overseas.dorm.domain.mapper.RegionMapper;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.support.PageMapper;
import com.overseas.dorm.domain.vo.BatchResultVO;
import com.overseas.dorm.domain.vo.RoomStatsVO;
import com.overseas.dorm.domain.vo.RoomVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间与床位服务。
 *
 * <p>「房间」与「床位」的关系是本系统的核心数据模型：
 * 房间承载价格、房型、性别限制等属性；床位是分配与占用的实际单元。
 * 因此房间的 CRUD 必须同步维护床位集合，否则会出现
 * 「四人间只有 2 张床」或「床位指向已变更房型的房间」这类脏数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final DormRoomMapper roomMapper;
    private final DormBedMapper bedMapper;
    private final DormBuildingMapper buildingMapper;
    private final RegionMapper regionMapper;
    private final DataScope dataScope;

    /**
     * 用于"批量接口逐条独立事务"的模板。
     *
     * <p>由 Spring Boot 的 {@code TransactionAutoConfiguration} 自动提供
     * （存在唯一的 {@link PlatformTransactionManager} 时即注册该 Bean），
     * 因此不需要在本项目里再声明一次。
     */
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    // ------------------------------------------------------------------ 查询

    public PageResult<RoomVO> page(long current, long size, String keyword, Long buildingId,
                                  Long regionId, RoomType roomType, RoomStatus status) {
        LambdaQueryWrapper<DormRoom> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(DormRoom::getRoomNo, keyword)
                    .or().like(DormRoom::getRemark, keyword));
        }
        wrapper.eq(buildingId != null, DormRoom::getBuildingId, buildingId)
                .eq(roomType != null, DormRoom::getRoomType, roomType)
                .eq(status != null, DormRoom::getStatus, status);
        if (regionId != null) {
            wrapper.eq(DormRoom::getRegionId, regionId);
        }
        dataScope.applyRegion(wrapper, DormRoom::getRegionId);
        wrapper.orderByAsc(DormRoom::getBuildingId).orderByAsc(DormRoom::getFloorNo).orderByAsc(DormRoom::getRoomNo);

        Map<Long, String> buildingNames = buildingNames();
        Map<Long, String> regionNames = regionNames();
        return PageMapper.of(roomMapper.selectPage(PageMapper.page(current, size), wrapper), room -> {
            RoomVO vo = RoomVO.of(room);
            vo.setBuildingName(buildingNames.get(room.getBuildingId()));
            vo.setRegionName(regionNames.get(room.getRegionId()));
            return vo;
        });
    }

    /** 房间详情（含床位明细）。 */
    public RoomVO detail(Long id) {
        DormRoom room = requireRoom(id);
        dataScope.assertRegionAccess(room.getRegionId());
        return toDetailVO(room);
    }

    private RoomVO toDetailVO(DormRoom room) {
        RoomVO vo = RoomVO.of(room);
        vo.setBuildingName(buildingNames().get(room.getBuildingId()));
        vo.setRegionName(regionNames().get(room.getRegionId()));
        List<DormBed> beds = bedMapper.selectByRoom(room.getId());
        List<RoomVO.BedVO> bedVOs = new ArrayList<>();
        int idle = 0;
        int occupied = 0;
        int disabled = 0;
        for (DormBed bed : beds) {
            RoomVO.BedVO bv = new RoomVO.BedVO();
            bv.setId(bed.getId());
            bv.setBedNo(bed.getBedNo());
            bv.setStatus(bed.getStatus());
            bv.setStatusLabel(bed.getStatus() == null ? null : bed.getStatus().getLabel());
            bv.setCurrentApplyId(bed.getCurrentApplyId());
            bedVOs.add(bv);
            if (bed.getStatus() == BedStatus.IDLE) {
                idle++;
            } else if (bed.getStatus() == BedStatus.OCCUPIED) {
                occupied++;
            } else {
                disabled++;
            }
        }
        vo.setBeds(bedVOs);
        vo.setIdleBeds(idle);
        vo.setOccupiedBeds(occupied);
        vo.setDisabledBeds(disabled);
        return vo;
    }

    /**
     * 房源可用性检索 —— 员工端查房与 AI 助手查房共用同一个方法。
     *
     * <p>这个方法被 Agent 工具直接调用，因此它必须满足两个额外要求：
     * <ol>
     *   <li>返回结构化数据（而不是一段中文），让模型自己去组织语言；</li>
     *   <li>不做任何写操作，可被安全地反复调用（幂等）。</li>
     * </ol>
     */
    public List<RoomAvailabilityRow> searchAvailability(Long regionId, Long buildingId, String city,
                                                        RoomType roomType, GenderLimit gender,
                                                        LocalDate checkIn, LocalDate checkOut, int minBeds) {
        if (checkIn != null && checkOut != null) {
            DateUtil.nightsBetween(checkIn, checkOut);
        }
        // 数据权限：员工可以查任何区域的房源（出差目的地不受限），
        // 但区域管理员仍受自己的区域约束，避免越权看到其它区域的内部备注。
        Long scoped = regionId;
        Long visible = dataScope.visibleRegionId();
        if (visible != null && !dataScope.currentUser().isEmployee()) {
            scoped = visible;
        }

        List<RoomAvailabilityRow> rows = roomMapper.selectAvailability(scoped, buildingId, city,
                roomType == null ? null : roomType.name(),
                gender == null ? null : gender.name(),
                checkIn, checkOut, Math.max(minBeds, 1));

        Integer nights = (checkIn != null && checkOut != null) ? DateUtil.nightsBetween(checkIn, checkOut) : null;
        for (RoomAvailabilityRow row : rows) {
            row.setNights(nights);
            if (row.getRoomType() != null) {
                RoomType t = RoomType.valueOf(row.getRoomType());
                row.setRoomTypeLabel(t.getLabel());
            }
        }
        return rows;
    }

    /** 房间床位明细（分房界面 / 换房界面）。 */
    public List<RoomAvailabilityRow.BedDetail> bedDetails(Long roomId) {
        DormRoom room = requireRoom(roomId);
        dataScope.assertRegionAccess(room.getRegionId());
        return roomMapper.selectBedDetails(roomId);
    }

    // ------------------------------------------------------------------ 写操作

    @Transactional
    public RoomVO save(RoomSaveRequest request) {
        dataScope.assertAdmin();
        DormBuilding building = buildingMapper.selectById(request.buildingId());
        if (building == null) {
            throw BizException.of(ResultCode.BUILDING_NOT_FOUND);
        }
        dataScope.assertRegionAccess(building.getRegionId());

        Long duplicate = roomMapper.selectCount(new LambdaQueryWrapper<DormRoom>()
                .eq(DormRoom::getBuildingId, request.buildingId())
                .eq(DormRoom::getRoomNo, request.roomNo())
                .ne(request.id() != null, DormRoom::getId, request.id()));
        if (duplicate != null && duplicate > 0) {
            throw BizException.of(ResultCode.DUPLICATE_DATA,
                    "该公寓下已存在房间号 " + request.roomNo());
        }

        if (request.id() == null) {
            DormRoom room = new DormRoom();
            apply(room, request, building);
            room.setStatus(RoomStatus.OPEN);
            roomMapper.insert(room);
            syncBeds(room, 0);
            auditService.success(AuditEnums.Module.ROOM, "ROOM_CREATE", "ROOM", room.getId(), null, room);
            return toDetailVO(roomMapper.selectById(room.getId()));
        }

        DormRoom exists = requireRoom(request.id());
        dataScope.assertRegionAccess(exists.getRegionId());
        int oldCapacity = exists.getCapacity() == null ? 0 : exists.getCapacity();

        DormRoom update = new DormRoom();
        update.setId(request.id());
        apply(update, request, building);
        update.setVersion(exists.getVersion());
        int affected = roomMapper.updateById(update);
        if (affected == 0) {
            throw BizException.of(ResultCode.CONCURRENT_CONFLICT);
        }
        syncBeds(roomMapper.selectById(request.id()), oldCapacity);
        auditService.success(AuditEnums.Module.ROOM, "ROOM_UPDATE", "ROOM", request.id(), exists,
                roomMapper.selectById(request.id()));
        return toDetailVO(roomMapper.selectById(request.id()));
    }

    /**
     * 按房型同步床位。
     *
     * <p>缩容时的安全规则：只能减少<b>空闲</b>床位。
     * 如果有人正住着 B 床而你把房间从双人间改成单人间，
     * 系统必须拒绝 —— 而不是悄悄把在住员工"变没"。
     */
    private void syncBeds(DormRoom room, int oldCapacity) {
        int capacity = room.getRoomType() == null ? 1 : room.getRoomType().getCapacity();
        List<DormBed> existing = bedMapper.selectByRoom(room.getId());
        int current = existing.size();

        if (capacity > current) {
            for (int i = current; i < capacity; i++) {
                DormBed bed = new DormBed();
                bed.setRoomId(room.getId());
                bed.setBuildingId(room.getBuildingId());
                bed.setRegionId(room.getRegionId());
                bed.setBedNo(String.valueOf((char) ('A' + i)));
                bed.setStatus(BedStatus.IDLE);
                bedMapper.insert(bed);
            }
            log.info("房间扩容，新增床位 roomId={} {} -> {}", room.getId(), current, capacity);
        } else if (capacity < current) {
            List<DormBed> removable = existing.stream()
                    .filter(b -> b.getStatus() == BedStatus.IDLE)
                    .sorted((a, b) -> b.getBedNo().compareTo(a.getBedNo()))
                    .limit(current - capacity)
                    .toList();
            if (removable.size() < current - capacity) {
                throw BizException.of(ResultCode.ILLEGAL_STATE,
                        "该房间有 %d 个床位正在使用，无法缩减为 %d 个床位。请先安排换房"
                                .formatted(existing.size() - removable.size(), capacity));
            }
            removable.forEach(b -> bedMapper.deleteById(b.getId()));
            log.info("房间缩容，删除空闲床位 roomId={} {} -> {}", room.getId(), current, capacity);
        }
        // 房间容量字段与房型对齐
        if (!java.util.Objects.equals(room.getCapacity(), capacity)) {
            DormRoom fix = new DormRoom();
            fix.setId(room.getId());
            fix.setCapacity(capacity);
            roomMapper.updateById(fix);
        }
    }

    /**
     * 批量开关房 / 置为维修。
     *
     * <p>关闭房间前会检查是否还有未完成的入住：有在住/已预订的申请时不允许直接关闭，
     * 必须先把人安排走。这是「关房」这个操作最容易出事的地方。
     */
    /**
     * 批量开关房 / 置为维修。
     *
     * <p>关闭房间前会检查是否还有未完成的入住：有在住/已预订的申请时不允许直接关闭，
     * 必须先把人安排走。这是「关房」这个操作最容易出事的地方。
     *
     * <h3>为什么不用一个方法级事务包住整个批量操作</h3>
     * 批量接口的语义是"<b>能做的先做，做不了的回报原因</b>"。如果整个方法共用一个事务，
     * 那么只要最后一条因为任何原因抛出非业务异常，前面已经成功生效的改动会一起回滚，
     * 而返回值里却报告了 successCount = N —— <b>报告与事实不一致，这类问题极难排查</b>。
     *
     * <p>因此这里改为<b>每条记录一个独立事务</b>（{@link TransactionTemplate} 显式控制边界）：
     * 单条内部的"改房间状态 + 同步床位状态 + 写审计"保持原子，
     * 条与条之间互不影响。批量操作正确的粒度就是"单个聚合根"。
     */
    public BatchResultVO changeRoomStatus(RoomStatusRequest request) {
        dataScope.assertAdmin();
        int success = 0;
        List<BatchResultVO.BatchFailure> failures = new ArrayList<>();
        for (Long roomId : request.roomIds()) {
            try {
                boolean done = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    DormRoom room = requireRoom(roomId);
                    dataScope.assertRegionAccess(room.getRegionId());
                    if (request.status() != RoomStatus.OPEN) {
                        long occupied = bedMapper.selectCount(new LambdaQueryWrapper<DormBed>()
                                .eq(DormBed::getRoomId, roomId)
                                .eq(DormBed::getStatus, BedStatus.OCCUPIED));
                        if (occupied > 0) {
                            throw BizException.param(
                                    "该房间还有 %d 个床位被占用，请先安排换房".formatted(occupied));
                        }
                    }
                    DormRoom update = new DormRoom();
                    update.setId(roomId);
                    update.setStatus(request.status());
                    update.setVersion(room.getVersion());
                    if (roomMapper.updateById(update) == 0) {
                        throw BizException.param("数据已被他人修改，请刷新后重试");
                    }
                    // 房间关闭时把空闲床位一并停用、重新开放时恢复（床位与房间状态必须一致）
                    if (request.status() == RoomStatus.CLOSED) {
                        updateBedStatusByRoom(roomId, BedStatus.IDLE, BedStatus.DISABLED);
                    } else if (request.status() == RoomStatus.OPEN) {
                        updateBedStatusByRoom(roomId, BedStatus.DISABLED, BedStatus.IDLE);
                    }
                    auditService.success(AuditEnums.Module.ROOM,
                            AuditEnums.ROOM_STATUS_ACTION_PREFIX + request.status().name(),
                            AuditEnums.TARGET_ROOM, roomId,
                            Map.of("status", room.getStatus()),
                            Map.of("status", request.status().name(),
                                    "remark", String.valueOf(request.remark())));
                    return true;
                }));
                if (Boolean.TRUE.equals(done)) {
                    success++;
                }
            } catch (BizException e) {
                failures.add(BatchResultVO.roomFailure(roomId, null, e.getMessage()));
            }
        }
        return new BatchResultVO(success, failures.size(), failures);
    }

    /** 批量启用/停用床位（同样按"每条一个事务"处理，理由见 {@link #changeRoomStatus}）。 */
    public BatchResultVO changeBedStatus(BedStatusRequest request) {
        dataScope.assertAdmin();
        int success = 0;
        List<BatchResultVO.BatchFailure> failures = new ArrayList<>();
        for (Long bedId : request.bedIds()) {
            try {
                boolean done = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    DormBed bed = bedMapper.selectById(bedId);
                    if (bed == null) {
                        throw BizException.param("床位不存在");
                    }
                    dataScope.assertRegionAccess(bed.getRegionId());
                    if (request.status() == BedStatus.DISABLED && bed.getStatus() == BedStatus.OCCUPIED) {
                        throw BizException.param("床位正在使用，不能停用");
                    }
                    DormBed update = new DormBed();
                    update.setId(bedId);
                    update.setStatus(request.status());
                    update.setVersion(bed.getVersion());
                    if (bedMapper.updateById(update) == 0) {
                        throw BizException.param("数据已被他人修改");
                    }
                    auditService.success(AuditEnums.Module.BED,
                            AuditEnums.BED_STATUS_ACTION_PREFIX + request.status().name(),
                            AuditEnums.TARGET_BED, bedId,
                            Map.of("status", bed.getStatus()),
                            Map.of("status", request.status().name()));
                    return true;
                }));
                if (Boolean.TRUE.equals(done)) {
                    success++;
                }
            } catch (BizException e) {
                failures.add(BatchResultVO.bedFailure(bedId, e.getMessage()));
            }
        }
        return new BatchResultVO(success, failures.size(), failures);
    }

    /** 按房间批量迁移床位状态（关房/开房时保持房间与床位状态一致）。 */
    private void updateBedStatusByRoom(Long roomId, BedStatus from, BedStatus to) {
        bedMapper.update(null, new LambdaUpdateWrapper<DormBed>()
                .eq(DormBed::getRoomId, roomId)
                .eq(DormBed::getStatus, from)
                .set(DormBed::getStatus, to));
    }

    private void apply(DormRoom room, RoomSaveRequest request, DormBuilding building) {
        room.setBuildingId(request.buildingId());
        room.setRegionId(building.getRegionId());
        room.setRoomNo(request.roomNo());
        room.setFloorNo(request.floorNo());
        room.setRoomType(request.roomType());
        room.setCapacity(request.roomType() == null ? 1 : request.roomType().getCapacity());
        room.setGenderLimit(request.genderLimit());
        room.setAreaSqm(request.areaSqm());
        room.setPricePerNight(request.pricePerNight());
        if (request.currency() != null && !request.currency().isBlank()) {
            room.setCurrency(request.currency());
        } else {
            // 未显式指定币种时，继承区域配置的币种（避免出现"新加坡的房间标着 EUR"）
            Region region = regionMapper.selectById(building.getRegionId());
            room.setCurrency(region == null ? null : region.getCurrency());
        }
        room.setFacilities(request.facilities());
        room.setRemark(request.remark());
    }

    public DormRoom requireRoom(Long id) {
        DormRoom room = roomMapper.selectById(id);
        if (room == null) {
            throw BizException.of(ResultCode.ROOM_NOT_FOUND);
        }
        return room;
    }

    private Map<Long, String> buildingNames() {
        Map<Long, String> map = new HashMap<>();
        for (DormBuilding b : buildingMapper.selectList(new LambdaQueryWrapper<>())) {
            map.put(b.getId(), b.getName());
        }
        return map;
    }

    private Map<Long, String> regionNames() {
        Map<Long, String> map = new HashMap<>();
        for (Region r : regionMapper.selectList(new LambdaQueryWrapper<>())) {
            map.put(r.getId(), r.getName());
        }
        return map;
    }

    /** 房间概览统计（区域维度），供看板与 Agent 使用。 */
    public List<RoomStatsVO> statisticsByRegion(Long regionId) {
        Long visible = dataScope.visibleRegionId();
        // 区域管理员一律被收窄到自己的区域：请求参数不能越权，这是数据权限的基本要求
        Long scoped = visible != null ? visible : regionId;
        return roomMapper.statisticsByRegion(scoped);
    }
}
