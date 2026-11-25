package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.team.model.*;
import vip.mate.team.repository.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamRunSummaryServiceTest {
    TeamRunMapper runs = mock(TeamRunMapper.class);
    TeamTaskMapper tasks = mock(TeamTaskMapper.class);
    TeamRunService runService = mock(TeamRunService.class);
    AgentMapper agents = mock(AgentMapper.class);
    ModelConfigService models = mock(ModelConfigService.class);
    ProviderChatModelFactory factory = mock(ProviderChatModelFactory.class);
    TeamEventChannel events = mock(TeamEventChannel.class);
    ChatModel model = mock(ChatModel.class);
    TeamRunEntity run = new TeamRunEntity();
    TeamTaskEntity task = new TeamTaskEntity();
    TeamRunSummaryService service;

    @BeforeEach void setup() {
        service = new TeamRunSummaryService(runs, tasks, runService, agents, models, factory, events);
        run.setId(20L); run.setWorkspaceId(30L); run.setTeamId(10L); run.setLeadAgentId(40L);
        run.setStatus(TeamRunStatus.FINALIZING); run.setObjective("Summarize the checks");
        task.setId(50L); task.setTaskNumber(1); task.setStatus(TeamTaskStatus.COMPLETED);
        task.setSubject("checks"); task.setResult("130 checks passed; tail verified");
        AgentEntity lead = new AgentEntity(); lead.setWorkspaceId(30L); lead.setModelName("configured-lead-model");
        when(agents.selectById(40L)).thenReturn(lead);
        ModelConfigEntity config = new ModelConfigEntity(); config.setModelName("configured-lead-model");
        config.setProvider("configured-provider"); config.setMaxInputTokens(16000);
        when(models.resolveModel("configured-lead-model")).thenReturn(config);
        when(factory.buildFor(any(), any())).thenReturn(model);
        when(runs.selectById(20L)).thenReturn(run);
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(runService.markFinalized(anyLong(), anyLong(), anyString(), anyString(), any(), any())).thenAnswer(call -> {
            TeamRunEntity finalized = new TeamRunEntity(); finalized.setStatus(TeamRunStatus.COMPLETED);
            finalized.setFinalSummary(call.getArgument(2)); return finalized;
        });
    }
    @Test void invokesConfiguredModelAndPersistsSynthesizedSummary() {
        when(model.call(any(Prompt.class))).thenReturn(response("All 130 checks passed and tail was verified.", "STOP"));
        service.finalizeRun(20L);
        verify(models).resolveModel("configured-lead-model");
        verify(runService).markFinalized(20L, 30L, "All 130 checks passed and tail was verified.", "synthesized", null, null);
        verify(events).publishRunEvent(any(), eq("team_run_completed"), anyMap());
    }
    @Test void coordinatorLeadSynthesisUsesScopedProviderFactory() {
        var policy = mock(TeamCoordinatorPolicy.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "coordinatorPolicy", policy);
        when(policy.isCoordinator(40L)).thenReturn(true);
        when(factory.buildFor(any(), any(), eq(true))).thenReturn(model);
        when(model.call(any(Prompt.class))).thenReturn(response("Scoped summary", "STOP"));
        service.finalizeRun(20L);
        verify(factory).buildFor(any(), any(), eq(true));
        verify(factory, never()).buildFor(any(), any());
        verify(runService).markFinalized(20L, 30L, "Scoped summary", "synthesized", null, null);
    }
    @Test void ordinaryLeadWithPolicyKeepsExistingProviderFactoryPath() {
        var policy = mock(TeamCoordinatorPolicy.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "coordinatorPolicy", policy);
        when(model.call(any(Prompt.class))).thenReturn(response("Ordinary summary", "STOP"));
        service.finalizeRun(20L);
        verify(factory).buildFor(any(), any());
        verify(factory, never()).buildFor(any(), any(), anyBoolean());
    }
    @Test void actualCallFailureRecordsSafeReasonAndBoundedRawEvidence() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret must not be persisted"));
        service.finalizeRun(20L);
        verify(runService).markFinalized(eq(20L), eq(30L), contains("130 checks passed"), eq("fallback"), eq("model_call_failed"), isNull());
    }
    @Test void emptyAndTruncatedResponsesAreNotSynthesized() {
        for (ChatResponse response : List.of(response("", "STOP"), response("partial summary", "LENGTH"))) {
            reset(runService);
            when(model.call(any(Prompt.class))).thenReturn(response);
            service.finalizeRun(20L);
            verify(runService).markFinalized(eq(20L), eq(30L), anyString(), eq("fallback"), anyString(), isNull());
        }
    }
    @Test void doesNotSummarizePendingReviewOrCancelledRuns() {
        task.setStatus(TeamTaskStatus.IN_REVIEW);
        service.finalizeRun(20L);
        task.setStatus(TeamTaskStatus.COMPLETED); run.setStatus(TeamRunStatus.CANCELLED);
        service.finalizeRun(20L);
        verifyNoInteractions(model, runService);
    }
    @Test void cancellationDuringCallWins() {
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            run.setStatus(TeamRunStatus.CANCELLED);
            return response("late summary", "STOP");
        });
        service.finalizeRun(20L);
        verify(runService, never()).markFinalized(anyLong(), anyLong(), anyString(), anyString(), any(), any());
    }
    @Test void effectiveProviderOutputLimitCannotOverrideSummaryBudget() {
        when(model.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder()
                .maxCompletionTokens(100000).build());
        when(model.call(any(Prompt.class))).thenReturn(response("summary", "STOP"));
        service.finalizeRun(20L);
        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        var options = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getValue().getOptions();
        assertTrue(options.getMaxCompletionTokens() <= 4096);
        assertNull(options.getMaxTokens());
    }
    @Test void retryReturningToFinalizingDoesNotPublishStaleSummary() {
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            run.setMetadata("{\"summaryEvidenceRevision\":2}");
            return response("stale summary", "STOP");
        });
        service.finalizeRun(20L);
        verify(runService, never()).markFinalized(anyLong(), anyLong(), anyString(), anyString(), any(), any());
    }
    @Test void schedulerDoesNotStartDuplicateCallsForAnInFlightRun() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(runs.selectList(any())).thenReturn(List.of(run));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS);
            return response("summary", "STOP");
        });
        service.summarizeFinalizingRuns();
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        service.summarizeFinalizingRuns();
        verify(factory, times(1)).buildFor(any(), any());
        release.countDown();
        verify(runService, timeout(2000)).markFinalized(anyLong(), anyLong(), anyString(), eq("synthesized"), isNull(), isNull());
    }
    @Test void timeoutFallsBackAndInterruptsProvider() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "timeoutMillis", 20L);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Thread.sleep(5000); return response("too late", "STOP");
        });
        service.finalizeRun(20L);
        verify(runService).markFinalized(eq(20L), eq(30L), anyString(), eq("fallback"), eq("model_timeout"), isNull());
    }
    @org.junit.jupiter.api.AfterEach void cleanup() { service.close(); }
    @Test void promptHasTotalBudgetTailAndRetrievalReferences() {
        task.setResult("HEAD-" + "x".repeat(100000) + "-TAIL");
        String prompt = TeamRunSummaryService.taskEvidence(List.of(task));
        assertTrue(prompt.length() <= TeamRunSummaryService.EVIDENCE_CHARS);
        assertTrue(prompt.contains("HEAD-")); assertTrue(prompt.contains("-TAIL"));
        assertTrue(prompt.contains("output")); assertTrue(prompt.contains("50"));
    }
    private static ChatResponse response(String text, String finish) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finish).build())));
    }
}
