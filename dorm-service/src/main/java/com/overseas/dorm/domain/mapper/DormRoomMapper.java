package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.dto.RoomAvailabilityRow;
import com.overseas.dorm.domain.entity.DormRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 房间与床位的数据访问。
 *
 * 房间"可订性"<b>不能按床位的当前状态判断</b>，必须按
 * <b>申请单的日期区间是否重叠</b>判断，否则"下个月空着的房间"
 * 会被显示为不可订（床位此刻是 OCCUPIED，但到下个月已经空出来了）。
 * {@code DormRoomMapper.xml} 里的可用性查询就是按区间重叠实现的。
 */
@Mapper
public interface DormRoomMapper extends BaseMapper<DormRoom> {

    /**
     * 按日期区间查询可用房源。
     *
     * <p>为什么不能只用 {@code dorm_bed.status='IDLE'} 判断：
     * 床位状态是「此刻」的快照，一张被预订给下个月的床，现在也是 OCCUPIED。
     * 若只按状态过滤，用户就永远查不到「下个月其实空着」的房间。
     * 这是本系统房源检索正确性的核心。
     *
     * @param regionId   区域（必填，数据权限基线）
     * @param buildingId 公寓（可空）
     * @param city       城市（可空，模糊匹配公寓城市）
     * @param roomType   房型（可空）
     * @param gender     性别要求（可空；用于过滤不匹配的房间）
     * @param checkIn    入住日（可空：为空表示只按当前空闲床位统计）
     * @param checkOut   退房日（可空）
     */
    List<RoomAvailabilityRow> selectAvailability(@Param("regionId") Long regionId,
                                                 @Param("buildingId") Long buildingId,
                                                 @Param("city") String city,
                                                 @Param("roomType") String roomType,
                                                 @Param("gender") String gender,
                                                 @Param("checkIn") LocalDate checkIn,
                                                 @Param("checkOut") LocalDate checkOut,
                                                 @Param("minBeds") Integer minBeds);

    /** 房间维度的床位明细（含当前占用申请信息），用于分房界面。 */
    List<RoomAvailabilityRow.BedDetail> selectBedDetails(@Param("roomId") Long roomId);

    /** 区域的房源总览统计（管理看板）。 */
    List<com.overseas.dorm.domain.vo.RoomStatsVO> statisticsByRegion(@Param("regionId") Long regionId);

    /** 公寓维度的房源统计（一次分组聚合取代逐栋查询的 N+1）。 */
    List<com.overseas.dorm.domain.vo.BuildingStatsVO> statisticsByBuilding(@Param("regionId") Long regionId);
}
