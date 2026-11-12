package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.DormBed;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 床位数据访问。
 *
 * <p>床位状态（IDLE/OCCUPIED/DISABLED）是"此刻"的事实，
 * 只用于分配与占用校验，不能用来回答"某段时间内是否有空位"（见 {@link DormRoomMapper}）。
 */
@Mapper
public interface DormBedMapper extends BaseMapper<DormBed> {

    /**
     * 原子占用床位 —— 并发安全的最后一道防线。
     *
     * <p>{@code WHERE status = 'IDLE'} 这个条件让「检查-占用」合成一个原子操作：
     * 两个并发事务同时执行，只有一个能把 IDLE 改成 OCCUPIED，
     * 另一个影响行数为 0，业务层据此判定「床位已被抢走」并重新分配。
     * 这比「先 SELECT 判断再 UPDATE」可靠得多 —— 后者是典型的竞态。
     */
    @Update("""
            UPDATE dorm_bed
               SET status = 'OCCUPIED', current_apply_id = #{applyId}, version = version + 1
             WHERE id = #{bedId} AND status = 'IDLE'
            """)
    int occupyBed(@Param("bedId") Long bedId, @Param("applyId") Long applyId);

    /** 释放床位（退房 / 取消 / 失效）。只有当占用者匹配时才释放，避免误放被他人接管的床。 */
    @Update("""
            UPDATE dorm_bed
               SET status = 'IDLE', current_apply_id = NULL, version = version + 1
             WHERE id = #{bedId} AND current_apply_id = #{applyId}
            """)
    int releaseBed(@Param("bedId") Long bedId, @Param("applyId") Long applyId);



    @Select("SELECT * FROM dorm_bed WHERE room_id = #{roomId} ORDER BY bed_no")
    List<DormBed> selectByRoom(@Param("roomId") Long roomId);


}
