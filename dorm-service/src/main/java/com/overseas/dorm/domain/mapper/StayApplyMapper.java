package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.StayApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 入住申请数据访问（含状态机相关的过期扫描与状态计数）。
 *
 * <p>状态值由调用方传入枚举名，不在 SQL 里写字面量 —— 枚举改名时
 * SQL 会照跑不报错，只会让定时任务静默失效（"过期申请永远不释放床位"）。
 */
@Mapper
public interface StayApplyMapper extends BaseMapper<StayApply> {

    /**
     * 查询某员工在指定日期区间内是否已有会冲突的有效申请。
     *
     * <p>业务规则：同一个人不能在同一时间段有两份有效的入住安排，
     * 否则会出现「一个人占了两个区域的床位」。
     * 冲突判定同样基于日期区间重叠，与房源检索保持一致的口径。
     */
    @Select("""
            SELECT * FROM stay_apply
             WHERE employee_id = #{employeeId}
               AND status IN ('PENDING', 'APPROVED', 'CHECKED_IN')
               AND check_in_date < #{checkOut}
               AND #{checkIn} < check_out_date
            """)
    List<StayApply> selectOverlapping(@Param("employeeId") Long employeeId,
                                      @Param("checkIn") java.time.LocalDate checkIn,
                                      @Param("checkOut") java.time.LocalDate checkOut);

    /**
     * 待审批 / 待入住 / 在住 等状态计数（管理看板）。
     *
     * <p>返回 {@link StatusCountVO} 而不是 {@code Map<String, Object>}：
     * 聚合结果的列别名一旦拼错，用 Map 接只会得到 null，再被上层转成 0，
     * 统计数字静默出错且没有任何提示。
     */
    @Select("""
            SELECT status, COUNT(*) AS cnt FROM stay_apply
             WHERE (#{regionId} IS NULL OR region_id = #{regionId})
               AND (#{employeeId} IS NULL OR employee_id = #{employeeId})
             GROUP BY status
            """)
    List<com.overseas.dorm.domain.vo.StatusCountVO> countByStatus(@Param("regionId") Long regionId,
                                                                 @Param("employeeId") Long employeeId);

    /**
     * 过期未入住的申请（定时任务扫描用）。
     *
     * <p>状态值由调用方传入枚举名（{@code ApplyStatus.APPROVED.name()}），
     * 而不是在 SQL 里写 {@code 'APPROVED'}：避免"枚举改了、SQL 没改"导致
     * 定时任务永远扫不到数据 —— 这类问题只表现为"功能不生效"，日志里毫无线索。
     */
    @Select("""
            SELECT * FROM stay_apply
             WHERE status = #{approved} AND expire_time IS NOT NULL AND expire_time < NOW()
             LIMIT #{limit}
            """)
    List<StayApply> selectExpired(@Param("approved") String approved, @Param("limit") int limit);

    /** 入住日期分布（近 N 天的趋势统计）。 */
    @Select("""
            SELECT DATE(create_time) AS day, COUNT(*) AS cnt
              FROM stay_apply
             WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
               AND (#{regionId} IS NULL OR region_id = #{regionId})
               AND (#{employeeId} IS NULL OR employee_id = #{employeeId})
             GROUP BY DATE(create_time)
             ORDER BY day
            """)
    List<com.overseas.dorm.domain.vo.DailyCountVO> countDailyTrend(@Param("regionId") Long regionId,
                                                                   @Param("employeeId") Long employeeId,
                                                                   @Param("days") int days);
}
