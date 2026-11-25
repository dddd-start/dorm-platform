package com.overseas.dorm.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.overseas.dorm.common.api.ResultCode;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.BedStatus;
import com.overseas.dorm.common.exception.BizException;
import com.overseas.dorm.domain.dto.RegionSaveRequest;
import com.overseas.dorm.domain.entity.DormBed;
import com.overseas.dorm.domain.entity.DormRoom;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.domain.entity.SysUser;
import com.overseas.dorm.domain.mapper.DormBedMapper;
import com.overseas.dorm.domain.mapper.DormRoomMapper;
import com.overseas.dorm.domain.mapper.RegionMapper;
import com.overseas.dorm.domain.mapper.SysUserMapper;
import com.overseas.dorm.domain.support.DataScope;
import com.overseas.dorm.domain.vo.BedSummaryVO;
import com.overseas.dorm.domain.vo.RegionOptionVO;
import com.overseas.dorm.domain.vo.RegionVO;
import com.overseas.dorm.domain.vo.RoomStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域服务。
 *
 * <p>区域是数据权限的载体，因此这里做了两层保护：
 * <ol>
 *   <li>读接口对区域管理员只返回<b>本区域</b>（连"看见别的区域存在"都不允许，
 *       避免通过区域列表探测组织结构）；</li>
 *   <li>写接口仅总管理员可用，区域增减会影响权限模型本身，属于系统级配置。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionService {

    private final RegionMapper regionMapper;
    private final DormRoomMapper roomMapper;
    private final DormBedMapper bedMapper;
    private final SysUserMapper userMapper;
    private final DataScope dataScope;
    private final AuditService auditService;

    /** 区域列表（含房源概要）。区域管理员只能看到自己的区域。 */
    public List<RegionVO> list(Integer status, boolean withStats) {
        LambdaQueryWrapper<Region> wrapper = new LambdaQueryWrapper<>();
        Long visibleRegion = dataScope.visibleRegionId();
        if (visibleRegion != null) {
            wrapper.eq(Region::getId, visibleRegion);
        }
        wrapper.eq(status != null, Region::getStatus, status).orderByAsc(Region::getId);
        List<Region> regions = regionMapper.selectList(wrapper);

        Map<Long, String> managers = managerNames();
        Map<Long, RoomStatsVO> stats = withStats ? roomStats() : Map.of();

        return regions.stream().map(r -> {
            RegionVO vo = RegionVO.of(r);
            vo.setManagerName(managers.get(r.getManagerId()));
            // 用具体类型取值：字段名靠编译器保证，不再有"别名拼错 → 静默为 0"的风险
            RoomStatsVO s = stats.get(r.getId());
            if (s != null) {
                vo.setRoomCount(orZero(s.getRoomCount()));
                vo.setBedCount(orZero(s.getBedCount()));
                vo.setIdleBedCount(orZero(s.getIdleBedCount()));
            }
            return vo;
        }).toList();
    }

    /**
     * 下拉选项（登录后任何角色都可用，用于「选择目的地区域」）。
     *
     * <p>返回类型是 {@link RegionOptionVO} 而不是 {@code Map<String, Object>}：
     * 后者在 Swagger 里只能显示成"一个对象"，前端无法据此生成类型，
     * 接口契约实际上退化成了"看代码才知道有哪些字段"。
     */
    public List<RegionOptionVO> options() {
        // 【H5 修复】数据权限统一在 service 层收口。
        // 改造前这里无条件返回全部启用区域，而同一份数据在 detail() 里是拦的
        // （"你负责的区域是「1001」，无权访问其它区域的数据"）——
        // 同一份区域数据"详情接口拦、options 不拦"，属实现遗漏。
        // 在 service 层修而不是在控制器修，是为了让 REST、Agent 工具、
        // 以及任何后续调用方都自动获得同一套权限判定，而不是逐个入口打补丁
        // （逐入口打补丁正是这些缺口产生的原因）。
        Long visibleRegion = dataScope.visibleRegionId();
        return regionMapper.selectList(new LambdaQueryWrapper<Region>()
                        .eq(Region::getStatus, 1)
                        .eq(visibleRegion != null, Region::getId, visibleRegion)
                        .orderByAsc(Region::getId))
                .stream()
                .map(r -> new RegionOptionVO(
                        r.getId(),
                        r.getCode(),
                        r.getName(),
                        blankToEmpty(r.getCountry()),
                        blankToEmpty(r.getCurrency())))
                .toList();
    }

    public RegionVO detail(Long id) {
        Region region = regionMapper.selectById(id);
        if (region == null) {
            throw BizException.of(ResultCode.REGION_NOT_FOUND);
        }
        dataScope.assertRegionAccess(id);
        return RegionVO.of(region);
    }

    @Transactional
    public RegionVO save(RegionSaveRequest request) {
        dataScope.assertSuperAdmin();
        if (request.managerId() != null) {
            SysUser manager = userMapper.selectById(request.managerId());
            if (manager == null) {
                throw BizException.of(ResultCode.NOT_FOUND, "指定的区域管理员不存在");
            }
            if (!manager.getRole().isAdmin()) {
                throw BizException.param("指定的负责人不是管理员角色");
            }
        }
        Long duplicate = regionMapper.selectCount(new LambdaQueryWrapper<Region>()
                .eq(Region::getCode, request.code())
                .ne(request.id() != null, Region::getId, request.id()));
        if (duplicate != null && duplicate > 0) {
            throw BizException.of(ResultCode.DUPLICATE_DATA, "区域编码已存在：" + request.code());
        }

        if (request.id() == null) {
            Region region = new Region();
            applyRequest(region, request);
            regionMapper.insert(region);
            auditService.success(AuditEnums.Module.REGION, "REGION_CREATE", "REGION", region.getId(), null, region);
            return RegionVO.of(region);
        }

        Region exists = regionMapper.selectById(request.id());
        if (exists == null) {
            throw BizException.of(ResultCode.REGION_NOT_FOUND);
        }
        Region update = new Region();
        update.setId(request.id());
        applyRequest(update, request);
        update.setVersion(exists.getVersion());
        regionMapper.updateById(update);
        auditService.success(AuditEnums.Module.REGION, "REGION_UPDATE", "REGION", request.id(), exists,
                regionMapper.selectById(request.id()));
        return RegionVO.of(regionMapper.selectById(request.id()));
    }

    private void applyRequest(Region region, RegionSaveRequest request) {
        region.setCode(request.code());
        region.setName(request.name());
        region.setNameEn(request.nameEn());
        region.setCountry(request.country());
        region.setCountryCode(request.countryCode());
        region.setTimezone(request.timezone());
        region.setCurrency(request.currency());
        region.setManagerId(request.managerId());
        region.setStatus(request.status() == null ? 1 : request.status());
        region.setRemark(request.remark());
    }

    /**
     * 启用/停用区域。
     *
     * <p>停用区域会级联把该区域的房间置为 CLOSED，* 否则会出现「区域停用了但员工还能查到并申请该区域房源」的矛盾状态。
     * 这是一次显式的、可审计的级联操作，而不是靠查询时加条件去兜。
     */
    @Transactional
    public void toggleStatus(Long id, Integer status) {
        dataScope.assertSuperAdmin();
        Region region = regionMapper.selectById(id);
        if (region == null) {
            throw BizException.of(ResultCode.REGION_NOT_FOUND);
        }
        Region update = new Region();
        update.setId(id);
        update.setStatus(status);
        update.setVersion(region.getVersion());
        regionMapper.updateById(update);

        if (status != null && status == 0) {
            int closed = roomMapper.update(null, new LambdaUpdateWrapper<DormRoom>()
                    .eq(DormRoom::getRegionId, id)
                    .eq(DormRoom::getStatus, com.overseas.dorm.common.enums.RoomStatus.OPEN)
                    .set(DormRoom::getStatus, com.overseas.dorm.common.enums.RoomStatus.CLOSED));
            log.info("区域停用，级联关闭房间 regionId={} closedRooms={}", id, closed);
        }
        auditService.success(AuditEnums.Module.REGION, status == 1 ? "REGION_ENABLE" : "REGION_DISABLE",
                "REGION", id, Map.of("status", region.getStatus()), Map.of("status", status));
    }

    private Map<Long, String> managerNames() {
        Map<Long, String> map = new HashMap<>();
        for (SysUser user : userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, com.overseas.dorm.common.enums.UserRole.REGION_ADMIN))) {
            map.put(user.getId(), user.getRealName());
        }
        return map;
    }

    /** 按 regionId 索引的区域房源统计，供列表接口一次性补齐统计字段（避免 N+1 查询）。 */
    private Map<Long, RoomStatsVO> roomStats() {
        Map<Long, RoomStatsVO> result = new HashMap<>();
        for (RoomStatsVO row : roomMapper.statisticsByRegion(null)) {
            if (row.getRegionId() != null) {
                result.put(row.getRegionId(), row);
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

    /** 校验区域存在且启用（供其它服务复用）。 */
    public Region requireEnabled(Long id) {
        Region region = regionMapper.selectById(id);
        if (region == null) {
            throw BizException.of(ResultCode.REGION_NOT_FOUND);
        }
        if (region.getStatus() == null || region.getStatus() == 0) {
            throw BizException.param("区域「%s」已停用，暂不可申请".formatted(region.getName()));
        }
        return region;
    }

    /** 区域床位资源快速统计（Agent 工具会用到）。 */
    public BedSummaryVO bedSummary(Long regionId) {
        // 【H5 修复】补区域越界校验：改造前任何登录用户都能读取任意区域的床位数，
        // 实测 zhangwei(区域 1001) 可以拿到 1002/1003/1004 的 idleBeds。
        // regionId 为空时表示"当前可见范围"，不校验（总管理员即全部区域）。
        if (regionId != null) {
            dataScope.assertRegionAccess(regionId);
        }
        long idle = bedMapper.selectCount(new LambdaQueryWrapper<DormBed>()
                .eq(regionId != null, DormBed::getRegionId, regionId)
                .eq(DormBed::getStatus, BedStatus.IDLE));
        long total = bedMapper.selectCount(new LambdaQueryWrapper<DormBed>()
                .eq(regionId != null, DormBed::getRegionId, regionId)
                .ne(DormBed::getStatus, BedStatus.DISABLED));
        return new BedSummaryVO(regionId, idle, total);
    }
}
