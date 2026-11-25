package com.overseas.dorm.agent.vo;

import java.io.Serializable;

/**
 * 一条长期记忆。
 *
 * <p>下发 {@code typeLabel} 而不只是 {@code type}：给用户看的界面不应该显示
 * {@code PREFERENCE} 这种内部枚举名，而中文标签的映射属于后端职责，* 否则前端要为每个枚举维护一份翻译表，且新增类型时必然漏掉。
 */
public record AgentMemoryVO(
        Long id,
        String type,
        String typeLabel,
        String memoryKey,
        String content,
        Integer importance,
        String updateTime
) implements Serializable {
}
