package vip.mate.goal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.exception.MateClawException;
import vip.mate.execution.evidence.service.JsonArtifactRecipe;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Only this service creates bindings, by checking managed bytes under the same goal lock. */
@Service
public class GoalJsonBindingService {
    private final JdbcTemplate jdbc;
    private final GoalJsonAcceptanceService acceptance;
    private final ManagedGoalJsonService artifacts;
    public GoalJsonBindingService(JdbcTemplate jdbc, GoalJsonAcceptanceService acceptance, ManagedGoalJsonService artifacts) {
        this.jdbc = jdbc; this.acceptance = acceptance; this.artifacts = artifacts;
    }

    public record CheckRequest(Long expectedRequirementRevision, String artifactId, Long expectedGeneration) { }
    public record Check(String criterionKey, long requirementRevision, String artifactId, long generation,
                        String status, List<String> missingFields, String recipeId, int recipeRevision,
                        Instant checkedAt, Instant expiresAt, boolean acceptanceEligible) { }
    public record State(String criterionKey, long requirementRevision, String artifactId, Long generation,
                        String status, boolean acceptanceEligible) { }
    public record Snapshot(boolean required, String status, int versionCount,
                           List<GoalJsonAcceptanceService.Requirement> requirements,
                           List<ManagedGoalJsonService.Slot> slots, List<State> checks) { }

    @Transactional
    public Snapshot snapshot(Long goalId, String username) {
        return snapshotLocked(acceptance.authorizedGoal(goalId, username, true));
    }

    @Transactional
    public Snapshot snapshotForRuntime(ChatOrigin origin) {
        return snapshotLocked(artifacts.runtimeGoal(origin).goal());
    }

    private Snapshot snapshotLocked(GoalJsonAcceptanceService.GoalScope goal) {
        int count = jdbc.queryForList("SELECT artifact_id FROM mate_goal_json_artifact WHERE goal_id=? FOR UPDATE", String.class, goal.id()).size();
        return new Snapshot(goal.required(), goal.status(), count, acceptance.requirements(goal.id()), artifacts.slots(goal.id()), statesLocked(goal.id()));
    }

    record Stored(String artifactId, long generation, String body, String sha256, int byteLength, Instant expiresAt) { }
    record Binding(long requirementRevision, long evaluationRevision, String artifactId, long generation,
                   String sha256, String recipeId, int recipeRevision, String status, Instant expiresAt) { }

    @Transactional
    public Check check(Long goalId, String key, CheckRequest request, String username) {
        var goal = acceptance.authorizedGoal(goalId, username, true);
        return checkLocked(goal, key, request);
    }

    @Transactional
    public Check checkForRuntime(ChatOrigin origin, String key, CheckRequest request) {
        var runtime = artifacts.runtimeGoal(origin);
        var result = checkLocked(runtime.goal(), key, request);
        ManagedGoalJsonService.verifyLease(runtime);
        return result;
    }

    @Transactional
    public List<State> state(Long goalId, String username) {
        acceptance.authorizedGoal(goalId, username, true);
        return statesLocked(goalId);
    }

    @Transactional
    public List<State> stateForRuntime(ChatOrigin origin) {
        return statesLocked(artifacts.runtimeGoal(origin).goal().id());
    }

    private Check checkLocked(GoalJsonAcceptanceService.GoalScope goal, String key, CheckRequest request) {
        if (!goal.required() || !List.of("active", "paused").contains(goal.status())) throw failure("Goal does not accept JSON checks");
        var requirement = acceptance.requirements(goal.id()).stream().filter(r -> r.criterionKey().equals(key)).findFirst()
                .orElseThrow(() -> failure("Current JSON requirement not found"));
        if (request == null || request.expectedRequirementRevision() == null || request.expectedGeneration() == null || request.artifactId() == null) {
            throw new MateClawException(400, "Expected requirement revision, artifact ID and generation are required");
        }
        var current = current(goal.id(), requirement.artifactSlot());
        if (request.expectedRequirementRevision() != requirement.revision() || current == null
                || !current.artifactId().equals(request.artifactId()) || request.expectedGeneration() != current.generation()) {
            throw failure("Requirement or artifact changed; reload before checking");
        }
        Instant checkedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (!current.expiresAt().isAfter(Instant.now())) throw failure("Current JSON version has expired");
        if (!intact(current)) throw failure("Managed JSON integrity check failed");
        var result = JsonArtifactRecipe.check(current.body().getBytes(StandardCharsets.UTF_8), requirement.requiredFields());
        long evaluationRevision = evaluationRevision(goal.id());
        // Goal serialization makes replacement safe across all supported database dialects.
        jdbc.update("DELETE FROM mate_goal_json_binding WHERE goal_id=? AND criterion_key=?", goal.id(), key);
        jdbc.update("""
                INSERT INTO mate_goal_json_binding
                (goal_id,criterion_key,requirement_revision,evaluation_revision,artifact_id,generation,sha256,
                recipe_id,recipe_revision,check_status,checked_at,expires_at,expires_epoch_second) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, goal.id(), key, requirement.revision(), evaluationRevision, current.artifactId(), current.generation(), current.sha256(),
                result.recipeId(), result.recipeRevision(), result.status(), Timestamp.from(checkedAt), Timestamp.from(current.expiresAt()), current.expiresAt().getEpochSecond());
        jdbc.update("UPDATE mate_agent_goal SET version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=?", goal.id());
        return new Check(key, requirement.revision(), current.artifactId(), current.generation(), result.status(), result.missingFields(),
                result.recipeId(), result.recipeRevision(), checkedAt, current.expiresAt(), "MATCH".equals(result.status()));
    }

    List<State> statesLocked(Long goalId) {
        long revision = evaluationRevision(goalId);
        return acceptance.requirements(goalId).stream().map(r -> {
            Stored current = current(goalId, r.artifactSlot());
            Binding binding = binding(goalId, r.criterionKey());
            String status;
            if (current == null) status = "NO_ARTIFACT";
            else if (!current.expiresAt().isAfter(Instant.now())) status = "EXPIRED";
            else if (!intact(current)) status = "CORRUPT";
            else if (binding == null) status = "UNBOUND";
            else if (binding.requirementRevision() != r.revision()) status = "REQUIREMENT_CHANGED";
            else if (binding.evaluationRevision() != revision) status = "GOAL_CHANGED";
            else if (!Objects.equals(binding.artifactId(), current.artifactId()) || binding.generation() != current.generation()
                    || !Objects.equals(binding.sha256(), current.sha256())) status = "SUPERSEDED";
            else if (!"json-required-fields".equals(binding.recipeId()) || binding.recipeRevision() != 1) status = "RECIPE_CHANGED";
            else if (!binding.expiresAt().equals(current.expiresAt()) || !binding.expiresAt().isAfter(Instant.now())) status = "EXPIRED";
            else if (!"MATCH".equals(binding.status())) status = binding.status();
            else status = JsonArtifactRecipe.check(current.body().getBytes(StandardCharsets.UTF_8), r.requiredFields()).status();
            return new State(r.criterionKey(), r.revision(), current == null ? null : current.artifactId(),
                    current == null ? null : current.generation(), status, "MATCH".equals(status));
        }).toList();
    }

    /** Shared completion gate. The held goal lock protects every reference until the status CAS commits. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public List<State> requireForCompletion(vip.mate.goal.model.GoalEntity expected) {
        var rows = jdbc.query("""
                SELECT version,evaluation_revision,json_acceptance_required,status FROM mate_agent_goal
                WHERE id=? AND deleted=0 FOR UPDATE
                """, (r, i) -> expected.getVersion() != null && r.getLong("version") == expected.getVersion().longValue()
                        && r.getLong("evaluation_revision") == expected.getEvaluationRevision()
                        && r.getBoolean("json_acceptance_required")
                        && Objects.equals(r.getString("status"), expected.getStatus().getValue()), expected.getId());
        if (rows.size() != 1 || !rows.getFirst()) throw failure("Goal changed before JSON completion; retry with current state");
        List<State> states = statesLocked(expected.getId());
        if (states.isEmpty()) throw failure("Required JSON contracts are unavailable");
        for (State state : states) {
            if (!state.acceptanceEligible()) throw failure("JSON requirement " + state.criterionKey() + " is not current: " + state.status());
        }
        // Recheck expiry after all recipes have run, immediately before returning to the status CAS.
        Instant now = Instant.now();
        for (State state : states) {
            Binding binding = binding(expected.getId(), state.criterionKey());
            if (binding == null || !binding.expiresAt().isAfter(now)) throw failure("JSON binding expired before completion");
        }
        return states;
    }

    private long evaluationRevision(Long goalId) {
        Long revision = jdbc.queryForObject("SELECT evaluation_revision FROM mate_agent_goal WHERE id=? AND deleted=0 FOR UPDATE", Long.class, goalId);
        if (revision == null) throw failure("Goal definition unavailable");
        return revision;
    }

    private Stored current(Long goalId, String slot) {
        var rows = jdbc.query("""
                SELECT a.* FROM mate_goal_json_slot s JOIN mate_goal_json_artifact a
                ON a.artifact_id=s.artifact_id AND a.goal_id=s.goal_id AND a.artifact_slot=s.artifact_slot AND a.generation=s.generation
                WHERE s.goal_id=? AND s.artifact_slot=? FOR UPDATE
                """, (r, i) -> new Stored(r.getString("artifact_id"), r.getLong("generation"), r.getString("json_body"),
                r.getString("sha256"), r.getInt("byte_length"), Instant.ofEpochSecond(r.getLong("expires_epoch_second"))), goalId, slot);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private Binding binding(Long goalId, String key) {
        var rows = jdbc.query("SELECT * FROM mate_goal_json_binding WHERE goal_id=? AND criterion_key=? FOR UPDATE",
                (r, i) -> new Binding(r.getLong("requirement_revision"), r.getLong("evaluation_revision"), r.getString("artifact_id"),
                        r.getLong("generation"), r.getString("sha256"), r.getString("recipe_id"), r.getInt("recipe_revision"),
                        r.getString("check_status"), Instant.ofEpochSecond(r.getLong("expires_epoch_second"))), goalId, key);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private static boolean intact(Stored current) {
        byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
        return bytes.length <= 1_048_576 && bytes.length == current.byteLength() && ManagedGoalJsonService.digest(bytes).equals(current.sha256());
    }
    private static MateClawException failure(String message) { return new MateClawException(409, message); }
}
