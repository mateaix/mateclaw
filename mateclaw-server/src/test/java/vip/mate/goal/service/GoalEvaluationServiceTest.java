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

    // ==================== Happy paths ====================

    @Test
    void continueDecision_whenScoreBelowOne() {
        stubChatResponse("{\"score\": 0.6, \"gap\": \"DNS not configured yet\", \"completed\": false}");
        GoalEvaluationResult r = svc.evaluate(goal(),
                List.of(new UserMessage("status?")),
                "DNS configured, still need TLS");
        assertEquals(GoalEvaluationResult.DECISION_CONTINUE, r.decision());
        assertFalse(r.completed());
        assertEquals(0.6, r.score(), 1e-9);
        assertEquals("DNS not configured yet", r.gap());
        assertEquals(1, r.llmCallsConsumed());
        assertEquals("qwen-turbo", r.evaluatorModel());
    }

    @Test
    void completedDecision_whenJsonSaysCompleted() {
        stubChatResponse("{\"score\": 0.95, \"gap\": \"\", \"completed\": true}");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "all green");
        assertEquals(GoalEvaluationResult.DECISION_COMPLETED, r.decision());
        assertTrue(r.completed());
    }

    @Test
    void scoreOfOne_implicitlyCompletes_evenWhenJsonSaysFalse() {
        stubChatResponse("{\"score\": 1.0, \"gap\": \"\", \"completed\": false}");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "perfect answer");
        assertTrue(r.completed(), "score=1.0 must imply completed regardless of the bool field");
        assertEquals(GoalEvaluationResult.DECISION_COMPLETED, r.decision());
    }

    @Test
    void score_clampedTo01_whenModelReturnsOutOfRange() {
        stubChatResponse("{\"score\": 1.7, \"gap\": \"\", \"completed\": true}");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "answer");
        assertEquals(1.0, r.score(), 1e-9);
    }

    @Test
    void negativeScore_clampedToZero() {
        stubChatResponse("{\"score\": -0.2, \"gap\": \"x\", \"completed\": false}");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "answer");
        assertEquals(0.0, r.score(), 1e-9);
    }

    // ==================== Parser tolerance ====================

    @Test
    void parsesEvenWhenWrappedInMarkdownFences() {
        // Lenient stub: parser tolerance shouldn't depend on a specific code path.
        stubChatResponse("```json\n"
                + "{\"score\": 0.4, \"gap\": \"still need TLS\", \"completed\": false}\n"
                + "```");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "DNS set up");
        assertEquals(GoalEvaluationResult.DECISION_CONTINUE, r.decision());
        assertEquals(0.4, r.score(), 1e-9);
        assertEquals("still need TLS", r.gap());
    }

    @Test
    void parseFails_whenNoJsonObjectInOutput() {
        stubChatResponse("I think it's about 60% done.");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertEquals(0, r.llmCallsConsumed());
    }

    @Test
    void parseFails_whenScoreFieldMissing() {
        stubChatResponse("{\"gap\": \"missing\", \"completed\": false}");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "x");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertTrue(r.gap().contains("parse_missing_score"));
    }

    @Test
    void parseFails_whenJsonMalformed() {
        // Closing brace present but interior is invalid — exercises the
        // ObjectMapper.readTree exception path rather than the cheaper
        // "no object found" pre-check.
        stubChatResponse("{\"score\": 0.5, \"gap\": }");
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
                        "{\"score\":0.5,\"gap\":\"\",\"completed\":false}"))));
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
