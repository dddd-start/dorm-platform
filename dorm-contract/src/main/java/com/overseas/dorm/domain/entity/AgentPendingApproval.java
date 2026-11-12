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
 * 人工干预待办。
 *
 * <p>什么场景会用它：当 Agent 判断某个操作「不可逆或金额/影响面较大」时
 * （例如跨区域申请、取消已入住订单），它不会直接执行，而是生成一条待办，
 * 通过 SSE 的 {@code HUMAN_REQUIRED} 事件告知前端，由管理员在控制台确认后
 * Agent 才继续。这既是安全阀，也是「人在回路」的产品化体现。
 */
@Data
@TableName("agent_pending_approval")
public class AgentPendingApproval implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String approvalNo;
    private Long conversationId;
    private String turnId;
    private Long userId;
    private String actionType;
    private String paramsJson;
    private String reason;
    private AgentEnums.ApprovalStatus status;
    private Long approverId;
    private LocalDateTime approveTime;
    private String remark;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
}
