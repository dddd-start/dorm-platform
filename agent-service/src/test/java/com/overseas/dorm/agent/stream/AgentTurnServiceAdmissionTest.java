package com.overseas.dorm.agent.stream;

import com.overseas.dorm.agent.aiservice.AgentRegistry;
import com.overseas.dorm.agent.aiservice.AgentRouter;
import com.overseas.dorm.agent.audit.AgentAuditService;
import com.overseas.dorm.agent.client.DormServiceClient;
import com.overseas.dorm.agent.config.AgentProperties;
import com.overseas.dorm.agent.guardrail.ContentGuardrail;
import com.overseas.dorm.agent.memory.AgentMemoryService;
import com.overseas.dorm.agent.memory.DormChatMemoryStore;
import com.overseas.dorm.agent.model.ModelConcurrencyGuard;
import com.overseas.dorm.agent.model.ModelRegistry;
import com.overseas.dorm.common.enums.AgentEnums;
import com.overseas.dorm.common.enums.StreamState;
import com.overseas.dorm.common.security.LoginUser;
import com.overseas.dorm.common.security.UserContext;
import com.overseas.dorm.domain.entity.AgentConversation;
import com.overseas.dorm.domain.entity.AgentMessage;
import com.overseas.dorm.domain.mapper.AgentMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闸门拒绝路径的回归测试（清单里的 P0-1）。
 *
 * <p>模型并发闸门拒绝时，doChat 已经注册了 channel、订阅者和本地映射，
 * 但返回给控制器的却是一个新的 errorEmitter。如果没有回滚：
 * <ul>
 *   <li>channels 里留下一个永不关闭的通道；</li>
 *   <li>Redis 订阅键被心跳永久续期；</li>
 *   <li>user/global 并发计数永久少一个。</li>
 * </ul>
 */
class AgentTurnServiceAdmissionTest {

    private static final Long USER_ID = 7L;
    private static final Long CONVERSATION_ID = 11L;
    private static final Long MESSAGE_ID = 42L;

    @Test
    @DisplayName("模型闸门拒绝：channel、订阅者、并发计数、流状态全部回滚")
    void modelGateRejectionRollsBackEverything() throws Exception {
        AgentProperties properties = mock(AgentProperties.class);
        AgentProperties.Stream stream = mock(AgentProperties.Stream.class);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getStream()).thenReturn(stream);
        when(stream.getMaxStreamsPerUser()).thenReturn(3);
        when(stream.getMaxStreamsGlobal()).thenReturn(1000);
        when(stream.getChannelQueueCapacity()).thenReturn(8);

        AgentStreamBuffer buffer = mock(AgentStreamBuffer.class);
        when(buffer.incrementUserStreams(USER_ID)).thenReturn(1L);
        when(buffer.incrementGlobalStreams()).thenReturn(1L);

        ContentGuardrail guardrail = mock(ContentGuardrail.class);
        when(guardrail.checkInput("你好")).thenReturn(
                new ContentGuardrail.InputCheck(true, null, "你好"));

        AgentRateLimiter rateLimiter = mock(AgentRateLimiter.class);
        when(rateLimiter.tryAcquireTurn(USER_ID)).thenReturn(null);
        when(rateLimiter.checkBudget(USER_ID)).thenReturn(AgentRateLimiter.BudgetDecision.allowed());

        ConversationService conversations = mock(ConversationService.class);
        AgentConversation conversation = mock(AgentConversation.class);
        when(conversation.getId()).thenReturn(CONVERSATION_ID);
        when(conversations.createConversation(eq(USER_ID), eq("你好"), any()))
                .thenReturn(conversation);
        AgentMessage assistant = mock(AgentMessage.class);
        when(assistant.getId()).thenReturn(MESSAGE_ID);
        when(conversations.beginTurn(eq(CONVERSATION_ID), eq("你好"), anyString(), anyString()))
                .thenReturn(assistant);

        ModelConcurrencyGuard modelGuard = mock(ModelConcurrencyGuard.class);
        when(modelGuard.tryAcquire()).thenReturn(false);

        AgentTurnService service = new AgentTurnService(
                properties,
                mock(AgentRegistry.class),
                mock(AgentRouter.class),
                buffer,
                conversations,
                guardrail,
                rateLimiter,
                mock(AgentAuditService.class),
                mock(ModelRegistry.class),
                mock(DormServiceClient.class),
                mock(AgentMessageMapper.class),
                mock(ExecutorService.class),
                mock(ThreadPoolTaskScheduler.class),
                mock(AgentMemoryService.class),
                mock(DormChatMemoryStore.class),
                mock(TaskExecutor.class),
                modelGuard);

        LoginUser user = LoginUser.builder().userId(USER_ID).username("tester").build();
        UserContext.runAs(user, () -> service.chat(null, "turn-1", "你好"));

        // 闸门拒绝必须走 finishTurn(FAILED)，而不是让消息永远停在 GENERATING
        verify(conversations).finishTurn(eq(MESSAGE_ID), eq(CONVERSATION_ID),
                anyString(), isNull(), eq(AgentEnums.MessageStatus.FAILED), anyString(),
                anyInt(), anyInt(), anyLong(), anyLong(), eq(USER_ID),
                anyInt(), anyInt(), eq(false), eq(true), isNull(), anyList());

        // 注册过的三项本地/Redis 状态必须回滚
        verify(buffer).unregisterSubscriber(eq(MESSAGE_ID), anyString());
        verify(buffer).setState(MESSAGE_ID, StreamState.FAILED);
        verify(buffer).decrementUserStreams(USER_ID);
        verify(buffer).decrementGlobalStreams();

        // 闸门没拿到许可，不能误释放
        verify(modelGuard, never()).release();

        // 通道必须从注册表移除，且其写入器收到收尾信号
        Field channelsField = AgentTurnService.class.getDeclaredField("channels");
        channelsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, ?> channels = (Map<Long, ?>) channelsField.get(service);
        assertThat(channels).isEmpty();

        Field subscribersField = AgentTurnService.class.getDeclaredField("localSubscriberIds");
        subscribersField.setAccessible(true);
        assertThat((Map<?, ?>) subscribersField.get(service)).isEmpty();
    }

}
