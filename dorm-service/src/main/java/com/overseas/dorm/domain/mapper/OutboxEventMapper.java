package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 领域事件发件箱数据访问。
 *
 * <p>投递器按 {@code status + next_retry_time} 取待投递事件，再用
 * {@link #claim(Long)} 的<b>条件更新</b>领取：只有把 NEW/FAILED 改成 SENDING
 * 成功的那个实例才投递，因此多实例部署时同一条事件不会被重复投递。
 * 进程崩溃时事件会停在 SENDING，由 {@link #recoverStale(int)} 超时回收成 FAILED 再重试。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /** 扫描待投递事件（跳过未到重试时间、以及重试次数耗尽的事件）。 */
    @Select("""
            SELECT * FROM outbox_event
             WHERE status IN ('NEW', 'FAILED')
               AND retry_count < #{maxRetry}
               AND (next_retry_time IS NULL OR next_retry_time <= NOW())
             ORDER BY create_time
             LIMIT #{limit}
            """)
    List<OutboxEvent> selectPending(@Param("limit") int limit, @Param("maxRetry") int maxRetry);

    /**
     * 领取一条事件（条件更新，天然互斥）。
     *
     * @return 1=领取成功，可以投递；0=已被其它实例领取或状态已变化
     */
    @Update("""
            UPDATE outbox_event
               SET status = 'SENDING', update_time = NOW()
             WHERE id = #{id}
               AND status IN ('NEW', 'FAILED')
            """)
    int claim(@Param("id") Long id);

    /**
     * 回收卡在 SENDING 的事件（进程崩溃/被杀导致），置回 FAILED 等待重试。
     *
     * @param minutes 超过多少分钟视为卡住
     * @return 回收条数
     */
    @Update("""
            UPDATE outbox_event
               SET status = 'FAILED', update_time = NOW()
             WHERE status = 'SENDING'
               AND update_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)
            """)
    int recoverStale(@Param("minutes") int minutes);
}
