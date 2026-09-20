package vip.mate.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.*;
import vip.mate.goal.service.GoalService;
import vip.mate.goal.service.GoalServiceImpl;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Fixed service transitions on a real test database; evaluation results are fixtures, not model calls. */
final class OfflineGoalServiceTaskReplay {
    static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    enum Operation { CURRENT_REVISION_COMPLETION, APPEND_BEFORE_VERDICT, PAUSE_BEFORE_VERDICT,
        APPEND_BEFORE_BOOTSTRAP, REPLACE_DEFINITION, ABA_DEFINITION }
    record Suite(Integer schemaVersion, String suiteId, List<Task> tasks) { }
    record Task(String id, String task, String source, Operation operation, Expected expected) { }
    record Expected(GoalStatus status, Double score, Integer criteriaCount, Integer passedCount,
                    String firstCriterionText, Long evaluationRevision, Integer recordedEvalCallsUsed,
                    Integer completionCode) { }
    record Actual(GoalStatus status, Double score, int criteriaCount, int passedCount,
                  String firstCriterionText, long evaluationRevision, int recordedEvalCallsUsed, int completionCode) { }
    record Case(String id, String task, String source, Expected expected, Actual actual, boolean matched, String error) { }
    record Report(int schemaVersion, String suiteId, String suiteSha256, String revisionLabel,
                  Map<String, String> productionClassSha256, String databaseProduct, String latestMigration,
                  List<String> mockedBoundaries, String executionMode, int onlineModelCalls, String agentTaskSuccessRate, String onlineCost,
                  int matchedCases, int mismatchedCases, List<Case> cases) { }

    static Suite parse(byte[] bytes) throws IOException {
        Suite suite = JSON.readValue(bytes, Suite.class);
        require(suite != null && Integer.valueOf(1).equals(suite.schemaVersion()), "schemaVersion must be 1");
        require(nonblank(suite.suiteId()) && suite.tasks() != null && !suite.tasks().isEmpty()
                && suite.tasks().size() <= 100, "suiteId and 1..100 tasks required");
        Set<String> ids = new HashSet<>();
        for (Task task : suite.tasks()) {
            require(task != null && nonblank(task.id()) && ids.add(task.id()) && nonblank(task.task())
                    && nonblank(task.source()) && task.operation() != null, "valid unique tasks required");
            Expected e = task.expected();
            require(e != null && e.status() != null && e.score() != null && Double.isFinite(e.score())
                    && e.score() >= 0 && e.score() <= 1 && e.criteriaCount() != null && e.criteriaCount() >= 0
                    && e.passedCount() != null && e.passedCount() >= 0 && e.passedCount() <= e.criteriaCount()
                    && e.evaluationRevision() != null && e.evaluationRevision() >= 0
                    && e.recordedEvalCallsUsed() != null && e.recordedEvalCallsUsed() >= 0
                    && e.completionCode() != null && Set.of(0, 200, 409).contains(e.completionCode())
                    && (e.criteriaCount() > 0 ? nonblank(e.firstCriterionText()) : e.firstCriterionText() == null),
                    "all state expectations must be explicit and valid");
        }
        return suite;
    }

    static Report run(byte[] bytes, GoalService goals, JdbcTemplate jdbc, String revisionLabel) throws Exception {
        Suite suite = parse(bytes); // Entire input checked before any service writes.
        require(nonblank(revisionLabel), "revisionLabel required");
        List<Case> results = new ArrayList<>();
        for (Task task : suite.tasks()) {
            try {
                Actual a = execute(task.operation(), goals);
                Expected e = task.expected();
                boolean matched = e.status() == a.status() && Objects.equals(e.score(), a.score())
                        && e.criteriaCount() == a.criteriaCount() && e.passedCount() == a.passedCount()
                        && Objects.equals(e.firstCriterionText(), a.firstCriterionText())
                        && e.evaluationRevision() == a.evaluationRevision()
                        && e.recordedEvalCallsUsed() == a.recordedEvalCallsUsed() && e.completionCode() == a.completionCode();
                results.add(new Case(task.id(), task.task(), task.source(), e, a, matched, null));
            } catch (RuntimeException error) {
                results.add(new Case(task.id(), task.task(), task.source(), task.expected(), null, false,
                        error.getClass().getSimpleName()));
            }
        }
        Map<String, String> classes = new LinkedHashMap<>();
        for (Class<?> type : List.of(GoalServiceImpl.class, GoalCriteriaCodec.class, GoalEntity.class, GoalEvaluationResult.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (stream == null) throw new IOException("Missing tested class " + type.getName());
                classes.put(type.getName(), digest(stream.readAllBytes()));
            }
        }
        String database;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            database = connection.getMetaData().getDatabaseProductName();
        }
        String migration = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank DESC LIMIT 1", String.class);
        int matched = (int) results.stream().filter(Case::matched).count();
        return new Report(1, suite.suiteId(), digest(bytes), revisionLabel, classes, database, migration, List.of("MemoryManager"),
                "offline_h2_goal_service_scenarios", 0, "not_measured", "not_measured", matched,
                results.size() - matched, List.copyOf(results));
    }

    private static Actual execute(Operation operation, GoalService goals) {
        var request = new GoalCreateRequest();
        request.setConversationId("service-fixture-" + UUID.randomUUID());
        request.setAgentId(1L); request.setWorkspaceId(1L); request.setTitle("Service fixture");
        request.setAutoFollowupEnabled(false);
        request.setPersistentExecution(operation == Operation.PAUSE_BEFORE_VERDICT);
        if (operation != Operation.APPEND_BEFORE_BOOTSTRAP && operation != Operation.ABA_DEFINITION) {
            request.setCriteria(List.of(new GoalCriterion("C1", "report", false, "")));
        }
        if (operation == Operation.ABA_DEFINITION) request.setExitCriteria("A");
        Long id = goals.create(request, "fixture-owner").getId();
        var passed = verdict(true, 0);
        boolean attemptCompletion = true;
        switch (operation) {
            case CURRENT_REVISION_COMPLETION -> {
                var edit = new GoalUpdateRequest(); edit.setDescription("revised context");
                goals.update(id, edit, "fixture-owner");
                passed = verdict(true, 1);
                goals.recordEvaluation(id, passed, 0, 1);
            }
            case APPEND_BEFORE_VERDICT -> {
                goals.appendCriterion(id, "appendix", "fixture-owner");
                goals.recordEvaluation(id, passed, 0, 1);
            }
            case PAUSE_BEFORE_VERDICT -> {
                goals.recordEvaluation(id, verdict(false, 0), 0, 1);
                goals.pause(id, "fixture-owner");
                goals.recordEvaluation(id, passed, 0, 1);
            }
            case APPEND_BEFORE_BOOTSTRAP -> {
                goals.appendCriterion(id, "user appendix", "fixture-owner");
                goals.recordEvaluation(id, draft(), 0, 1);
                attemptCompletion = false;
            }
            case REPLACE_DEFINITION -> {
                goals.recordEvaluation(id, passed, 0, 1);
                var edit = new GoalUpdateRequest(); edit.setExitCriteria("new requirement");
                goals.update(id, edit, "fixture-owner");
                goals.recordEvaluation(id, passed, 0, 1);
            }
            case ABA_DEFINITION -> {
                var edit = new GoalUpdateRequest(); edit.setExitCriteria("B"); goals.update(id, edit, "fixture-owner");
                edit.setExitCriteria("A"); goals.update(id, edit, "fixture-owner");
                goals.recordEvaluation(id, draft(), 0, 1);
                attemptCompletion = false;
            }
        }
        int completionCode = 0;
        if (attemptCompletion) {
            try { goals.markEvaluatedCompleted(id, passed); completionCode = 200; }
            catch (MateClawException denied) { completionCode = denied.getCode(); }
        }
        GoalEntity saved = goals.getById(id);
        List<GoalCriterion> criteria = GoalCriteriaCodec.parse(saved.getCriteria(), JSON);
        return new Actual(saved.getStatus(), saved.getCompletionScore(), criteria.size(),
                (int) criteria.stream().filter(GoalCriterion::passed).count(),
                criteria.isEmpty() ? null : criteria.getFirst().text(), saved.getEvaluationRevision(),
                saved.getEvalLlmCallsUsed(), completionCode);
    }

    private static GoalEvaluationResult verdict(boolean passed, long revision) {
        return new GoalEvaluationResult(passed ? 1.0 : 0.0, passed ? "" : "missing report",
                passed ? "completed" : "continue", passed, "service-fixture", 1, 0,
                List.of(new GoalChecklistVerdict.CriterionVerdict("C1", passed, passed ? "fixture report evidence" : "")),
                null).withEvaluationRevision(revision);
    }
    private static GoalEvaluationResult draft() {
        return new GoalEvaluationResult(0, "draft", "continue", false, "service-fixture", 1, 0,
                List.of(), List.of(new GoalCriterion("C1", "model draft", false, "")));
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
