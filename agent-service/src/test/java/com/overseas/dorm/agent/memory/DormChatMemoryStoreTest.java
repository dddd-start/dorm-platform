package com.overseas.dorm.agent.memory;

import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.domain.mapper.AgentConversationMapper;
import com.overseas.dorm.domain.mapper.AgentMessageMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H11 回归：一次对话内部的工具循环必须能读到上一轮的 assistant(tool_calls)
 * 与 TOOL 结果。
 *
 * <p>修复前 {@code updateMessages} 是空实现，模型每一轮都只看到最初那条用户消息，
 * 于是反复调用同一个工具，直到 {@code maxToolCallingRoundTrips} 耗尽。
 * 这个测试守住"工具消息在本轮内可见、轮次结束后不污染下一轮"两个性质。
 */
class DormChatMemoryStoreTest {

    @Test
    @DisplayName("工具循环中的 assistant/tool 消息在本轮内可见，结束后被丢弃")
    void toolLoopMessagesAreVisibleWithinTurn() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        DormChatMemoryStore store = new DormChatMemoryStore(messageMapper, conversationMapper);

        AgentMessage user = new AgentMessage();
        user.setId(10L);
        user.setRole(AgentEnums.Role.USER);
        user.setContent("有哪些区域？");
        user.setStatus(AgentEnums.MessageStatus.COMPLETED);

        AgentMessage streaming = new AgentMessage();
        streaming.setId(11L);
        streaming.setRole(AgentEnums.Role.ASSISTANT);
        streaming.setContent("");
        streaming.setStatus(AgentEnums.MessageStatus.STREAMING);

        when(messageMapper.selectRecent(eq(1L), anyInt())).thenReturn(List.of(user, streaming));
        when(conversationMapper.selectById(1L)).thenReturn(null);

        store.beginTurn(1L);
        // 库里的当前用户消息会被剔除；AiServices 随后会自己 add 一次，避免重复。
        assertThat(store.getMessages(1L)).isEmpty();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("listRegions").arguments("{}").build();
        List<ChatMessage> inTurn = List.of(
                UserMessage.from("有哪些区域？"),
                AiMessage.builder().toolExecutionRequests(List.of(request)).build(),
                ToolExecutionResultMessage.from("call-1", "listRegions", "{\"regions\":[]}"));
        store.updateMessages(1L, inTurn);

        List<ChatMessage> secondRound = store.getMessages(1L);
        assertThat(secondRound).hasSize(3);
        assertThat(secondRound.get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) secondRound.get(1)).toolExecutionRequests()).hasSize(1);
        assertThat(secondRound.get(2)).isInstanceOf(ToolExecutionResultMessage.class);

        store.endTurn(1L);
        // 结束后的下一次读取回到数据库视图；STREAMING 助手消息被跳过，USER 保留
        assertThat(store.getMessages(1L))
                .hasSize(1)
                .noneMatch(m -> m instanceof ToolExecutionResultMessage);
    }
}
