package com.overseas.dorm.common.enums;

import lombok.Getter;

/**
 * AI 消息角色与状态。
 */
public final class AgentEnums {

    private AgentEnums() {
    }

    /**
     * 消息角色（与 OpenAI 协议对齐）。
     *
     * <p>带中文标签：回放与管理界面需要展示"用户/助手/工具"，
     * 直接显示 {@code TOOL} 对排障人员不友好。
     */
    @Getter
    public enum Role {
        USER("用户"),
        ASSISTANT("助手"),
        TOOL("工具"),
        SYSTEM("系统");

        private final String label;

        Role(String label) {
            this.label = label;
        }
    }

    /**
     * 消息状态。
     *
     * <p>流式生成的生命周期：STREAMING -> COMPLETED；
     * 被用户打断 -> INTERRUPTED；被安全护栏截断 -> BLOCKED；
     * 模型/网络失败 -> FAILED。任何非 COMPLETED 的中间态都保留半成品内容，
     * 以便「断点续打」与「可回放」。
     */
    @Getter
    public enum MessageStatus {
        STREAMING, COMPLETED, INTERRUPTED, BLOCKED, FAILED
    }

    /** 异步任务状态。 */
    @Getter
    public enum TaskStatus {
        QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED
    }

    /** 异步任务类型。 */
    @Getter
    public enum TaskType {
        MONTHLY_REPORT("月度住宿报表"),
        BATCH_APPLY("批量入住申请"),
        OPS_DAILY_REPORT("运维日报");

        private final String label;

        TaskType(String label) {
            this.label = label;
        }
    }

    /** 人工干预待办状态。 */
    @Getter
    public enum ApprovalStatus {
        PENDING, APPROVED, REJECTED, EXPIRED
    }

    /**
     * 长期记忆类型。
     *
     * <p>带中文标签：界面需要展示"资料/摘要/偏好"，而这类映射属于后端职责，* 放前端意味着每新增一个类型都要同步改前端，且必然有漏掉的一次。
     */
    @Getter
    public enum MemoryType {
        PROFILE("个人资料"),
        SUMMARY("会话摘要"),
        PREFERENCE("使用偏好");

        private final String label;

        MemoryType(String label) {
            this.label = label;
        }
    }

    /** SSE 事件类型（前后端唯一契约）。 */
    public enum StreamEvent {
        /** 连接建立，回传 messageId / 起始 seq，前端据此做续传对齐。 */
        START,
        /** 思考过程增量（reasoning_content），与答案分开渲染。 */
        REASONING,
        /** 答案正文增量。 */
        DELTA,
        /** 工具调用开始/结束。 */
        TOOL,
        /** RAG 引用来源。 */
        REFERENCE,
        /** 需要人工确认。 */
        HUMAN_REQUIRED,
        /** 正常结束，含 token 用量与耗时。 */
        DONE,
        /** 被安全护栏截断。 */
        BLOCKED,
        /** 被用户打断。 */
        INTERRUPTED,
        /** 出错。 */
        ERROR
    }
}
