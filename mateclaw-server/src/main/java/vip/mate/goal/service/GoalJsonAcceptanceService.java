package vip.mate.goal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.execution.evidence.service.JsonArtifactRecipe;

import java.util.List;
import java.util.Objects;

/** User-managed requirements. Agent tools must not expose this configuration surface. */
@Service
public class GoalJsonAcceptanceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public GoalJsonAcceptanceService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record ConfigureRequest(Long expectedRevision, String artifactSlot, List<String> requiredFields) { }
    public record Requirement(String criterionKey, String artifactSlot, long revision,
                              List<String> requiredFields, String configuredBy) { }
    public record View(boolean required, String status, List<Requirement> requirements) { }
    record GoalScope(long id, String conversationId, long workspaceId, long agentId, String status, boolean required) { }

    /** Keep the HTTP caller's immutable identity locked through the complete managed operation. */
    @Transactional
    public <T> T withAuthenticatedUser(Long userId, String username, java.util.function.Function<String, T> operation) {
        if (userId == null || username == null || username.isBlank()) throw failure(401, "Authenticated account ID required");
        List<String> names = jdbc.queryForList("SELECT username FROM mate_user WHERE id=? AND enabled=TRUE AND deleted=0 FOR UPDATE", String.class, userId);
        if (names.size() != 1 || !username.equals(names.getFirst())) throw failure(403, "Authenticated account is no longer current");
        return operation.apply(names.getFirst());
    }

    @Transactional
    public View get(Long goalId, String username) {
        GoalScope goal = authorizedGoal(goalId, username, true);
        return new View(goal.required(), goal.status(), requirements(goalId));
    }

    @Transactional
    public Requirement configure(Long goalId, String criterionKey, ConfigureRequest request, String username) {
        GoalScope goal = authorizedGoal(goalId, username, true);
        if (!List.of("active", "paused").contains(goal.status())) {
            throw failure(409, "JSON requirements can only change on an active or paused goal");
        }
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw failure(400, "expectedRevision is required (0 for a new requirement)");
        }
        validateKey(criterionKey);
        validateKey(request.artifactSlot());
        List<String> fields = JsonArtifactRecipe.validate(request.requiredFields());
        List<Requirement> current = requirements(goalId);
        Requirement previous = current.stream().filter(r -> r.criterionKey().equals(criterionKey)).findFirst().orElse(null);
        long revision = previous == null ? 0 : previous.revision();
        if (request.expectedRevision() != revision) throw failure(409, "JSON requirement revision changed; reload before editing");
        if (previous != null && previous.artifactSlot().equals(request.artifactSlot()) && previous.requiredFields().equals(fields)) {
            return previous;
        }
        if (previous == null && current.size() >= 8) throw failure(400, "At most 8 JSON requirements per goal");
        long next = Math.addExact(revision, 1);
        String encoded = encode(fields);
        if (previous == null) {
            jdbc.update("""
                    INSERT INTO mate_goal_json_requirement
                    (goal_id,criterion_key,artifact_slot,revision,required_fields,created_by,updated_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, goalId, criterionKey, request.artifactSlot(), next, encoded, username, username);
        } else {
            jdbc.update("""
                    UPDATE mate_goal_json_requirement SET artifact_slot=?,revision=?,required_fields=?,updated_by=?,updated_at=CURRENT_TIMESTAMP
                    WHERE goal_id=? AND criterion_key=?
                    """, request.artifactSlot(), next, encoded, username, goalId, criterionKey);
        }
        // This version write races safely with existing GoalService CAS completion.
        // No removal/disable endpoint: opting in never silently restores text-only completion.
        jdbc.update("UPDATE mate_agent_goal SET json_acceptance_required=TRUE,version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=?", goalId);
        return new Requirement(criterionKey, request.artifactSlot(), next, fields, username);
    }

    GoalScope authorizedGoal(Long goalId, String username, boolean lock) {
        if (username == null || username.isBlank() || "anonymous".equals(username)) throw failure(401, "Authentication required");
        // Deliberately stricter than legacy system-conversation ownership fallback.
        List<String> roles = jdbc.queryForList("SELECT role FROM mate_user WHERE username=? AND enabled=TRUE AND deleted=0" + (lock ? " FOR UPDATE" : ""), String.class, username);
        if (roles.size() != 1) throw failure(403, "An enabled user account is required");
        GoalScope initial = goal(goalId, false);
        var conversations = jdbc.query("SELECT username,workspace_id FROM mate_conversation WHERE conversation_id=? AND deleted=0" + (lock ? " FOR UPDATE" : ""),
                (row, i) -> Objects.equals(row.getString("username"), username) || "admin".equalsIgnoreCase(roles.getFirst())
                        ? row.getLong("workspace_id") : null, initial.conversationId());
        if (conversations.size() != 1 || !Objects.equals(conversations.getFirst(), initial.workspaceId())) throw failure(403, "Goal owner permission required");
        GoalScope current = lock ? goal(goalId, true) : initial;
        if (!Objects.equals(current.conversationId(), initial.conversationId()) || current.workspaceId() != initial.workspaceId()) {
            throw failure(409, "Goal scope changed; reload before editing");
        }
        return current;
    }

    private GoalScope goal(Long id, boolean lock) {
        var rows = jdbc.query("SELECT id,conversation_id,workspace_id,agent_id,status,json_acceptance_required FROM mate_agent_goal WHERE id=? AND deleted=0" + (lock ? " FOR UPDATE" : ""),
                (row, i) -> new GoalScope(row.getLong("id"), row.getString("conversation_id"), row.getLong("workspace_id"), row.getLong("agent_id"), row.getString("status"), row.getBoolean("json_acceptance_required")), id);
        if (rows.size() != 1) throw failure(404, "Goal not found");
        return rows.getFirst();
    }

    List<Requirement> requirements(Long goalId) {
        return jdbc.query("SELECT criterion_key,artifact_slot,revision,required_fields,updated_by FROM mate_goal_json_requirement WHERE goal_id=? ORDER BY criterion_key FOR UPDATE",
                (row, i) -> new Requirement(row.getString("criterion_key"), row.getString("artifact_slot"), row.getLong("revision"), decode(row.getString("required_fields")), row.getString("updated_by")), goalId);
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9_-]{0,63}")) throw failure(400, "Keys must be 1–64 lowercase letters, digits, underscores or hyphens, starting with a letter");
    }

    private String encode(List<String> fields) {
        try { return json.writeValueAsString(fields); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode JSON requirements", e); }
    }

    private List<String> decode(String fields) {
        try { return JsonArtifactRecipe.validate(json.readValue(fields, new TypeReference<List<String>>() { })); }
        catch (Exception e) { throw new IllegalStateException("Stored JSON requirements are invalid", e); }
    }

    private static MateClawException failure(int code, String message) { return new MateClawException(code, message); }
}
