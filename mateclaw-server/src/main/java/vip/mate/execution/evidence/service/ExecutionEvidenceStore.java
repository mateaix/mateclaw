package vip.mate.execution.evidence.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.common.text.SecretRedactor;
import vip.mate.execution.evidence.ExecutionEvidenceProperties;
import vip.mate.execution.evidence.model.AttemptState;
import vip.mate.execution.evidence.model.BeginResult;
import vip.mate.execution.evidence.model.EffectOutcome;
import vip.mate.execution.evidence.model.EvidenceKind;
import vip.mate.execution.evidence.model.EvidenceObservation;
import vip.mate.execution.evidence.model.EvidenceResult;
import vip.mate.execution.evidence.model.ExecutionAttempt;
import vip.mate.execution.evidence.model.ExecutionEvidence;
import vip.mate.execution.evidence.model.ExecutionIdentity;
import vip.mate.execution.evidence.model.SourceLevel;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/** Short, independent transactions never span tool execution. No public write API exists. */
@Service
public class ExecutionEvidenceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutionEvidenceProperties properties;
    private static final String EVIDENCE_QUERY = "SELECT e.*, a.conversation_id FROM mate_execution_evidence e JOIN mate_execution_attempt a ON a.id=e.attempt_id WHERE e.deleted=0 AND a.deleted=0";

    public ExecutionEvidenceStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ExecutionEvidenceProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private ExecutionIdentityResolver ownershipValidator;

    @Autowired
    public void setOwnershipValidator(ExecutionIdentityResolver validator) {
        this.ownershipValidator = validator;
    }

    public ExecutionAttempt begin(ExecutionIdentity identity) {
        return reserve(identity).attempt();
    }

    public BeginResult reserve(ExecutionIdentity identity) {
        validate(identity);
        try {
            return transaction.execute(status -> {
                if (ownershipValidator != null) ownershipValidator.lockCurrentForUpdate(identity, false);
                var existing = byInvocation(identity);
                if (existing.isPresent()) return new BeginResult(sameIdentity(existing.get(), identity), false);
                long id = IdWorker.getId();
                Instant now = now();
                jdbc.update("""
                        INSERT INTO mate_execution_attempt
                        (id,workspace_id,conversation_id,runtime_kind,runtime_session_id,invocation_key,
                        logical_call_id,attempt_no,provider_tool_call_id,tool_name,goal_id,goal_attempt_id,
                        team_run_id,team_task_id,cron_run_id,approval_id,owner_fence,state,effect_outcome,started_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'STARTED','UNCERTAIN',?)
                        """, id, identity.workspaceId(), identity.conversationId(), identity.runtimeKind(),
                        identity.runtimeSessionId(), identity.invocationKey(), identity.logicalCallId(),
                        identity.attemptNo(), identity.providerToolCallId(), identity.toolName(), identity.goalId(),
                        identity.goalAttemptId(), identity.teamRunId(), identity.teamTaskId(), identity.cronRunId(),
                        identity.approvalId(), identity.ownerFence(), timestamp(now));
                return new BeginResult(new ExecutionAttempt(id, identity, AttemptState.STARTED, EffectOutcome.UNCERTAIN, now, null), true);
            });
        } catch (DuplicateKeyException conflict) {
            return new BeginResult(sameIdentity(byInvocation(identity).orElseThrow(() ->
                    new IllegalStateException("Logical execution attempt already exists")), identity), false);
        }
    }

    public List<ExecutionEvidence> finish(Long attemptId, String ownerFence, AttemptState state,
            EffectOutcome effect, List<EvidenceObservation> observations) {
        if (state == null || state == AttemptState.STARTED || effect == null || observations == null)
            throw new IllegalArgumentException("A terminal execution outcome is required");
        if (observations.size() > 100) throw new IllegalArgumentException("Too many observations");
        return transaction.execute(status -> {
            var snapshot = findAttempt(attemptId).orElseThrow(() -> new IllegalStateException("Execution attempt unavailable"));
            boolean currentOwner = ownershipValidator == null || ownershipValidator.lockCurrentForUpdate(snapshot.identity(), true);
            var rows = jdbc.query("SELECT * FROM mate_execution_attempt WHERE id=? AND deleted=0 FOR UPDATE",
                    this::attempt, attemptId);
            if (rows.isEmpty()) throw new IllegalStateException("Execution attempt unavailable");
            var attempt = rows.getFirst();
            if (!Objects.equals(attempt.identity().ownerFence(), ownerFence))
                throw new IllegalStateException("Execution owner fence rejected");
            var existing = jdbc.query(EVIDENCE_QUERY + " AND e.attempt_id=? ORDER BY e.id", this::evidence, attemptId);
            var bySource = new LinkedHashMap<String, ExecutionEvidence>();
            existing.forEach(row -> bySource.put(row.observation().sourceKey(), row));
            var normalized = new LinkedHashMap<String, EvidenceObservation>();
            for (var observation : observations) {
                var prior = bySource.get(observation.sourceKey());
                var baseline = prior == null ? normalized.get(observation.sourceKey()) : prior.observation();
                var clean = normalize(observation, baseline);
                var duplicate = normalized.putIfAbsent(clean.sourceKey(), clean);
                if (duplicate != null && !duplicate.equals(clean)) throw conflict();
                if (prior != null && !prior.observation().equals(clean)) throw conflict();
            }
            if (attempt.state() != AttemptState.STARTED) {
                if (attempt.state() != state || attempt.effectOutcome() != effect
                        || bySource.size() != normalized.size() || !bySource.keySet().equals(normalized.keySet()))
                    throw conflict();
                return existing;
            }
            if (!currentOwner) throw new IllegalStateException("Execution owner fence rejected");
            int updated = jdbc.update("""
                    UPDATE mate_execution_attempt SET state=?,effect_outcome=?,finished_at=?,update_time=?
                    WHERE id=? AND owner_fence=? AND state='STARTED' AND deleted=0
                    """, state.name(), effect.name(), timestamp(now()), timestamp(now()), attemptId, ownerFence);
            if (updated != 1) throw new IllegalStateException("Execution owner fence rejected");
            for (var observation : normalized.values()) {
                long id = IdWorker.getId();
                jdbc.update("""
                        INSERT INTO mate_execution_evidence
                        (id,workspace_id,attempt_id,source_key,kind,result,source_level,scope_id,generation,
                        input_fingerprint,recipe_id,recipe_revision,check_scope,artifact_ref,artifact_digest,
                        summary,payload_ref,observed_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, id, attempt.identity().workspaceId(), attemptId, observation.sourceKey(),
                        observation.kind().name(), observation.result().name(), observation.sourceLevel().name(),
                        observation.scopeId(), observation.generation(), observation.inputFingerprint(),
                        observation.recipeId(), observation.recipeRevision(), observation.checkScope(),
                        observation.artifactRef(), observation.artifactDigest(), observation.summary(),
                        observation.payloadRef(), timestamp(observation.observedAt()), timestamp(observation.expiresAt()));
            }
            return jdbc.query(EVIDENCE_QUERY + " AND e.attempt_id=? ORDER BY e.id", this::evidence, attemptId);
        });
    }

    /** Delete bounded, unreferenced terminal metadata; unresolved executions and bindings remain pinned. */
    public int purgeExpiredMetadata(Instant now, int batchLimit) {
        Objects.requireNonNull(now, "Retention time required");
        if (batchLimit < 1) throw new IllegalArgumentException("Positive cleanup batch required");
        var cutoff = timestamp(now.minus(properties.getRetentionDays(), ChronoUnit.DAYS));
        return transaction.execute(status -> {
            var ids = jdbc.queryForList("""
                    SELECT a.id FROM mate_execution_attempt a
                    WHERE a.state IN ('SUCCEEDED','FAILED','CANCELLED','BLOCKED') AND a.update_time<?
                    AND NOT EXISTS (SELECT 1 FROM mate_execution_evidence e
                        WHERE e.attempt_id=a.id AND e.observed_at>=?)
                    AND NOT EXISTS (SELECT 1 FROM mate_execution_evidence e
                        JOIN mate_goal_criterion_evidence b ON b.evidence_id=e.id WHERE e.attempt_id=a.id)
                    ORDER BY a.id LIMIT ? FOR UPDATE
                    """, Long.class, cutoff, cutoff, Math.min(batchLimit,100));
            for (var id : ids) {
                // Foreign keys also prevent deletion if a new binding races the candidate selection.
                jdbc.update("DELETE FROM mate_execution_evidence WHERE attempt_id=?", id);
                jdbc.update("DELETE FROM mate_execution_attempt WHERE id=?", id);
            }
            return ids.size();
        });
    }

    /** Erase copied content while retaining non-content tombstones for existing evidence bindings. */
    public int purgeConversation(String conversationId) {
        required(conversationId,128);
        return transaction.execute(status -> {
            // Lock attempts before receipts, matching finish, so a late writer cannot restore deleted content.
            jdbc.update("""
                    UPDATE mate_execution_attempt SET
                    effect_outcome=CASE WHEN state='STARTED' THEN 'UNCERTAIN' ELSE effect_outcome END,
                    finished_at=CASE WHEN state='STARTED' THEN ? ELSE finished_at END,
                    state=CASE WHEN state='STARTED' THEN 'UNKNOWN' ELSE state END,
                    failure_reason=NULL,deleted=1,update_time=?
                    WHERE conversation_id=? AND deleted=0
                    """, timestamp(now()), timestamp(now()), conversationId);
            return jdbc.update("""
                    UPDATE mate_execution_evidence SET summary=NULL,input_fingerprint=NULL,check_scope=NULL,
                    artifact_ref=NULL,artifact_digest=NULL,payload_ref=NULL,recipe_id=NULL,
                    deleted=1,update_time=? WHERE deleted=0 AND attempt_id IN
                    (SELECT id FROM mate_execution_attempt WHERE conversation_id=?)
                    """, timestamp(now()), conversationId);
        });
    }

    public long countUnresolved() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM mate_execution_attempt WHERE deleted=0 AND state IN ('STARTED','UNKNOWN')", Long.class);
        return count == null ? 0 : count;
    }

    public Optional<ExecutionAttempt> findAttempt(Long id) {
        return jdbc.query("SELECT * FROM mate_execution_attempt WHERE id=? AND deleted=0", this::attempt, id).stream().findFirst();
    }

    /** Load one page of attempts without allowing cross-conversation reads. */
    public Map<Long, ExecutionAttempt> findAttempts(Long workspaceId, String conversationId, List<Long> ids) {
        scope(workspaceId, conversationId);
        Objects.requireNonNull(ids, "Attempt IDs required");
        if (ids.size() > properties.getMaxListLimit())
            throw new IllegalArgumentException("Attempt batch exceeds page limit");
        if (ids.isEmpty()) return Map.of();
        if (ids.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Attempt ID required");
        var distinct = ids.stream().distinct().toList();
        var args = new ArrayList<Object>(List.of(workspaceId, conversationId));
        args.addAll(distinct);
        String placeholders = String.join(",", Collections.nCopies(distinct.size(), "?"));
        var result = new LinkedHashMap<Long, ExecutionAttempt>();
        jdbc.query("SELECT * FROM mate_execution_attempt WHERE workspace_id=? AND conversation_id=?"
                        + " AND deleted=0 AND id IN (" + placeholders + ")", this::attempt, args.toArray())
                .forEach(attempt -> result.put(attempt.id(), attempt));
        return result;
    }

    /** Internal lookup for source authorization; callers must authorize before exposing the result. */
    public Optional<ExecutionEvidence> findById(Long id) {
        return jdbc.query(EVIDENCE_QUERY + " AND e.id=?", this::evidence, id).stream().findFirst();
    }

    public Optional<ExecutionEvidence> find(Long workspaceId, String conversationId, Long id) {
        scope(workspaceId, conversationId);
        return jdbc.query(EVIDENCE_QUERY + " AND e.workspace_id=? AND a.conversation_id=? AND e.id=?",
                this::evidence, workspaceId, conversationId, id).stream().findFirst();
    }

    public List<ExecutionEvidence> list(Long workspaceId, String conversationId, Instant beforeObservedAt,
            Long beforeId, int limit) {
        return list(workspaceId, conversationId, beforeObservedAt, beforeId, limit, null, null);
    }

    public List<ExecutionEvidence> list(Long workspaceId, String conversationId, Instant beforeObservedAt,
            Long beforeId, int limit, Long goalId, Long teamTaskId) {
        scope(workspaceId, conversationId);
        if ((beforeObservedAt == null) != (beforeId == null))
            throw new IllegalArgumentException("Both cursor components are required");
        int bounded = Math.min(properties.getMaxListLimit() + 1,
                limit <= 0 ? properties.getDefaultListLimit() : limit);
        var args = new ArrayList<Object>(List.of(workspaceId, conversationId));
        String query = EVIDENCE_QUERY + " AND e.workspace_id=? AND a.conversation_id=?";
        if (goalId != null) { query += " AND a.goal_id=?"; args.add(goalId); }
        if (teamTaskId != null) { query += " AND a.team_task_id=?"; args.add(teamTaskId); }
        if (beforeObservedAt != null) {
            query += " AND (e.observed_at<? OR (e.observed_at=? AND e.id<?))";
            args.add(timestamp(beforeObservedAt)); args.add(timestamp(beforeObservedAt)); args.add(beforeId);
        }
        args.add(bounded);
        return jdbc.query(query + " ORDER BY e.observed_at DESC,e.id DESC LIMIT ?", this::evidence, args.toArray());
    }

    private Optional<ExecutionAttempt> byInvocation(ExecutionIdentity identity) {
        return jdbc.query("SELECT * FROM mate_execution_attempt WHERE workspace_id=? AND invocation_key=? AND deleted=0",
                this::attempt, identity.workspaceId(), identity.invocationKey()).stream().findFirst();
    }

    private ExecutionAttempt sameIdentity(ExecutionAttempt attempt, ExecutionIdentity identity) {
        if (!attempt.identity().equals(identity)) throw conflict();
        return attempt;
    }

    private EvidenceObservation normalize(EvidenceObservation value, EvidenceObservation prior) {
        Objects.requireNonNull(value.kind(), "Evidence kind required");
        Objects.requireNonNull(value.result(), "Evidence result required");
        Objects.requireNonNull(value.sourceLevel(), "Evidence source required");
        required(value.sourceKey(), 191);
        Instant observed = value.observedAt() == null ? (prior == null ? now() : prior.observedAt())
                : value.observedAt().truncatedTo(ChronoUnit.MICROS);
        return new EvidenceObservation(value.sourceKey(), value.kind(), value.result(), value.sourceLevel(),
                value.scopeId(), value.generation(), bounded(value.inputFingerprint(),128), bounded(value.recipeId(),191),
                value.recipeRevision(), bounded(value.checkScope(),2048), bounded(value.artifactRef(),512),
                bounded(value.artifactDigest(),128), bounded(value.summary(),properties.getMaxSummaryBytes()),
                bounded(value.payloadRef(),512), observed,
                value.expiresAt() == null ? null : value.expiresAt().truncatedTo(ChronoUnit.MICROS));
    }

    private String bounded(String value, int bytes) {
        String clean = SecretRedactor.redact(value);
        if (clean == null) return null;
        int end = 0, used = 0;
        while (end < clean.length()) {
            int cp = clean.codePointAt(end);
            int length = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + length > bytes) break;
            used += length; end += Character.charCount(cp);
        }
        return clean.substring(0,end);
    }

    private void validate(ExecutionIdentity identity) {
        Objects.requireNonNull(identity, "Execution identity required");
        scope(identity.workspaceId(), identity.conversationId());
        required(identity.runtimeKind(),40); required(identity.invocationKey(),191);
        required(identity.logicalCallId(),191); required(identity.toolName(),191); required(identity.ownerFence(),191);
        if (identity.attemptNo() < 1) throw new IllegalArgumentException("Attempt number must be positive");
    }

    private void scope(Long workspaceId, String conversationId) {
        if (workspaceId == null) throw new IllegalArgumentException("Workspace required");
        required(conversationId,128);
    }

    private void required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("Missing or oversized execution identity");
    }

    private IllegalStateException conflict() { return new IllegalStateException("Immutable execution evidence conflict"); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private ExecutionAttempt attempt(ResultSet row, int number) throws SQLException {
        var identity = new ExecutionIdentity(row.getObject("workspace_id",Long.class),row.getString("conversation_id"),
                row.getString("runtime_kind"),row.getString("runtime_session_id"),row.getString("invocation_key"),
                row.getString("logical_call_id"),row.getInt("attempt_no"),row.getString("provider_tool_call_id"),
                row.getString("tool_name"),row.getObject("goal_id",Long.class),row.getString("goal_attempt_id"),
                row.getObject("team_run_id",Long.class),row.getObject("team_task_id",Long.class),
                row.getObject("cron_run_id",Long.class),row.getString("approval_id"),row.getString("owner_fence"));
        return new ExecutionAttempt(row.getLong("id"), identity,AttemptState.valueOf(row.getString("state")),
                EffectOutcome.valueOf(row.getString("effect_outcome")),instant(row,"started_at"),instant(row,"finished_at"));
    }
    private ExecutionEvidence evidence(ResultSet row, int number) throws SQLException {
        var observation = new EvidenceObservation(row.getString("source_key"),EvidenceKind.valueOf(row.getString("kind")),
                EvidenceResult.valueOf(row.getString("result")),SourceLevel.valueOf(row.getString("source_level")),
                row.getObject("scope_id",Long.class),row.getObject("generation",Long.class),row.getString("input_fingerprint"),
                row.getString("recipe_id"),row.getObject("recipe_revision",Long.class),row.getString("check_scope"),
                row.getString("artifact_ref"),row.getString("artifact_digest"),row.getString("summary"),row.getString("payload_ref"),
                instant(row,"observed_at"),instant(row,"expires_at"));
        return new ExecutionEvidence(row.getLong("id"),row.getLong("workspace_id"),row.getLong("attempt_id"),
                row.getString("conversation_id"),observation);
    }
}
