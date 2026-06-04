package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalChecklistVerdict;
import vip.mate.goal.model.GoalCriteriaCodec;
import vip.mate.goal.model.GoalCriteriaDraft;
import vip.mate.goal.model.GoalCriterion;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates whether the assistant's latest reply satisfies a goal's exit
 * checklist, and bootstraps that checklist on first run. Sits on the hot
 * path of every chat turn that has an active goal.
 *
 * <p>Two evaluation modes, chosen by whether the goal already has criteria:
 * <ul>
 *   <li><b>Bootstrap</b> (no criteria yet): decompose the goal into a set of
 *       verifiable criteria and return them as
 *       {@link GoalEvaluationResult#bootstrapCriteria()}. Completion is not
 *       judged on this round.</li>
 *   <li><b>Verdict</b> (criteria exist): take a position on each existing
 *       criterion by id (passed + concrete evidence) and return the delta as
 *       {@link GoalEvaluationResult#criterionVerdicts()}. Completion is
 *       derived from "all criteria passed" after the merge.</li>
 * </ul>
 *
 * <p>Output is shaped by {@link BeanOutputConverter}, which injects a JSON
 * format instruction and parses the reply. On any failure (no model, empty
 * reply, unparseable output, provider error) a deterministic
 * {@link GoalEvaluationResult#fallback fallback} is returned so the node can
 * degrade cleanly.
 *
 * <p>Implements Spring AI's {@link Evaluator} for interface uniformity and
 * testability; the goal-aware overloads carry the context the generic SPI
 * request cannot.
 */
@Slf4j
@Service
public class GoalEvaluationService implements Evaluator {

    /**
     * Token budget for the evaluator response. Reasoning-mode models consume
     * a chunk of this on internal thinking before emitting JSON; 2000 leaves
     * comfort for the reasoning trace plus the small object we need.
     */
    private static final int MAX_OUTPUT_TOKENS = 2000;
    private static final int MAX_CONVERSATION_CHARS = 6_000;
    private static final int MAX_TERMINAL_ANSWER_CHARS = 4_000;
    private static final int MIN_BOOTSTRAP_CRITERIA = 1;
    private static final int MAX_BOOTSTRAP_CRITERIA = 8;
    /** Skip-retry template — the goal node has its own try/catch. */
    private static final RetryTemplate ONESHOT = RetryTemplate.builder().maxAttempts(1).build();

    private final GoalProperties properties;
    private final ModelConfigService modelConfigService;
    private final ProviderChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;

    private final BeanOutputConverter<GoalCriteriaDraft> draftConverter =
            new BeanOutputConverter<>(GoalCriteriaDraft.class);
    private final BeanOutputConverter<GoalChecklistVerdict> verdictConverter =
            new BeanOutputConverter<>(GoalChecklistVerdict.class);

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
     * Evaluate one terminal answer against the goal's checklist (or bootstrap
     * the checklist when none exists yet).
     *
     * @param goal           the active goal under evaluation; never {@code null}
     * @param recentMessages most-recent N messages for context (already trimmed)
     * @param terminalAnswer the assistant's just-emitted final answer text
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
            log.warn("[GoalEvaluation] no evaluator model available (configured={})",
                    properties.getEvaluatorModel());
            return GoalEvaluationResult.fallback("no_model");
        }

        List<GoalCriterion> existing = GoalCriteriaCodec.parse(goal.getCriteria(), objectMapper);
        boolean bootstrap = existing.isEmpty();

        long start = System.currentTimeMillis();
        try {
            ChatModel chatModel = chatModelFactory.buildFor(model, ONESHOT);
            String format = bootstrap ? draftConverter.getFormat() : verdictConverter.getFormat();
            String userPrompt = buildUserPrompt(goal, existing, recentMessages, terminalAnswer, bootstrap)
                    + "\n\n" + format;

            List<Message> messages = new ArrayList<>(2);
            messages.add(new SystemMessage(bootstrap ? BOOTSTRAP_SYSTEM_PROMPT : VERDICT_SYSTEM_PROMPT));
            messages.add(new UserMessage(userPrompt));

            ChatOptions options = ChatOptions.builder()
                    .temperature(0.1)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .build();

            ChatResponse response = chatModel.call(new Prompt(messages, options));
            long elapsed = System.currentTimeMillis() - start;

            String body = extractText(response);
            if (body == null || body.isBlank()) {
                log.warn("[GoalEvaluation] empty response from evaluator model={}", model.getModelName());
                // The call was really spent — bill it.
                return GoalEvaluationResult.fallbackAfterCall("empty_response", model.getModelName(), elapsed);
            }

            return bootstrap
                    ? parseBootstrap(body, model.getModelName(), elapsed)
                    : parseVerdict(body, existing, model.getModelName(), elapsed);
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[GoalEvaluation] evaluator call failed after {}ms: {}", elapsed, t.toString());
            return GoalEvaluationResult.fallback("call_failed");
        }
    }

    // ==================== Evaluator SPI ====================

    /**
     * Generic SPI surface: judge whether {@code request.getResponseContent()}
     * satisfies the objective stated in {@code request.getUserText()}.
     *
     * <p>The objective is wrapped as a single checklist criterion so the call
     * runs in <b>verdict</b> mode (a real pass/fail judgement of the response),
     * not bootstrap mode. {@code isPass()} is true only when that criterion is
     * satisfied; detail rides in {@code metadata.criterionVerdicts}.
     *
     * <p>Goal-aware callers use the {@link #evaluate(GoalEntity, List, String)}
     * overload, which carries the full multi-criterion checklist context the
     * generic request cannot.
     */
    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        String objective = request.getUserText() != null ? request.getUserText() : "";
        GoalEntity probe = new GoalEntity();
        probe.setTitle("Does the response satisfy the objective?");
        probe.setDescription(objective);
        // One criterion = the objective -> non-empty criteria -> verdict mode.
        probe.setCriteria(GoalCriteriaCodec.serialize(
                List.of(new GoalCriterion("C1", objective, false, "")), objectMapper));

        GoalEvaluationResult r = evaluate(probe, List.of(), request.getResponseContent());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decision", r.decision());
        metadata.put("criterionVerdicts", r.criterionVerdicts());
        return new EvaluationResponse(r.completed(), (float) r.score(),
                r.gap() == null ? "" : r.gap(), metadata);
    }

    // ==================== Internals ====================

    private ModelConfigEntity resolveEvaluatorModel() {
        String name = properties.getEvaluatorModel();
        if (name != null && !name.isBlank()) {
            // resolveModel returns the default model when the named one can't
            // be found — the desired graceful-degradation semantics.
            return modelConfigService.resolveModel(name);
        }
        return modelConfigService.getDefaultModel();
    }

    private static final String BOOTSTRAP_SYSTEM_PROMPT =
            "You decompose a user's goal into a short checklist of concrete, "
                    + "independently verifiable acceptance criteria. Each criterion "
                    + "must be checkable from observable evidence (an output, a file, "
                    + "a command result), not a vague aspiration. Output only the "
                    + "requested JSON.";

    private static final String VERDICT_SYSTEM_PROMPT =
            "You judge, criterion by criterion, whether an AI assistant's latest "
                    + "reply satisfies a goal's checklist. For each criterion you MUST "
                    + "cite concrete evidence from the reply (an output line, a file "
                    + "excerpt, a command result). Do NOT accept generic phrases like "
                    + "'all requirements met'. If a criterion lacks specific evidence, "
                    + "mark it not passed. Output only the requested JSON.";

    private String buildUserPrompt(GoalEntity goal,
                                   List<GoalCriterion> existing,
                                   List<? extends Message> recentMessages,
                                   String terminalAnswer,
                                   boolean bootstrap) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Goal title: ").append(safe(goal.getTitle())).append('\n');
        if (goal.getDescription() != null && !goal.getDescription().isBlank()) {
            sb.append("Goal description: ").append(safe(goal.getDescription())).append('\n');
        }
        if (goal.getExitCriteria() != null && !goal.getExitCriteria().isBlank()) {
            sb.append("Exit criteria (free text):\n").append(safe(goal.getExitCriteria())).append('\n');
        }
        sb.append('\n');

        if (!bootstrap) {
            sb.append("Current checklist (judge each by id):\n");
            for (GoalCriterion c : existing) {
                sb.append("- ").append(c.id()).append(": ").append(c.text()).append('\n');
            }
            sb.append('\n');
        }

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

        sb.append('\n');
        if (bootstrap) {
            sb.append("Produce between ").append(MIN_BOOTSTRAP_CRITERIA).append(" and ")
              .append(MAX_BOOTSTRAP_CRITERIA)
              .append(" criteria. Leave every 'passed' false and 'evidence' empty — "
                      + "this round only defines the checklist.");
        } else {
            sb.append("For every criterion above, return its id with passed=true ONLY when "
                    + "the reply shows concrete evidence; otherwise passed=false with a short "
                    + "note of what is missing.");
        }
        return sb.toString();
    }

    private GoalEvaluationResult parseBootstrap(String body, String modelName, long latencyMs) {
        try {
            GoalCriteriaDraft dto = draftConverter.convert(stripFences(body));
            if (dto == null || dto.criteria() == null || dto.criteria().isEmpty()) {
                return GoalEvaluationResult.fallbackAfterCall("bootstrap_empty", modelName, latencyMs);
            }
            List<GoalCriterion> normalized = new ArrayList<>();
            for (GoalCriterion c : dto.criteria()) {
                if (c != null && c.text() != null && !c.text().isBlank()) {
                    normalized.add(new GoalCriterion("", c.text().trim(), false, ""));
                }
                // Hard cap regardless of what the model returned — the prompt
                // asks for <= MAX but a verbose model could exceed it.
                if (normalized.size() >= MAX_BOOTSTRAP_CRITERIA) {
                    break;
                }
            }
            if (normalized.isEmpty()) {
                return GoalEvaluationResult.fallbackAfterCall("bootstrap_empty", modelName, latencyMs);
            }
            normalized = GoalCriteriaCodec.reindex(normalized);
            // Bootstrap never judges completion: the checklist is freshly created.
            return new GoalEvaluationResult(
                    0.0, "checklist created", GoalEvaluationResult.DECISION_CONTINUE, false,
                    modelName != null ? modelName : "", 1, latencyMs,
                    List.of(), normalized);
        } catch (Exception e) {
            log.warn("[GoalEvaluation] bootstrap parse failed: {}", e.getMessage());
            return GoalEvaluationResult.fallbackAfterCall("parse_failed", modelName, latencyMs);
        }
    }

    private GoalEvaluationResult parseVerdict(String body,
                                              List<GoalCriterion> existing,
                                              String modelName,
                                              long latencyMs) {
        try {
            GoalChecklistVerdict verdict = verdictConverter.convert(stripFences(body));
            List<GoalChecklistVerdict.CriterionVerdict> deltas =
                    verdict != null && verdict.criterionVerdicts() != null
                            ? verdict.criterionVerdicts() : List.of();
            List<GoalCriterion> merged = GoalCriteriaCodec.merge(existing, deltas);
            boolean completed = GoalCriteriaCodec.allPassed(merged);
            int total = merged.size();
            int passed = (int) merged.stream().filter(GoalCriterion::passed).count();
            double score = total == 0 ? 0.0 : (double) passed / total;
            String gap = completed ? "" : buildGap(GoalCriteriaCodec.remaining(merged));
            String decision = completed
                    ? GoalEvaluationResult.DECISION_COMPLETED
                    : GoalEvaluationResult.DECISION_CONTINUE;
            return new GoalEvaluationResult(
                    score, gap, decision, completed,
                    modelName != null ? modelName : "", 1, latencyMs,
                    deltas, null);
        } catch (Exception e) {
            log.warn("[GoalEvaluation] verdict parse failed: {}", e.getMessage());
            return GoalEvaluationResult.fallbackAfterCall("parse_failed", modelName, latencyMs);
        }
    }

    private static String buildGap(List<GoalCriterion> remaining) {
        if (remaining.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Still missing: ");
        for (int i = 0; i < remaining.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(remaining.get(i).text());
        }
        return sb.toString();
    }

    /** Strip ```json fences the model may add despite instructions. */
    private static String stripFences(String body) {
        String t = body.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.strip();
    }

    private String serializeMessages(List<? extends Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            String role = m.getMessageType() != null ? m.getMessageType().getValue() : "msg";
            String text = m.getText();
            if (text == null) {
                text = "";
            }
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
        // Fallback for reasoning models that emit everything as reasoningContent
        // and leave the regular content empty; the JSON often tails the trace.
        var metadata = output.getMetadata();
        if (metadata != null) {
            Object rc = metadata.get("reasoningContent");
            if (rc instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return text;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
