package com.overseas.dorm.common.enums;

import lombok.Getter;

/**
 * 领域事件发件箱的状态。
 *
 * <p>用枚举而不是散落的字符串字面量，收益不只是"好看"：
 * <ul>
 *   <li><b>编译期就能发现拼写错误</b>。{@code "FALIED"} 这种笔误在字符串世界里
 *       只会在生产上表现为"事件永远投递不出去"，而且日志里看不出任何异常；</li>
 *   <li><b>可枚举、可测试</b>。运维看板要展示"各状态积压量"，
 *       有了枚举就能直接遍历全部状态，不会漏掉某个新加的；</li>
 *   <li><b>重构安全</b>。改名时编译器会指出所有使用点。</li>
 * </ul>
 *
 * <p>数据库里存的仍是枚举的 {@code name()}，MyBatis 默认按名称映射，
 * 因此不需要额外的 TypeHandler。
 */
@Getter
public enum OutboxStatus {

    /** 待投递。 */
    NEW("待投递"),
    /** 已被某个实例领取、正在投递（多实例防重复投递的租约态）。 */
    SENDING("投递中"),
    /** 投递成功。 */
    SENT("已投递"),
    /** 投递失败，等待重试（未达重试上限）。 */
    FAILED("待重试"),
    /** 超过重试上限，需要人工介入。 */
    DEAD("已死信");

    private final String label;

    OutboxStatus(String label) {
        this.label = label;
    }

}
