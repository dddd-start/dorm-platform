package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.StayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 入住订单数据访问（含逾期扫描与床位占用统计）。
 */
@Mapper
public interface StayOrderMapper extends BaseMapper<StayOrder> {

    /** 已到退房日期但仍未退房的订单（定时提醒 / 自动结算用）。 */
    @Select("""
            SELECT * FROM stay_order
             WHERE status = 'CHECKED_IN' AND check_out_date < CURDATE()
             LIMIT #{limit}
            """)
    List<StayOrder> selectOverdue(@Param("limit") int limit);

    @Select("""
            SELECT status, COUNT(*) AS cnt FROM stay_order
             WHERE (#{regionId} IS NULL OR region_id = #{regionId})
               AND (#{employeeId} IS NULL OR employee_id = #{employeeId})
             GROUP BY status
            """)
    List<com.overseas.dorm.domain.vo.StatusCountVO> countByStatus(@Param("regionId") Long regionId,
                                                                 @Param("employeeId") Long employeeId);

    /**
     * 区域入住率：已占用床位 / 可用床位。
     *
     * <p>两个状态值同样由调用方传入枚举名（{@code BedStatus.OCCUPIED.name()} /
     * {@code BedStatus.DISABLED.name()}），SQL 里不留字符串字面量。
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM dorm_bed
                WHERE status = #{occupied} AND (#{regionId} IS NULL OR region_id = #{regionId})) AS occupiedBeds,
              (SELECT COUNT(*) FROM dorm_bed
                WHERE status <> #{disabled} AND (#{regionId} IS NULL OR region_id = #{regionId})) AS usableBeds
            """)
    com.overseas.dorm.domain.vo.OccupancyRowVO occupancy(@Param("regionId") Long regionId,
                                                       @Param("occupied") String occupied,
                                                       @Param("disabled") String disabled);
}
