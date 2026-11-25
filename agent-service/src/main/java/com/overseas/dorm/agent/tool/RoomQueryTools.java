package com.overseas.dorm.agent.tool;

import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.common.util.DateUtil;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.domain.dto.RoomAvailabilityRow;
import com.overseas.dorm.domain.entity.DormBuilding;
import com.overseas.dorm.domain.entity.DormRoom;
import com.overseas.dorm.domain.entity.Region;
import com.overseas.dorm.agent.client.DormServiceClient;


import com.overseas.dorm.domain.vo.BedSummaryVO;
import com.overseas.dorm.domain.vo.RegionOptionVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 房源查询工具集。
 *
 *
 * 日期参数有意用 {@code String} 而不是 {@code LocalDate}：
 * LangChain4j 需要为参数生成 JSON Schema，简单类型最稳妥；
 * 更重要的是，模型偶尔会传 "下周一" 或 "2026/10/20"，
 * 用 String 能在工具内给出明确的纠错提示（"日期格式应为 yyyy-MM-dd"），
 * 让模型自我修正，而不是抛一个它看不懂的解析异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomQueryTools {

    /**
     * 单次返回给模型的房间条数上限。
     *
     * <p>有意的产品化取舍：工具结果会全部进入模型上下文并<b>按 token 计费</b>，
     * 一次塞 136 条房间既让成本陡增，也不会提升回答质量（模型只需要几个可选项）。
     * 因此这里取前 N 条，并在结果里明确告知"还有多少间未列出"，让模型引导用户补充条件。
     */
    private static final int MAX_ROOMS_RETURNED = 12;

    private final DormServiceClient regionService;
    /** 数据权限：工具与 REST 必须用同一套范围判定。 */
    private final com.overseas.dorm.domain.support.DataScope dataScope;
    private final DormServiceClient buildingService;
    private final DormServiceClient roomService;

    /**
     * 工具结果的数据结构。
     *
     * <p>用 record 而不是 {@code Map<String, Object>}：工具结果是<b>给模型看的接口</b>，
     * 字段名就是模型理解数据的依据。用 Map 拼装时，一个键名笔误（{@code idleBeds}
     * 写成 {@code idleBed}）不会报错，模型会直接"看不见"这个字段，
     * 表现为回答里缺少信息，这类问题只能靠肉眼比对 JSON 才能发现。
     *
     * <p>record 还顺带保证了序列化顺序稳定，便于比对工具输出快照。
     */
    public record RegionWithBeds(
            Long id, String code, String name, String country, String currency,
            long idleBedsNow, long totalBeds) {
    }

    public record RegionOverviewItem(
            String regionName, Integer roomCount, Integer openRooms, Integer closedRooms,
            Integer maintenanceRooms, Integer beds, Integer idleBeds, Integer occupiedBeds) {
    }

    public record RegionList(List<RegionWithBeds> regions, int count) {
    }

    public record RegionOverview(List<RegionOverviewItem> regions) {
    }

    /**
     * 可订房间条目。
     *
     * <p>{@code availableBeds} = <b>此刻可被分配</b>的床位数（与审批分配的口径逐字一致，
     * 是"能不能订"的唯一依据）；{@code bookableBeds} = 查询日期区间内<b>时间上不冲突</b>
     * 的床位数，只代表"届时会有房"。两者可能不同（H8/H9：旧实现把它们混成一个字段，
     * 导致"查询说可订、审批却 3005"）。
     */
    public record RoomItemVO(
            Long roomId, String building, String city, String roomNo, String roomType,
            String genderLimit, Integer availableBeds, Integer bookableBeds,
            java.math.BigDecimal pricePerNight,
            String currency, String facilities, Integer nights, java.math.BigDecimal estimatedTotal) {
    }

    /** 房源检索结果。 */
    public record RoomSearchVO(
            String region, String regionCode, String checkInDate, String checkOutDate,
            int matchedRoomCount, int returnedRoomCount, List<RoomItemVO> rooms, String hint) {
    }

    /** 公寓详情。 */
    public record BuildingDetailVO(
            Long buildingId, String name, String code, String country, String city,
            String address, String contactName, String contactPhone, String status) {
    }

    /** 公寓列表（直接复用区域服务下发的选项对象，不再转手复制一遍）。 */
    public record BuildingListVO(List<com.overseas.dorm.domain.vo.BuildingOptionVO> buildings) {
    }

    /** 床位明细条目。 */
    public record BedItemVO(
            String bedNo, String status, String occupiedFrom, String occupiedTo, String occupant) {
    }

    /** 房间床位明细。 */
    public record RoomBedsVO(
            String roomNo, String roomType, java.math.BigDecimal pricePerNight,
            String currency, String status, List<BedItemVO> beds) {
    }

    @Tool("列出系统覆盖的全部海外区域（含所属国家与币种）。当用户没有明确说去哪个区域时，先调用它来确认可选范围。")
    public String listRegions() {
        // 数据权限：只返回当前用户可见的区域。
        // 改造前这里对 regionService.options() 无差别遍历并逐个查床位数，
        // 于是普通员工在对话里就能拿到全部 4 个区域的床位数据 ——
        // 绕过了 REST 层已经生效的数据权限（独立验证实测 listRegions 返回 4 个区域，
        // 而同一轮的 getRegionOverview 只返回本区域，两个工具口径不一致暴露了漏做过滤）。
        Long visibleRegion = dataScope.visibleRegionId();
        List<RegionWithBeds> data = new ArrayList<>();
        for (RegionOptionVO option : regionService.options()) {
            if (visibleRegion != null && !visibleRegion.equals(option.id())) {
                continue;
            }
            BedSummaryVO beds = regionService.bedSummary(option.id());
            data.add(new RegionWithBeds(option.id(), option.code(), option.name(),
                    option.country(), option.currency(),
                    beds.idleBeds(), beds.totalBeds()));
        }
        return JsonUtil.toJson(new RegionList(data, data.size()));
    }

    @Tool("""
            按区域和日期查询可入住房间。
            必须提供 regionCode（区域编码，如 SEA/EU/ME/NEA，可先用 listRegions 获取）。
            日期格式必须为 yyyy-MM-dd。若用户未给出具体日期，可先不传日期，
            此时返回的是「此刻空闲床位」的情况，但应提醒用户：真正的可订性需要具体日期。
            """)
    public String searchAvailableRooms(
            @P("区域编码，如 SEA、EU、ME、NEA") String regionCode,
            @P("入住日期，格式 yyyy-MM-dd，可为空") String checkInDate,
            @P("退房日期，格式 yyyy-MM-dd，可为空") String checkOutDate,
            @P("城市名称（可空），如 新加坡、迪拜、东京") String city,
            @P("房型偏好（可空）：SINGLE 单人间 / DOUBLE 双人间 / TRIPLE 三人间 / QUAD 四人间") String roomType,
            @P("入住人性别（可空）：MALE / FEMALE；用于过滤性别限制房间") String gender) {

        Region region = resolveRegion(regionCode);
        LocalDate checkIn = parseDate(checkInDate, "入住日期");
        LocalDate checkOut = parseDate(checkOutDate, "退房日期");
        if (checkIn != null && checkOut == null) {
            throw com.overseas.dorm.common.exception.BizException.forAgent(
                    com.overseas.dorm.common.api.ResultCode.PARAM_MISSING,
                    "还需要退房日期",
                    "用户只给了入住日期。请追问退房日期（格式 yyyy-MM-dd）后再查询。");
        }
        RoomType type = parseRoomType(roomType);
        GenderLimit genderLimit = parseGender(gender);

        List<RoomAvailabilityRow> rows = roomService.searchAvailability(region.getId(), null, city,
                type, genderLimit, checkIn, checkOut, 1);

        int limit = MAX_ROOMS_RETURNED;
        List<RoomItemVO> rooms = rows.subList(0, Math.min(limit, rows.size())).stream()
                .map(row -> new RoomItemVO(
                        row.getRoomId(), row.getBuildingName(), row.getCity(), row.getRoomNo(),
                        row.getRoomTypeLabel(), row.getGenderLimit(), row.getAvailableBeds(),
                        row.getBookableBeds(),
                        row.getPricePerNight(), row.getCurrency(), row.getFacilities(),
                        row.getNights(), row.getEstimatedAmount()))
                .toList();
        String hint = null;
        if (rows.isEmpty()) {
            hint = "该条件下没有可订房间。可以尝试：换区域、换个日期、不限房型，"
                    + "或让我列出该区域所有公寓。";
        } else if (rows.size() > limit) {
            hint = "还有 %d 间未列出。如需更精确的结果，请补充城市或房型条件。"
                    .formatted(rows.size() - limit);
        }
        return JsonUtil.toJson(new RoomSearchVO(
                region.getName(), region.getCode(),
                checkIn == null ? null : checkIn.toString(),
                checkOut == null ? null : checkOut.toString(),
                rows.size(), rooms.size(), rooms, hint));
    }

    @Tool("查询某栋公寓的详细信息（地址、前台联系方式、城市、开放房间数与空闲床位）。buildingId 可从 searchAvailableRooms 的结果或 listBuildings 获取。")
    public String getBuildingInfo(@P("公寓ID") Long buildingId) {
        DormBuilding building = buildingService.buildingMap(List.of(buildingId)).get(buildingId);
        if (building == null) {
            throw com.overseas.dorm.common.exception.BizException.forAgent(
                    com.overseas.dorm.common.api.ResultCode.BUILDING_NOT_FOUND,
                    "公寓不存在",
                    "该 buildingId 不存在。请先调用 listBuildings 或 searchAvailableRooms 获取有效 ID。");
        }
        return JsonUtil.toJson(new BuildingDetailVO(
                building.getId(), building.getName(), building.getCode(),
                building.getCountry(), building.getCity(), building.getAddress(),
                building.getContactName(),
                // 前台电话是公寓的公开联络方式，不属于个人 PII，但仍然做一次脱敏以防误传
                com.overseas.dorm.common.util.SensitiveMasker.maskPhone(building.getContactPhone()),
                building.getStatus() != null && building.getStatus() == 1 ? "启用" : "停用"));
    }

    @Tool("列出指定区域下的全部公寓（用于回答「新加坡有哪些公寓」这类问题）。")
    public String listBuildings(@P("区域编码，如 SEA；可为空表示全部区域") String regionCode) {
        Long regionId = null;
        if (regionCode != null && !regionCode.isBlank()) {
            regionId = resolveRegion(regionCode).getId();
        }
        return JsonUtil.toJson(new BuildingListVO(buildingService.options(regionId)));
    }

    @Tool("查看某个房间的床位占用明细（每个床位当前住到什么时候）。用于回答「这间房还有没有空位」「我能住到几号」这类问题。")
    public String getRoomBeds(@P("房间ID") Long roomId) {
        DormRoom room = roomService.requireRoom(roomId);
        List<RoomAvailabilityRow.BedDetail> beds = roomService.bedDetails(roomId);
        boolean viewerIsAdmin = com.overseas.dorm.common.security.UserContext.get() != null
                && com.overseas.dorm.common.security.UserContext.get().isAdmin();
        List<BedItemVO> bedList = beds.stream()
                .map(bed -> new BedItemVO(
                        bed.getBedNo(),
                        bed.getStatus(),
                        bed.getApplyId() == null ? null : String.valueOf(bed.getOccupiedCheckIn()),
                        bed.getApplyId() == null ? null : String.valueOf(bed.getOccupiedCheckOut()),
                        // 姓名脱敏：普通员工不应从 AI 对话里拿到同事姓名
                        bed.getApplyId() == null ? null
                                : (viewerIsAdmin ? bed.getEmployeeName()
                                        : com.overseas.dorm.common.util.SensitiveMasker
                                                .maskName(bed.getEmployeeName()))))
                .toList();
        return JsonUtil.toJson(new RoomBedsVO(
                room.getRoomNo(),
                room.getRoomType() == null ? null : room.getRoomType().getLabel(),
                room.getPricePerNight(), room.getCurrency(),
                room.getStatus() == null ? null : room.getStatus().getLabel(),
                bedList));
    }

    @Tool("查看各区域的房源总览：房间数、床位数、当前空闲床位数与关闭/维修情况。用于回答「哪里还有房」「哪个区域房源紧张」这类问题。")
    public String getRegionOverview() {
        List<RegionOverviewItem> data = roomService.statisticsByRegion(null).stream()
                .map(row -> new RegionOverviewItem(
                        row.getRegionName(), row.getRoomCount(), row.getOpenRoomCount(),
                        row.getClosedRoomCount(), row.getMaintenanceRoomCount(), row.getBedCount(),
                        row.getIdleBedCount(), row.getOccupiedBedCount()))
                .toList();
        return JsonUtil.toJson(new RegionOverview(data));
    }

    // ------------------------------------------------------------------ 参数解析

    private Region resolveRegion(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            throw com.overseas.dorm.common.exception.BizException.forAgent(
                    com.overseas.dorm.common.api.ResultCode.PARAM_MISSING,
                    "请指定区域",
                    "缺少区域参数。请先调用 listRegions 获取区域编码（SEA/EU/ME/NEA），"
                            + "或向用户确认要去哪个区域。");
        }
        String input = regionCode.trim();
        String code = input.toUpperCase();
        // 支持三种输入：区域编码（SEA）、中文区域名（东南亚）、区域 ID
        for (Region region : listAllRegions()) {
            if (region.getCode() != null && region.getCode().equalsIgnoreCase(code)) {
                return regionService.requireEnabled(region.getId());
            }
            if (region.getName() != null && region.getName().equals(input)) {
                return regionService.requireEnabled(region.getId());
            }
            if (String.valueOf(region.getId()).equals(input)) {
                return regionService.requireEnabled(region.getId());
            }
        }
        throw com.overseas.dorm.common.exception.BizException.forAgent(
                com.overseas.dorm.common.api.ResultCode.REGION_NOT_FOUND,
                "未找到区域：" + regionCode,
                "区域编码无效。合法值为 SEA（东南亚）、EU（欧洲）、ME（中东）、NEA（东北亚）。"
                        + "请调用 listRegions 获取准确列表后重试。");
    }

    private List<Region> listAllRegions() {
        return regionService.list(null, false).stream().map(vo -> {
            Region region = new Region();
            region.setId(vo.getId());
            region.setCode(vo.getCode());
            region.setName(vo.getName());
            region.setStatus(vo.getStatus());
            region.setCountry(vo.getCountry());
            return region;
        }).toList();
    }

    private LocalDate parseDate(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DateUtil.parseDate(text.trim());
        } catch (Exception e) {
            throw com.overseas.dorm.common.exception.BizException.forAgent(
                    com.overseas.dorm.common.api.ResultCode.DATE_RANGE_INVALID,
                    "%s格式不正确".formatted(field),
                    "%s 的格式应为 yyyy-MM-dd（例如 2026-11-05）。请据此重新调用。".formatted(field));
        }
    }

    private RoomType parseRoomType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toUpperCase();
        Map<String, String> chinese = Map.of(
                "单人间", "SINGLE", "双人间", "DOUBLE", "三人间", "TRIPLE", "四人间", "QUAD",
                "单人", "SINGLE", "双人", "DOUBLE", "三人", "TRIPLE", "四人", "QUAD");
        if (chinese.containsKey(text.trim())) {
            normalized = chinese.get(text.trim());
        }
        try {
            return RoomType.valueOf(normalized);
        } catch (Exception e) {
            throw com.overseas.dorm.common.exception.BizException.forAgent(
                    com.overseas.dorm.common.api.ResultCode.PARAM_INVALID,
                    "房型无效：" + text,
                    "房型只能是 SINGLE / DOUBLE / TRIPLE / QUAD 之一，或者不传（表示不限房型）。");
        }
    }

    private GenderLimit parseGender(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toUpperCase();
        if ("男".equals(text.trim())) {
            normalized = "MALE";
        }
        if ("女".equals(text.trim())) {
            normalized = "FEMALE";
        }
        try {
            return GenderLimit.valueOf(normalized);
        } catch (Exception e) {
            return null;
        }
    }

}
