package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the LLM-backed evaluator: prompt construction is exercised
 * via the integration with a mocked {@link ChatModel}, JSON parsing
 * corners (markdown fences, missing fields, malformed JSON), and the
 * fallback degradation paths that protect the chat turn when the
 * evaluator provider is unavailable.
 */
@ExtendWith(MockitoExtension.class)
class GoalEvaluationServiceTest {

    @Mock private ModelConfigService modelConfigService;
    @Mock private ProviderChatModelFactory chatModelFactory;
    @Mock private ChatModel chatModel;

    private GoalProperties props;
    private GoalEvaluationService svc;

    @BeforeEach
    void setUp() {
        props = new GoalProperties();
        svc = new GoalEvaluationService(props, modelConfigService, chatModelFactory, new ObjectMapper());
    }

    private GoalEntity goal() {
        GoalEntity g = new GoalEntity();
        g.setId(1L);
        g.setTitle("ship the blog");
        g.setDescription("deploy to fly.io");
        g.setExitCriteria("hello world page accessible");
        g.setStatus(GoalStatus.ACTIVE);
        return g;
    }

    /** Goal that already has a 2-item checklist — drives verdict mode. */
    private GoalEntity goalWithCriteria() {
        GoalEntity g = goal();
        g.setCriteria("[{\"id\":\"C1\",\"text\":\"DNS configured\",\"passed\":false,\"evidence\":\"\"},"
                + "{\"id\":\"C2\",\"text\":\"TLS enabled\",\"passed\":false,\"evidence\":\"\"}]");
        return g;
    }

    private ModelConfigEntity model(String name) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider("dashscope");
        m.setModelName(name);
        return m;
    }

    private void stubChatResponse(String body) {
        when(modelConfigService.getDefaultModel()).thenReturn(model("qwen-turbo"));
        when(chatModelFactory.buildFor(any(ModelConfigEntity.class), any(RetryTemplate.class)))
                .thenReturn(chatModel);
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage(body))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    // ==================== Pre-flight guards ====================

    @Test
    void nullGoal_returnsFallback_withoutTouchingProviders() {
        GoalEvaluationResult r = svc.evaluate(null, List.of(), "anything");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertFalse(r.completed());
        assertEquals(0, r.llmCallsConsumed());
        verify(chatModelFactory, never()).buildFor(any(), any());
    }

    @Test
    void emptyAnswer_returnsFallback_withoutTouchingProviders() {
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        verify(chatModelFactory, never()).buildFor(any(), any());
    }

    @Test
    void noModelAvailable_returnsFallback() {
        // Both lookup paths return null — no default, no override.
        when(modelConfigService.getDefaultModel()).thenReturn(null);
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "any answer");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertTrue(r.gap().contains("no_model"));
        verify(chatModelFactory, never()).buildFor(any(), any());
    }

    // ==================== Bootstrap mode (no criteria yet) ====================

    @Test
    void bootstrap_createsChecklist_fromDraftJson() {
        stubChatResponse("{\"criteria\":["
                + "{\"text\":\"DNS configured\"},"
                + "{\"text\":\"TLS enabled\"}]}");
        GoalEvaluationResult r = svc.evaluate(goal(),
                List.of(new UserMessage("status?")), "working on it");
        assertEquals(GoalEvaluationResult.DECISION_CONTINUE, r.decision());
        assertFalse(r.completed(), "bootstrap round never completes");
        assertNotNull(r.bootstrapCriteria());
        assertEquals(2, r.bootstrapCriteria().size());
        assertEquals("C1", r.bootstrapCriteria().get(0).id());
        assertFalse(r.bootstrapCriteria().get(0).passed());
        assertEquals(1, r.llmCallsConsumed());
        assertEquals("qwen-turbo", r.evaluatorModel());
    }

    @Test
    void bootstrap_parsesMarkdownFences() {
        stubChatResponse("```json\n{\"criteria\":[{\"text\":\"only one\"}]}\n```");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertNotNull(r.bootstrapCriteria());
        assertEquals(1, r.bootstrapCriteria().size());
        assertEquals("only one", r.bootstrapCriteria().get(0).text());
    }

    @Test
    void bootstrap_emptyDraft_returnsFallback() {
        stubChatResponse("{\"criteria\":[]}");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
    }

    // ==================== Verdict mode (criteria exist) ====================

    @Test
    void verdict_partial_continues() {
        stubChatResponse("{\"criterionVerdicts\":["
                + "{\"id\":\"C1\",\"passed\":true,\"evidence\":\"page returns 200\"}],"
                + "\"summary\":\"1 of 2\"}");
        GoalEvaluationResult r = svc.evaluate(goalWithCriteria(), List.of(), "DNS done");
        assertEquals(GoalEvaluationResult.DECISION_CONTINUE, r.decision());
        assertFalse(r.completed());
        assertEquals(0.5, r.score(), 1e-9);          // 1 of 2 merged criteria passed
        assertEquals(1, r.criterionVerdicts().size());
        assertEquals(1, r.llmCallsConsumed());
    }

    @Test
    void verdict_allPassed_completes() {
        stubChatResponse("{\"criterionVerdicts\":["
                + "{\"id\":\"C1\",\"passed\":true,\"evidence\":\"200\"},"
                + "{\"id\":\"C2\",\"passed\":true,\"evidence\":\"tls ok\"}],"
                + "\"summary\":\"done\"}");
        GoalEvaluationResult r = svc.evaluate(goalWithCriteria(), List.of(), "all green");
        assertEquals(GoalEvaluationResult.DECISION_COMPLETED, r.decision());
        assertTrue(r.completed());
        assertEquals(1.0, r.score(), 1e-9);
    }

    // ==================== Parser tolerance ====================

    @Test
    void parseFails_whenNoJsonObjectInOutput() {
        stubChatResponse("I think it's about 60% done.");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertEquals(0, r.llmCallsConsumed());
    }

    @Test
    void parseFails_whenJsonMalformed() {
        stubChatResponse("{\"criteria\": }");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertTrue(r.gap().contains("parse_failed"));
    }

    // ==================== Failure modes ====================

    @Test
    void emptyResponseFromModel_returnsFallback() {
        stubChatResponse("   ");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertTrue(r.gap().contains("empty_response"));
    }

    @Test
    void modelCallThrows_returnsFallback_andDoesNotPropagate() {
        when(modelConfigService.getDefaultModel()).thenReturn(model("qwen-turbo"));
        when(chatModelFactory.buildFor(any(), any())).thenReturn(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("provider down"));
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertTrue(r.gap().contains("call_failed"));
        assertEquals(0, r.llmCallsConsumed());
    }

    // ==================== Model resolution ====================

    @Test
    void usesNamedEvaluatorModel_whenPropertySet() {
        props.setEvaluatorModel("qwen-evaluator-small");
        ModelConfigEntity named = model("qwen-evaluator-small");
        when(modelConfigService.resolveModel("qwen-evaluator-small")).thenReturn(named);
        when(chatModelFactory.buildFor(eq(named), any())).thenReturn(chatModel);
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage(
                        "{\"criteria\":[{\"text\":\"works\"}]}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        // Default lookup is never consulted when an override is configured.
        lenient().when(modelConfigService.getDefaultModel()).thenReturn(null);

        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals("qwen-evaluator-small", r.evaluatorModel());
        assertNotNull(r);
    }

    // ==================== Fallback factory ====================

    @Test
    void fallback_doesNotChargeLlmCalls() {
        GoalEvaluationResult r = GoalEvaluationResult.fallback("evaluator_unavailable");
        assertEquals(0, r.llmCallsConsumed());
        assertFalse(r.completed());
        assertTrue(r.gap().contains("evaluator unavailable"));
    }
}
