package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.overseas.dorm.common.api.PageResult;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.RoomStatus;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.domain.dto.BuildingSaveRequest;
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
import com.overseas.dorm.domain.vo.BuildingOptionVO;
import com.overseas.dorm.domain.vo.BuildingStatsVO;
import com.overseas.dorm.domain.vo.BuildingVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公寓服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingService {

    private final DormBuildingMapper buildingMapper;
    private final DormRoomMapper roomMapper;
    private final DormBedMapper bedMapper;
    private final RegionMapper regionMapper;
    private final DataScope dataScope;
    private final AuditService auditService;

    public PageResult<BuildingVO> page(long current, long size, String keyword, Long regionId, Integer status) {
        LambdaQueryWrapper<DormBuilding> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(DormBuilding::getName, keyword)
                    .or().like(DormBuilding::getCode, keyword)
                    .or().like(DormBuilding::getCity, keyword));
        }
        wrapper.eq(regionId != null, DormBuilding::getRegionId, regionId)
                .eq(status != null, DormBuilding::getStatus, status);
        // 数据权限：区域管理员只看本区域
        dataScope.applyRegion(wrapper, DormBuilding::getRegionId);
        wrapper.orderByAsc(DormBuilding::getRegionId).orderByAsc(DormBuilding::getCode);

        Map<Long, String> regionNames = regionNames();
        Map<Long, BuildingStatsVO> stats = buildingStats(regionId);
        return PageMapper.of(buildingMapper.selectPage(PageMapper.page(current, size), wrapper), b -> {
            BuildingVO vo = BuildingVO.of(b);
            vo.setRegionName(regionNames.get(b.getRegionId()));
            BuildingStatsVO s = stats.get(b.getId());
            if (s != null) {
                vo.setRoomCount(orZero(s.getRoomCount()));
                vo.setBedCount(orZero(s.getBedCount()));
                vo.setIdleBedCount(orZero(s.getIdleBedCount()));
            }
            return vo;
        });
    }

    /** 下拉选项：员工查房源、管理员分房都要用。 */
    public List<BuildingOptionVO> options(Long regionId) {
        LambdaQueryWrapper<DormBuilding> wrapper = new LambdaQueryWrapper<DormBuilding>()
                .eq(DormBuilding::getStatus, 1)
                .eq(regionId != null, DormBuilding::getRegionId, regionId);
        dataScope.applyRegion(wrapper, DormBuilding::getRegionId);
        wrapper.orderByAsc(DormBuilding::getRegionId).orderByAsc(DormBuilding::getCode);
        return buildingMapper.selectList(wrapper).stream()
                .map(b -> new BuildingOptionVO(
                        b.getId(),
                        b.getRegionId(),
                        b.getCode(),
                        b.getName(),
                        blankToEmpty(b.getCity()),
                        blankToEmpty(b.getAddress())))
                .toList();
    }

    public BuildingVO detail(Long id) {
        DormBuilding building = buildingMapper.selectById(id);
        if (building == null) {
            throw BizException.of(ResultCode.BUILDING_NOT_FOUND);
        }
        dataScope.assertRegionAccess(building.getRegionId());
        BuildingVO vo = BuildingVO.of(building);
        vo.setRegionName(regionNames().get(building.getRegionId()));
        return vo;
    }

    @Transactional
    public BuildingVO save(BuildingSaveRequest request) {
        dataScope.assertAdmin();
        dataScope.assertRegionAccess(request.regionId());
        Region region = regionMapper.selectById(request.regionId());
        if (region == null) {
            throw BizException.of(ResultCode.REGION_NOT_FOUND);
        }
        Long duplicate = buildingMapper.selectCount(new LambdaQueryWrapper<DormBuilding>()
                .eq(DormBuilding::getCode, request.code())
                .ne(request.id() != null, DormBuilding::getId, request.id()));
        if (duplicate != null && duplicate > 0) {
            throw BizException.of(ResultCode.DUPLICATE_DATA, "公寓编码已存在：" + request.code());
        }

        if (request.id() == null) {
            DormBuilding building = new DormBuilding();
            apply(building, request);
            buildingMapper.insert(building);
            auditService.success(AuditEnums.Module.BUILDING, "BUILDING_CREATE", "BUILDING",
                    building.getId(), null, building);
            return detail(building.getId());
        }

        DormBuilding exists = buildingMapper.selectById(request.id());
        if (exists == null) {
            throw BizException.of(ResultCode.BUILDING_NOT_FOUND);
        }
        // 编辑路径必须**两次**校验：目标区域（上面已校验）与**原区域**。
        // 否则区域管理员可以把别区的公寓"改"到自己区域，等于凭空搬走他人数据。
        // 参考 RoomService.save 的同类校验，这里是单点遗漏，已补。
        dataScope.assertRegionAccess(exists.getRegionId());
        // 换区域时必须同步刷新房间的冗余 regionId，否则数据权限会失效
        boolean regionChanged = !exists.getRegionId().equals(request.regionId());

        DormBuilding update = new DormBuilding();
        update.setId(request.id());
        apply(update, request);
        update.setVersion(exists.getVersion());
        buildingMapper.updateById(update);

        if (regionChanged) {
            int moved = roomMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DormRoom>()
                    .eq(DormRoom::getBuildingId, request.id())
                    .set(DormRoom::getRegionId, request.regionId()));
            bedMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DormBed>()
                    .eq(DormBed::getBuildingId, request.id())
                    .set(DormBed::getRegionId, request.regionId()));
            log.info("公寓换区域，级联刷新房间/床位 regionId buildingId={} 影响房间 {} 间", request.id(), moved);
        }
        auditService.success(AuditEnums.Module.BUILDING, "BUILDING_UPDATE", "BUILDING", request.id(),
                exists, buildingMapper.selectById(request.id()));
        return detail(request.id());
    }

    @Transactional
    public void toggleStatus(Long id, Integer status) {
        dataScope.assertAdmin();
        DormBuilding building = buildingMapper.selectById(id);
        if (building == null) {
            throw BizException.of(ResultCode.BUILDING_NOT_FOUND);
        }
        dataScope.assertRegionAccess(building.getRegionId());
        DormBuilding update = new DormBuilding();
        update.setId(id);
        update.setStatus(status);
        update.setVersion(building.getVersion());
        buildingMapper.updateById(update);
        // 停用公寓 = 该公寓所有房间不再可分配
        if (status != null && status == 0) {
            roomMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DormRoom>()
                    .eq(DormRoom::getBuildingId, id)
                    .eq(DormRoom::getStatus, RoomStatus.OPEN)
                    .set(DormRoom::getStatus, RoomStatus.CLOSED));
        }
        auditService.success(AuditEnums.Module.BUILDING, status == 1 ? "BUILDING_ENABLE" : "BUILDING_DISABLE",
                "BUILDING", id, Map.of("status", building.getStatus()), Map.of("status", status));
    }

    private void apply(DormBuilding building, BuildingSaveRequest request) {
        building.setRegionId(request.regionId());
        building.setCode(request.code());
        building.setName(request.name());
        building.setCountry(request.country());
        building.setCity(request.city());
        building.setAddress(request.address());
        building.setContactName(request.contactName());
        building.setContactPhone(request.contactPhone());
        building.setStatus(request.status() == null ? 1 : request.status());
        building.setRemark(request.remark());
    }

    public Map<Long, String> regionNames() {
        Map<Long, String> map = new HashMap<>();
        for (Region r : regionMapper.selectList(new LambdaQueryWrapper<>())) {
            map.put(r.getId(), r.getName());
        }
        return map;
    }

    public Map<Long, DormBuilding> buildingMap(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DormBuilding> map = new HashMap<>();
        for (DormBuilding b : buildingMapper.selectBatchIds(ids)) {
            map.put(b.getId(), b);
        }
        return map;
    }

    /**
     * 按 buildingId 索引的公寓统计。
     *
     * <p>改为单次分组聚合（原先是对每栋楼各查一次空闲床位，典型的 N+1）。
     */
    private Map<Long, BuildingStatsVO> buildingStats(Long regionId) {
        Map<Long, BuildingStatsVO> result = new HashMap<>();
        for (BuildingStatsVO row : roomMapper.statisticsByBuilding(regionId)) {
            if (row.getBuildingId() != null) {
                result.put(row.getBuildingId(), row);
            }
        }
        return result;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
