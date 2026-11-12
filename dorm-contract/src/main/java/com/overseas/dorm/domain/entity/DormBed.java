package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.overseas.dorm.common.enums.BedStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 床位 —— 并发分配的最小竞争单元。
 *
 * <p>「同一张床被两个人同时抢到」是本系统唯一真正的高并发风险点，
 * 因此这里用<b>三道防线</b>叠加：
 * <ol>
 *   <li>{@link Version} 乐观锁：UPDATE 带 version 条件，冲突即失败；</li>
 *   <li>Redis 分布式锁：按 bedId 加锁，把并发收敛成串行；</li>
 *   <li>条件更新：{@code UPDATE ... WHERE status='IDLE'}，
 *       即便前两道被绕过，数据库层面也不会把已占用的床改成占用。</li>
 * </ol>
 */
@Data
@TableName("dorm_bed")
public class DormBed implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long roomId;
    /** 冗余：所属公寓。 */
    private Long buildingId;
    /** 冗余：所属区域。 */
    private Long regionId;
    private String bedNo;
    private BedStatus status;
    /** 当前占用的申请 ID；释放时置空。 */
    private Long currentApplyId;

    @Version
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
