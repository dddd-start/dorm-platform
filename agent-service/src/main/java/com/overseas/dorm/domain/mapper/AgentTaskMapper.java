package com.overseas.dorm.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.overseas.dorm.domain.entity.AgentTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 异步任务数据访问。
 *
 * 这里如实说明，避免文档给出"已经支持月报/批量申请等异步任务"的错误印象：
 * <ul>
 *   <li>{@code agent_task} 表、实体与 Mapper 已就绪；</li>
 *   <li><b>但没有任何地方创建任务，也没有工作者领取并执行任务</b>，*       此前存在的 {@code selectQueued / claim / requeueStale} 三个方法
 *       从未被调用，已随本轮死代码清理删除，避免"看起来有队列"的误导。</li>
 * </ul>
 * 要做成真正可用的异步任务能力，需要补齐三件事：
 * 谁提交任务（生产者）、谁领取并执行（工作者，按 {@code priority + queued_time} 取）、
 * 以及卡死任务回收。它们属于功能开发，不在本次代码规范整改的范围内。
 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTask> {
}
