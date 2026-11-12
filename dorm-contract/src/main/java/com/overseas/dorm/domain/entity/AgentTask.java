package com.overseas.dorm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.overseas.dorm.common.enums.AgentEnums;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 异步任务。
 *
 * <p>「执行时间超过十秒的任务异步化」的落地：月报生成、批量申请、运维日报
 * 这类任务不在 HTTP 请求里同步等待，而是落库进队列，由工作线程按优先级消费，
 * 前端轮询进度。这样既不会占着 SSE 长连接，也不会因为网关超时而失败。
 */
@Data
@TableName("agent_task")
public class AgentTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String taskNo;
    private Long userId;
    private Long conversationId;
    private AgentEnums.TaskType taskType;
    private String paramsJson;
    private AgentEnums.TaskStatus status;
    /** 数值越小优先级越高（管理员任务优先于员工任务）。 */
    private Integer priority;
    private Integer progress;
    private String resultJson;
    private String errorMsg;
    private LocalDateTime queuedTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
}
