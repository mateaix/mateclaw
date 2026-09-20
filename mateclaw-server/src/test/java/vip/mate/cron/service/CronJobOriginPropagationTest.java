package vip.mate.cron.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.cron.CronChatOriginFactory;
import vip.mate.cron.CronConversationResolver;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;
import vip.mate.i18n.I18nService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CronJobOriginPropagationTest {

    private static final Long JOB_ID = 11L;
    private static final Long AGENT_ID = 22L;
    private static final Long WORKSPACE_ID = 33L;
    private static final Long MESSAGE_ID = 44L;
    private static final String CONVERSATION_ID = "tasks_33";

    @Test
    void lifecycleReturnsThePersistedUserMessageIdWithoutSavingTwice() {
        ConversationService conversations = mock(ConversationService.class);
        CronJobRunMapper runMapper = mock(CronJobRunMapper.class);
        CronJobLifecycleService lifecycle = new CronJobLifecycleService(
                runMapper, conversations,
                mock(ConversationCompletionPublisher.class),
                mock(ApplicationEventPublisher.class), mock(I18nService.class));
        CronJobEntity job = job();
        MessageEntity saved = new MessageEntity();
        saved.setId(MESSAGE_ID);
        when(conversations.saveMessage(CONVERSATION_ID, "user", "do work"))
                .thenReturn(saved);

        CronJobLifecycleService.StartResult result = lifecycle.startRun(
                job, "do work", "scheduled", CONVERSATION_ID);

        assertEquals(MESSAGE_ID, result.originMessageId());
        assertEquals(result.run().getStartedAt(), result.run().getHeartbeatAt(),
                "a new run must be live before the first scheduled heartbeat");
        verify(conversations, times(1)).saveMessage(CONVERSATION_ID, "user", "do work");
    }

    @Test
    void runnerPassesTheLifecycleMessageIdIntoTheAgentOrigin() {
        CronJobLifecycleService lifecycle = mock(CronJobLifecycleService.class);
        AgentService agentService = mock(AgentService.class);
        CronChatOriginFactory originFactory = mock(CronChatOriginFactory.class);
        CronConversationResolver resolver = mock(CronConversationResolver.class);
        CronRunHeartbeatService heartbeat = mock(CronRunHeartbeatService.class);
        CronRunHeartbeatService.Lease lease = mock(CronRunHeartbeatService.Lease.class);
        CronJobEntity job = job();
        CronJobRunEntity run = new CronJobRunEntity();
        run.setId(55L);
        ChatOrigin origin = ChatOrigin.cron(CONVERSATION_ID, WORKSPACE_ID, null, null, null)
                .withOriginMessageId(MESSAGE_ID);
        when(resolver.resolve(job)).thenReturn(CONVERSATION_ID);
        when(lifecycle.startRun(job, "do work", "scheduled", CONVERSATION_ID))
                .thenReturn(new CronJobLifecycleService.StartResult(run, MESSAGE_ID));
        when(originFactory.from(job, CONVERSATION_ID, MESSAGE_ID)).thenReturn(origin);
        when(heartbeat.begin(55L)).thenReturn(lease);
        when(agentService.chatWithUsage(eq(AGENT_ID), anyString(), eq(CONVERSATION_ID), eq(origin.withExecutionAttribution(new ExecutionAttribution(null, null, 55L, null, "cron:55")))))
                .thenReturn(AgentService.ChatResult.contentOnly("done"));
        CronJobRunner runner = new CronJobRunner(lifecycle, heartbeat, agentService, originFactory, resolver,
                mock(WikiProcessingService.class), new ObjectMapper());

        runner.executeJob(job);

        verify(originFactory).from(job, CONVERSATION_ID, MESSAGE_ID);
        verify(heartbeat).begin(55L);
        verify(lease).close();
        verify(agentService).chatWithUsage(eq(AGENT_ID), anyString(), eq(CONVERSATION_ID), eq(origin.withExecutionAttribution(new ExecutionAttribution(null, null, 55L, null, "cron:55"))));
        verify(agentService, never()).chatWithUsage(eq(AGENT_ID), anyString(), eq(CONVERSATION_ID));
    }

    @Test
    void runnerClosesHeartbeatWhenAgentFails() {
        CronJobLifecycleService lifecycle = mock(CronJobLifecycleService.class);
        CronRunHeartbeatService heartbeat = mock(CronRunHeartbeatService.class);
        CronRunHeartbeatService.Lease lease = mock(CronRunHeartbeatService.Lease.class);
        AgentService agentService = mock(AgentService.class);
        CronChatOriginFactory originFactory = mock(CronChatOriginFactory.class);
        CronConversationResolver resolver = mock(CronConversationResolver.class);
        CronJobEntity job = job();
        CronJobRunEntity run = new CronJobRunEntity();
        run.setId(55L);
        ChatOrigin origin = ChatOrigin.cron(CONVERSATION_ID, WORKSPACE_ID, null, null, null);
        when(resolver.resolve(job)).thenReturn(CONVERSATION_ID);
        when(lifecycle.startRun(job, "do work", "scheduled", CONVERSATION_ID))
                .thenReturn(new CronJobLifecycleService.StartResult(run, MESSAGE_ID));
        when(originFactory.from(job, CONVERSATION_ID, MESSAGE_ID)).thenReturn(origin);
        when(heartbeat.begin(55L)).thenReturn(lease);
        when(agentService.chatWithUsage(eq(AGENT_ID), anyString(), eq(CONVERSATION_ID), eq(origin.withExecutionAttribution(new ExecutionAttribution(null, null, 55L, null, "cron:55")))))
                .thenThrow(new IllegalStateException("provider timeout"));
        CronJobRunner runner = new CronJobRunner(lifecycle, heartbeat, agentService, originFactory, resolver,
                mock(WikiProcessingService.class), new ObjectMapper());

        runner.executeJob(job);

        verify(lease).close();
        verify(lifecycle).markRunFailed(eq(run), any(IllegalStateException.class));
    }

    @Test
    void runnerTreatsStructuredGraphErrorAsFailedTerminalState() {
        CronJobLifecycleService lifecycle = mock(CronJobLifecycleService.class);
        CronRunHeartbeatService heartbeat = mock(CronRunHeartbeatService.class);
        CronRunHeartbeatService.Lease lease = mock(CronRunHeartbeatService.Lease.class);
        AgentService agentService = mock(AgentService.class);
        CronChatOriginFactory originFactory = mock(CronChatOriginFactory.class);
        CronConversationResolver resolver = mock(CronConversationResolver.class);
        CronJobEntity job = job();
        CronJobRunEntity run = new CronJobRunEntity();
        run.setId(55L);
        ChatOrigin origin = ChatOrigin.cron(CONVERSATION_ID, WORKSPACE_ID, null, null, null);
        AgentService.ChatResult failed = new AgentService.ChatResult(
                "[错误] account expired", 12, 3, "model-a", "provider-a", "error_fallback");
        when(resolver.resolve(job)).thenReturn(CONVERSATION_ID);
        when(lifecycle.startRun(job, "do work", "scheduled", CONVERSATION_ID))
                .thenReturn(new CronJobLifecycleService.StartResult(run, MESSAGE_ID));
        when(originFactory.from(job, CONVERSATION_ID, MESSAGE_ID)).thenReturn(origin);
        when(heartbeat.begin(55L)).thenReturn(lease);
        when(agentService.chatWithUsage(eq(AGENT_ID), anyString(), eq(CONVERSATION_ID), eq(origin.withExecutionAttribution(new ExecutionAttribution(null, null, 55L, null, "cron:55")))))
                .thenReturn(failed);
        CronJobRunner runner = new CronJobRunner(lifecycle, heartbeat, agentService, originFactory, resolver,
                mock(WikiProcessingService.class), new ObjectMapper());

        runner.executeJob(job);

        verify(lifecycle).finishRunFailed(eq(run), any(org.springframework.ai.chat.messages.AssistantMessage.class),
                eq(CONVERSATION_ID), eq(failed));
        verify(lifecycle, never()).finishRunAndPublish(eq(job), eq(run), anyString(),
                any(org.springframework.ai.chat.messages.AssistantMessage.class), eq(CONVERSATION_ID),
                any(Boolean.class), eq(failed));
    }

    private static CronJobEntity job() {
        CronJobEntity job = new CronJobEntity();
        job.setId(JOB_ID);
        job.setAgentId(AGENT_ID);
        job.setWorkspaceId(WORKSPACE_ID);
        job.setTaskType("text");
        job.setTriggerMessage("do work");
        return job;
    }
}
