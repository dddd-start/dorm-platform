package com.overseas.dorm.boot.controller;

import com.overseas.dorm.common.api.Result;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.enums.ApplySource;
import com.overseas.dorm.common.enums.ApplyStatus;
import com.overseas.dorm.common.enums.AuditEnums;
import com.overseas.dorm.common.enums.BedStatus;
import com.overseas.dorm.common.enums.DocCategory;
import com.overseas.dorm.common.enums.GenderLimit;
import com.overseas.dorm.common.enums.OrderStatus;
import com.overseas.dorm.common.enums.RoomStatus;
import com.overseas.dorm.common.enums.RoomType;
import com.overseas.dorm.common.enums.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据接口：一次性把全部枚举给前端。
 *
 * <p>为什么不把这些下拉选项硬编码在前端：
 * 枚举是「后端与前端之间的契约」，硬编码在前端意味着
 * 后端新增一个状态时，前端会静默地显示成空白，而这往往要到线上才发现。
 * 统一由后端提供，前端只负责渲染，契约只有一处。
 */
@Tag(name = "10-元数据", description = "枚举字典，前端下拉与状态渲染的数据源")
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    /**
     * 全部枚举字典。
     *
     * <p><b>这里有意保留 {@code Map<String, Object>}</b>：它是字典聚合接口，
     * 17 个键各自对应一份不同的字典列表，键集合由"有哪些枚举"动态决定。
     * 为它硬造一个 17 字段的 record 并不会更安全（新增枚举时照样要改），
     * 反而会让"加一个枚举"变成两处改动。
     *
     * <p>与之相对，每个<b>值</b>都已经是具体类型（{@link com.overseas.dorm.common.api.OptionVO}），
     * 不再是内层 {@code Map}，前端拿到的每一项都有确定的 {@code value/label/color}。
     * 这正是本项目对 Map 的使用标准：<b>动态键可以用，值为 Object 才要消除</b>。
     */
    /** 由枚举派生字典（值=枚举名，标签=中文）。 */
    private static List<com.overseas.dorm.common.api.OptionVO> enumOptions(
            Enum<?>[] values) {
        return java.util.Arrays.stream(values)
                .map(e -> com.overseas.dorm.common.api.OptionVO.ofEnum(e,
                        labelOf(e)))
                .toList();
    }

    /** 取枚举的中文标签（约定：枚举带 {@code getLabel()}）。 */
    private static String labelOf(Enum<?> value) {
        try {
            Object label = value.getClass().getMethod("getLabel").invoke(value);
            return label == null ? value.name() : label.toString();
        } catch (ReflectiveOperationException e) {
            return value.name();
        }
    }

    @Operation(summary = "全部枚举字典", description = "无需登录也可访问，前端启动时拉取一次")
    @GetMapping("/enums")
    public Result<Map<String, Object>> enums() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userRole", UserRole.options());
        data.put("applyStatus", ApplyStatus.options());
        data.put("orderStatus", OrderStatus.options());
        data.put("roomStatus", RoomStatus.options());
        data.put("roomType", RoomType.options());
        data.put("genderLimit", GenderLimit.options());
        data.put("bedStatus", BedStatus.options());
        data.put("applySource", ApplySource.options());
        data.put("docCategory", DocCategory.options());
        data.put("auditResult", List.of(
                com.overseas.dorm.common.api.OptionVO.of("SUCCESS", "成功"),
                com.overseas.dorm.common.api.OptionVO.of("FAIL", "失败")));
        // 这两个字典没有对应的 Java 枚举（审计结果、Agent 类型分散在别处），
        // 因此就地定义；值类型同样是 OptionVO，前端渲染逻辑与其它字典完全一致
        data.put("agentType", java.util.Arrays.stream(com.overseas.dorm.common.enums.AgentType.values())
                .map(e -> com.overseas.dorm.common.api.OptionVO.ofEnum(e, e.getLabel()))
                .toList());
        // 这几组同样来源于枚举，直接由枚举派生 —— 手写字典的最大风险是
        // "枚举加了一项、字典忘了加"，于是新状态在前端显示为空白
        data.put("agentMessageStatus", enumOptions(
                com.overseas.dorm.common.enums.AgentEnums.MessageStatus.values()));
        data.put("agentTaskStatus", enumOptions(
                com.overseas.dorm.common.enums.AgentEnums.TaskStatus.values()));
        data.put("agentTaskType", enumOptions(
                com.overseas.dorm.common.enums.AgentEnums.TaskType.values()));
        data.put("approvalStatus", List.of(
                Map.of("value", "PENDING", "label", "待确认"),
                Map.of("value", "APPROVED", "label", "已确认"),
                Map.of("value", "REJECTED", "label", "已拒绝"),
                Map.of("value", "EXPIRED", "label", "已过期")));
        data.put("auditModule", List.of(
                Map.of("value", AuditEnums.Module.APPLY.name(), "label", AuditEnums.Module.APPLY.getLabel()),
                Map.of("value", AuditEnums.Module.ORDER.name(), "label", AuditEnums.Module.ORDER.getLabel()),
                Map.of("value", AuditEnums.Module.ROOM.name(), "label", AuditEnums.Module.ROOM.getLabel()),
                Map.of("value", AuditEnums.Module.BED.name(), "label", AuditEnums.Module.BED.getLabel()),
                Map.of("value", AuditEnums.Module.BUILDING.name(), "label", AuditEnums.Module.BUILDING.getLabel()),
                Map.of("value", AuditEnums.Module.REGION.name(), "label", AuditEnums.Module.REGION.getLabel()),
                Map.of("value", AuditEnums.Module.USER.name(), "label", AuditEnums.Module.USER.getLabel()),
                Map.of("value", AuditEnums.Module.AGENT.name(), "label", AuditEnums.Module.AGENT.getLabel()),
                Map.of("value", AuditEnums.Module.AUTH.name(), "label", AuditEnums.Module.AUTH.getLabel()),
                Map.of("value", AuditEnums.Module.ADMIN.name(), "label", AuditEnums.Module.ADMIN.getLabel())));
        data.put("streamEvent", List.of(
                AgentEnums.StreamEvent.START, AgentEnums.StreamEvent.REASONING, AgentEnums.StreamEvent.DELTA,
                AgentEnums.StreamEvent.TOOL, AgentEnums.StreamEvent.REFERENCE, AgentEnums.StreamEvent.HUMAN_REQUIRED,
                AgentEnums.StreamEvent.DONE, AgentEnums.StreamEvent.BLOCKED, AgentEnums.StreamEvent.INTERRUPTED,
                AgentEnums.StreamEvent.ERROR)
                .stream().map(e -> Map.of("value", e.name(), "label", e.name())).toList());
        return Result.ok(data);
    }
}
