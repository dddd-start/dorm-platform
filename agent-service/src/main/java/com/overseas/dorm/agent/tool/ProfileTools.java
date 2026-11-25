package com.overseas.dorm.agent.tool;

import com.overseas.dorm.agent.memory.AgentMemoryService;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.common.util.JsonUtil;
import com.overseas.dorm.agent.client.DormServiceClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户画像与记忆工具。
 *
 * 很多实现会让模型在后台自动提取并保存用户偏好。这看起来智能，
 * 但在企业系统里有两个问题：一是用户不知道系统记住了什么（合规风险），
 * 二是模型可能把一次性需求误存成长期偏好。
 *
 * <p>所以这里把记忆做成<b>显式工具</b>：模型只有在"用户明确表达了
 * 稳定偏好"时才调用它，而且用户可以在界面上查看和清除自己的记忆
 * （见 {@code /api/agent/memories}）。这是一种刻意的克制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileTools {

    private final AgentMemoryService memoryService;
    private final DormServiceClient authService;

    @Tool("查看当前用户自己的资料（姓名、工号、角色、部门、所属区域）。当用户询问关于「我」的身份信息时，可以用它确认。")
    public String getMyProfile() {
        LoginUser user = UserContext.require();
        var vo = authService.currentUser(false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", user.getUsername());
        result.put("realName", vo.getRealName());
        result.put("employeeNo", vo.getEmployeeNo());
        result.put("role", vo.getRoleLabel());
        result.put("department", vo.getDepartment());
        result.put("region", vo.getRegionName());
        // 手机号只给掩码：即使是本人，也没有必要让模型看到完整号码
        result.put("phoneMask", vo.getPhoneMask());
        return JsonUtil.toJson(result);
    }

    @Tool("""
            记住用户的一条稳定偏好，供以后所有对话使用。
            只有在用户明确表达"以后都""我一直""我习惯"这类长期偏好时才调用；
            一次性的需求（比如"这次我要单人间"）不要记录。
            可用的 key 建议：PREFERRED_ROOM_TYPE（偏好房型）、
            PREFERRED_CITY（常去城市）、MIND_SHARING（是否介意合住）、
            PREFERRED_BUILDING（偏好公寓）。
            """)
    public String rememberPreference(
            @P("偏好键，如 PREFERRED_ROOM_TYPE") String key,
            @P("偏好内容，一句自然语言，如「偏好单人间」") String content) {
        LoginUser user = UserContext.require();
        if (key == null || key.isBlank() || content == null || content.isBlank()) {
            return JsonUtil.toJson(Map.of("success", false,
                    "message", "偏好键与内容都不能为空"));
        }
        memoryService.remember(user.getUserId(), AgentEnums.MemoryType.PREFERENCE, key, content, 7);
        log.info("[Agent工具] 记录用户偏好 userId={} key={} content={}", user.getUserId(), key, content);
        return JsonUtil.toJson(Map.of(
                "success", true,
                "key", key,
                "content", content,
                "message", "已记住这条偏好，之后的对话会自动应用。用户可以在「我的助手偏好」里查看或清除。"));
    }

    @Tool("查看系统当前记住了用户的哪些信息（画像、偏好、上次沟通纪要）。当用户问「你记得我什么」时调用。")
    public String listMyMemories() {
        LoginUser user = UserContext.require();
        var memories = memoryService.listMemories(user.getUserId());
        return JsonUtil.toJson(Map.of("count", memories.size(), "memories", memories,
                "note", "这些信息由系统在与用户的对话中记录，用户可在界面上清除。"));
    }
}
