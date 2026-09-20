package vip.mate.cron.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.AgentService;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;
import vip.mate.i18n.I18nService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.workspace.conversation.ConversationService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CronJobLifecycleFenceTest {

    @BeforeAll
    static void initMpLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), CronJobRunEntity.class);
    }

    @Test
    void failedTerminalWrite_isFencedByRunningStatus() {
        Fixture fixture = new Fixture();
        fixture.service.markRunFailed(run(), new IllegalStateException("failed"));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(fixture.mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status"));
    }

    @Test
    void lateCompletion_dropsMessagesAndEventsAfterFenceIsLost() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        CronJobEntity job = new CronJobEntity();
        job.setId(1L);

        fixture.service.finishRunAndPublish(job, run(), "request",
                new AssistantMessage("late result"), "cron-1", false);

        verifyNoInteractions(fixture.conversations, fixture.completionPublisher, fixture.events);
    }

    @Test
    void graphErrorPersistsAnErrorMessageWithoutPublishingSuccessEvents() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        AgentService.ChatResult failed = new AgentService.ChatResult(
                "[错误] account expired", 12, 3, "model-a", "provider-a", "error_fallback");

        fixture.service.finishRunFailed(run(), new AssistantMessage(failed.content()),
                "cron-1", failed);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(fixture.mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status"));
        verify(fixture.conversations).saveMessage("cron-1", "assistant", failed.content(),
                null, "error", 12, 3, "model-a", "provider-a");
        verifyNoInteractions(fixture.completionPublisher, fixture.events);
    }

    private static CronJobRunEntity run() {
        CronJobRunEntity run = new CronJobRunEntity();
        run.setId(42L);
        run.setConversationId("cron-1");
        return run;
    }

    private static final class Fixture {
        private final CronJobRunMapper mapper = mock(CronJobRunMapper.class);
        private final ConversationService conversations = mock(ConversationService.class);
        private final ConversationCompletionPublisher completionPublisher =
                mock(ConversationCompletionPublisher.class);
        private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        private final CronJobLifecycleService service = new CronJobLifecycleService(
                mapper, conversations, completionPublisher, events, mock(I18nService.class));
    }
}
