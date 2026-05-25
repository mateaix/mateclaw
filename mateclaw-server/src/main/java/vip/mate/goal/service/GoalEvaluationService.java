package vip.mate.goal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates whether the assistant's latest reply satisfies a goal's exit
 * criteria. Drives the persistent-goal completion path (the
 * "auto-followup until score hits 1.0" loop), so it sits on the hot path
 * of every chat turn that has an active goal.
 *
 * <p>Returns a deterministic {@link GoalEvaluationResult#fallback fallback}
 * when the LLM call is unavailable, errors out, or returns un-parseable
 * JSON — the {@code GoalEvaluationNode} treats fallback as "skip
 * bookkeeping deltas, no event log, stay safe". This degrades cleanly
 * when the evaluator provider is misconfigured or transiently down.
 *
 * <p>Model selection:
 * <ol>
 *   <li>If {@code mateclaw.goal.evaluator-model} names an enabled model,
 *       use it.</li>
 *   <li>Otherwise fall back to {@link ModelConfigService#getDefaultModel()}
 *       — convenient for dev, but operators are encouraged to pin a cheap
 *       evaluator-only model in production since this fires on every turn.
 *   </li>
 * </ol>
 *
 * <p>Prompt is short and JSON-only: the evaluator returns one object with
 * {@code score} (0.0–1.0 fraction of criteria satisfied), {@code gap}
 * (plain-text description of what's missing), and {@code completed} (bool).
 */
@Slf4j
@Service
public class GoalEvaluationService {

    /**
     * Token budget for the evaluator response. Reasoning-mode models
     * (DeepSeek V4 Pro, Kimi for Coding, GLM-Z1, …) consume a chunk of
     * this budget on internal {@code <think>} content before emitting
     * the JSON answer; 400 was empirically too tight and produced
     * empty responses on every reasoning provider. 2000 leaves comfort
     * for ~1500 tokens of reasoning + the small JSON object we need.
     */
    private static final int MAX_OUTPUT_TOKENS = 2000;
    private static final int MAX_CONVERSATION_CHARS = 6_000;
    private static final int MAX_TERMINAL_ANSWER_CHARS = 4_000;
    /** Skip-retry template — the goal node has its own try/catch, no need to double-retry. */
    private static final RetryTemplate ONESHOT = RetryTemplate.builder().maxAttempts(1).build();

    private final GoalProperties properties;
    private final ModelConfigService modelConfigService;
    private final ProviderChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;

    public GoalEvaluationService(GoalProperties properties,
                                 ModelConfigService modelConfigService,
                                 ProviderChatModelFactory chatModelFactory,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.modelConfigService = modelConfigService;
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate one terminal answer against the goal's exit criteria.
     *
     * <p>Returns a {@link GoalEvaluationResult} carrying the score, gap
     * description, decision, model id, and elapsed latency. The
     * {@code llmCallsConsumed} field is 1 on success (one evaluator
     * call) and 0 on fallback paths so the per-goal LLM-call budget
     * stays accurate.
     *
     * @param goal            the active goal under evaluation; never {@code null}
     * @param recentMessages  most-recent N messages from the parent conversation
     *                        for context; the node already trims by
     *                        {@link GoalProperties#getEvaluatorContextMessages()}
     * @param terminalAnswer  the assistant's just-emitted final answer text
     */
    public GoalEvaluationResult evaluate(GoalEntity goal,
                                         List<? extends Message> recentMessages,
                                         String terminalAnswer) {
        if (goal == null) {
            return GoalEvaluationResult.fallback("no_goal");
        }
        if (terminalAnswer == null || terminalAnswer.isBlank()) {
            return GoalEvaluationResult.fallback("empty_answer");
        }

        ModelConfigEntity model = resolveEvaluatorModel();
        if (model == null) {
            log.warn("[GoalEvaluation] no evaluator model available (configured={}, default lookup empty)",
                    properties.getEvaluatorModel());
            return GoalEvaluationResult.fallback("no_model");
        }

        long start = System.currentTimeMillis();
        try {
            ChatModel chatModel = chatModelFactory.buildFor(model, ONESHOT);
            String prompt = buildUserPrompt(goal, recentMessages, terminalAnswer);

            List<Message> messages = new ArrayList<>(2);
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.add(new UserMessage(prompt));

            ChatOptions options = ChatOptions.builder()
                    .temperature(0.1)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .build();

            ChatResponse response = chatModel.call(new Prompt(messages, options));
            long elapsed = System.currentTimeMillis() - start;

            String body = extractText(response);
            if (body == null || body.isBlank()) {
                log.warn("[GoalEvaluation] empty response from evaluator model={}", model.getModelName());
                return GoalEvaluationResult.fallback("empty_response");
            }

            return parseJson(body, model.getModelName(), elapsed);
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[GoalEvaluation] evaluator call failed after {}ms: {}", elapsed, t.toString());
            return GoalEvaluationResult.fallback("call_failed");
        }
    }

    // ==================== Internals ====================

    private ModelConfigEntity resolveEvaluatorModel() {
        String name = properties.getEvaluatorModel();
        if (name != null && !name.isBlank()) {
            // resolveModel returns the default model when the named one
            // can't be found, which is exactly the desired "graceful
            // degradation" semantics for a misconfigured evaluator id.
            return modelConfigService.resolveModel(name);
        }
        return modelConfigService.getDefaultModel();
    }

    private static final String SYSTEM_PROMPT =
            "You are a goal-completion evaluator. You judge whether an AI "
                    + "assistant's latest reply satisfies a user's stated goal. "
                    + "Output exactly ONE JSON object with the keys score, gap, "
                    + "completed. No markdown, no commentary, no extra prose.";

    private String buildUserPrompt(GoalEntity goal,
                                   List<? extends Message> recentMessages,
                                   String terminalAnswer) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Goal title: ").append(safe(goal.getTitle())).append('\n');
        if (goal.getDescription() != null && !goal.getDescription().isBlank()) {
            sb.append("Goal description: ").append(safe(goal.getDescription())).append('\n');
        }
        if (goal.getExitCriteria() != null && !goal.getExitCriteria().isBlank()) {
            sb.append("Exit criteria:\n").append(safe(goal.getExitCriteria())).append('\n');
        }
        sb.append('\n');

        if (recentMessages != null && !recentMessages.isEmpty()) {
            sb.append("Recent conversation (oldest first):\n");
            String convo = serializeMessages(recentMessages);
            if (convo.length() > MAX_CONVERSATION_CHARS) {
                convo = convo.substring(convo.length() - MAX_CONVERSATION_CHARS);
            }
            sb.append(convo).append('\n');
        }

        String answer = terminalAnswer;
        if (answer.length() > MAX_TERMINAL_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_TERMINAL_ANSWER_CHARS) + "\n... [truncated]";
        }
        sb.append("\nAssistant's latest final answer to evaluate:\n").append(answer).append('\n');

        sb.append('\n')
          .append("Return exactly:\n")
          .append("{\n")
          .append("  \"score\": <number 0.0 to 1.0 — fraction of exit criteria satisfied>,\n")
          .append("  \"gap\": \"<short plain-text description of what's still missing; empty when score=1.0>\",\n")
          .append("  \"completed\": <true if every exit criterion is fully satisfied, else false>\n")
          .append("}");
        return sb.toString();
    }

    private String serializeMessages(List<? extends Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            String role = m.getMessageType() != null ? m.getMessageType().getValue() : "msg";
            String text = m.getText();
            if (text == null) text = "";
            sb.append(role).append(": ").append(text.strip()).append('\n');
        }
        return sb.toString();
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        var output = response.getResult().getOutput();
        String text = output.getText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        // Fallback for reasoning models: some providers (DeepSeek-style
        // OpenAI-compatible streaming, MiMo) emit the entire output as
        // `reasoning_content` and leave the regular content field empty
        // when the token budget gets eaten by thinking. The JSON object
        // we want often appears at the tail of the reasoning trace.
        var metadata = output.getMetadata();
        if (metadata != null) {
            Object rc = metadata.get("reasoningContent");
            if (rc instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return text;
    }

    /**
     * Parse the evaluator's JSON output. The model may wrap the object in
     * ```json fences despite the system prompt telling it not to, so we
     * locate the first {@code {...}} substring and parse that. Anything
     * else (non-numeric score, missing fields, malformed JSON) downgrades
     * to a fallback result rather than throwing.
     */
    private GoalEvaluationResult parseJson(String body, String modelName, long latencyMs) {
        String trimmed = body.strip();
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            log.warn("[GoalEvaluation] no JSON object in evaluator output: {}",
                    trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed);
            return GoalEvaluationResult.fallback("parse_no_object");
        }
        String json = trimmed.substring(braceStart, braceEnd + 1);
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode scoreNode = node.get("score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                return GoalEvaluationResult.fallback("parse_missing_score");
            }
            double score = clamp01(scoreNode.asDouble());
            String gap = node.hasNonNull("gap") ? node.get("gap").asText("") : "";
            boolean completed = node.hasNonNull("completed") && node.get("completed").asBoolean(false);
            // Belt-and-braces: a perfect score implies completion; let the
            // node's >= 0.95 threshold handle the gray zone.
            if (score >= 1.0 - 1e-9) completed = true;
            String decision = completed
                    ? GoalEvaluationResult.DECISION_COMPLETED
                    : GoalEvaluationResult.DECISION_CONTINUE;
            return new GoalEvaluationResult(
                    score, gap, decision, completed,
                    modelName != null ? modelName : "", 1, latencyMs);
        } catch (Exception e) {
            log.warn("[GoalEvaluation] JSON parse failed: {} — body={}",
                    e.getMessage(),
                    json.length() > 200 ? json.substring(0, 200) + "..." : json);
            return GoalEvaluationResult.fallback("parse_failed");
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
