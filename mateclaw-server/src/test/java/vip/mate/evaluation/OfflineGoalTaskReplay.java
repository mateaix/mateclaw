package vip.mate.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCriteriaCodec;
import vip.mate.goal.model.GoalCriterion;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.service.GoalEvaluationService;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Offline policy replay. The model is a fixture: no agent task is actually executed. */
final class OfflineGoalTaskReplay {
    static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final String MODE = "offline_synthetic_evaluator_replay";

    record Suite(Integer schemaVersion, String suiteId, List<Task> tasks) { }
    record Task(String id, String task, String source, String boundary, Boolean persistent,
                List<GoalCriterion> criteria, String terminalAnswer, String evaluatorResponse,
                Expected expected) { }
    record Expected(Boolean completed, Double score, String decision, List<String> remainingIds,
                    Integer fixtureCalls) { }
    record Actual(boolean completed, double score, String decision, List<String> remainingIds,
                  int fixtureCalls) { }
    record CaseResult(String id, String task, String source, String boundary, Expected expected,
                      Actual actual, boolean matched, String error) { }
    record Report(int schemaVersion, String suiteId, String suiteSha256, String codeRevision,
                  String revisionSource, Map<String, String> executedClassSha256, String executionMode, int onlineModelCalls, String agentTaskSuccessRate,
                  String onlineCost, int matchedCases, int mismatchedCases, List<CaseResult> cases) { }

    static Suite parse(byte[] bytes) throws IOException {
        Suite suite = JSON.readValue(bytes, Suite.class);
        require(suite != null && Integer.valueOf(1).equals(suite.schemaVersion()), "schemaVersion must be 1");
        require(nonblank(suite.suiteId()), "suiteId is required");
        require(suite.tasks() != null && !suite.tasks().isEmpty() && suite.tasks().size() <= 100,
                "suite must contain 1..100 tasks");
        Set<String> ids = new HashSet<>();
        for (Task task : suite.tasks()) {
            require(task != null && nonblank(task.id()) && ids.add(task.id()), "task IDs must be unique and nonblank");
            require(nonblank(task.task()) && nonblank(task.source()) && nonblank(task.boundary()),
                    task.id() + ": task, source and boundary are required");
            require(task.persistent() != null && task.criteria() != null && task.terminalAnswer() != null
                    && task.evaluatorResponse() != null, task.id() + ": missing replay inputs");
            Set<String> criterionIds = new HashSet<>();
            for (GoalCriterion criterion : task.criteria()) {
                require(criterion != null && nonblank(criterion.id()) && nonblank(criterion.text())
                        && criterionIds.add(criterion.id()), task.id() + ": invalid or duplicate criterion");
            }
            Expected expected = task.expected();
            require(expected != null && expected.completed() != null && expected.score() != null
                    && Double.isFinite(expected.score()) && expected.score() >= 0 && expected.score() <= 1
                    && Set.of("completed", "continue", "fallback").contains(expected.decision() == null ? "" : expected.decision())
                    && expected.remainingIds() != null && expected.fixtureCalls() != null
                    && expected.fixtureCalls() >= 0 && expected.fixtureCalls() <= 1,
                    task.id() + ": incomplete or invalid expected outcome");
            require(expected.remainingIds().stream().allMatch(OfflineGoalTaskReplay::nonblank)
                    && new HashSet<>(expected.remainingIds()).size() == expected.remainingIds().size(),
                    task.id() + ": remainingIds must be unique and nonblank");
        }
        return suite;
    }

    static Report run(byte[] bytes, String revision) throws IOException {
        Suite suite = parse(bytes); // Validate the whole suite before any fixture is replayed.
        require(nonblank(revision), "codeRevision is required");
        List<CaseResult> results = new ArrayList<>();
        for (Task task : suite.tasks()) {
            try {
                Actual actual = replay(task);
                Expected expected = task.expected();
                boolean matched = expected.completed() == actual.completed()
                        && Math.abs(expected.score() - actual.score()) < 1e-9
                        && expected.decision().equals(actual.decision())
                        && expected.remainingIds().equals(actual.remainingIds())
                        && expected.fixtureCalls() == actual.fixtureCalls();
                results.add(new CaseResult(task.id(), task.task(), task.source(), task.boundary(),
                        expected, actual, matched, null));
            } catch (RuntimeException error) {
                results.add(new CaseResult(task.id(), task.task(), task.source(), task.boundary(),
                        task.expected(), null, false, error.getClass().getSimpleName()));
            }
        }
        int matched = (int) results.stream().filter(CaseResult::matched).count();
        Map<String, String> classes = new LinkedHashMap<>();
        for (Class<?> type : List.of(GoalEvaluationService.class, GoalCriteriaCodec.class,
                GoalCriterion.class, GoalEvaluationResult.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (stream == null) throw new IOException("Missing tested class " + type.getName());
                classes.put(type.getName(), digest(stream.readAllBytes()));
            }
        }
        return new Report(1, suite.suiteId(), digest(bytes), revision, "caller_supplied_label", classes, MODE, 0,
                "not_measured", "not_measured", matched, results.size() - matched, List.copyOf(results));
    }

    private static Actual replay(Task task) {
        ChatModel chat = mock(ChatModel.class);
        ModelConfigService configs = mock(ModelConfigService.class);
        ProviderChatModelFactory factory = mock(ProviderChatModelFactory.class);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setModelName("offline-fixture");
        when(configs.getDefaultModel()).thenReturn(model);
        when(factory.buildFor(any(ModelConfigEntity.class), any(RetryTemplate.class))).thenReturn(chat);
        int[] fixtureCalls = {0};
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            fixtureCalls[0]++;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(task.evaluatorResponse()))));
        });
        GoalEntity goal = new GoalEntity();
        goal.setTitle(task.task());
        goal.setPersistentExecution(task.persistent());
        goal.setCriteria(GoalCriteriaCodec.serialize(task.criteria(), JSON));
        var evaluator = new GoalEvaluationService(new GoalProperties(), configs, factory, JSON);
        var result = evaluator.evaluate(goal, List.of(), task.terminalAnswer());
        var merged = result.bootstrapCriteria() != null ? result.bootstrapCriteria()
                : GoalCriteriaCodec.merge(task.criteria(), result.criterionVerdicts());
        return new Actual(result.completed(), result.score(), result.decision(),
                GoalCriteriaCodec.remaining(merged).stream().map(GoalCriterion::id).toList(), fixtureCalls[0]);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
