package vip.mate.goal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalCriteriaCodec;
import vip.mate.goal.model.GoalCriterion;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalResponse;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalEventEntity;
import vip.mate.goal.model.GoalEventType;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.GoalUpdateRequest;
import vip.mate.goal.repository.GoalEventMapper;
import vip.mate.goal.repository.GoalMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation. Concurrency safety relies on:
 * <ol>
 *   <li>Service-level pre-check + DB-level unique index for "at most one
 *       active goal per conversation" — see V120 migration.</li>
 *   <li>Per-write optimistic lock via {@code WHERE version=?} on
 *       state-mutating updates; retried up to 3 times on contention.</li>
 *   <li>{@code @Transactional} on every write so the goal row update and
 *       the matching event-log insert succeed or fail together.</li>
 * </ol>
 */
@Slf4j
@Service
public class GoalServiceImpl implements GoalService {

    private static final int OPTIMISTIC_LOCK_MAX_RETRIES = 3;

    private final GoalMapper goalMapper;
    private final GoalEventMapper eventMapper;
    private final GoalProperties properties;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    /**
     * Optional — only set when the memory subsystem is wired. On goal
     * completion we forward a synthetic "[goal completed] ..." turn so
     * the memory dreaming pass can fold the outcome into long-term
     * memory and the user can ask about it later. Failures are best
     * effort: memory should never block the state-machine write.
     */
    private vip.mate.memory.spi.MemoryManager memoryManager;

    public GoalServiceImpl(GoalMapper goalMapper,
                           GoalEventMapper eventMapper,
                           GoalProperties properties,
                           AuditEventService auditEventService,
                           ObjectMapper objectMapper) {
        this.goalMapper = goalMapper;
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setMemoryManager(vip.mate.memory.spi.MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    // ==================== CRUD ====================

    @Override
    @Transactional
    public GoalEntity create(GoalCreateRequest req, String username) {
        validateCreate(req);

        // Service-level pre-check is a UX nicety only — the DB unique
        // index is the source of truth. Two concurrent creates can both
        // pass this check; the second insert will surface a
        // DuplicateKeyException and we map it to 409.
        GoalEntity active = findActiveByConversation(req.getConversationId());
        if (active != null) {
            throw new MateClawException("err.goal.conversation_has_active", 409,
                    "Conversation already has an active goal: " + active.getId());
        }

        GoalEntity entity = new GoalEntity();
        entity.setConversationId(req.getConversationId());
        entity.setAgentId(req.getAgentId());
        entity.setWorkspaceId(req.getWorkspaceId());
        entity.setCreatedBy(username);
        entity.setTitle(req.getTitle().trim());
        entity.setDescription(req.getDescription() != null ? req.getDescription() : "");
        entity.setExitCriteria(req.getExitCriteria());
        entity.setSuccessCheckPrompt(req.getSuccessCheckPrompt());
        entity.setStatus(GoalStatus.ACTIVE);
        entity.setTurnBudget(req.getTurnBudget() != null
                ? req.getTurnBudget() : properties.getDefaultTurnBudget());
        entity.setTurnsUsed(0);
        entity.setLlmCallBudget(req.getLlmCallBudget() != null
                ? req.getLlmCallBudget() : properties.getDefaultLlmCallBudget());
        entity.setAgentLlmCallsUsed(0);
        entity.setEvalLlmCallsUsed(0);
        // Three-state default: explicit true/false is honored; null falls
        // back to the configured create-time default.
        entity.setAutoFollowupEnabled(req.getAutoFollowupEnabled() != null
                ? req.getAutoFollowupEnabled()
                : properties.isDefaultAutoFollowup());
        entity.setFollowupCooldownSeconds(req.getFollowupCooldownSeconds() != null
                ? req.getFollowupCooldownSeconds() : properties.getAutoFollowupCooldownSeconds());
        // Normalize any caller-supplied checklist: assign C1..Cn, force
        // passed=false, clear evidence. Empty/omitted -> null column so the
        // first evaluation bootstraps the list.
        entity.setCriteria(serializeCriteria(normalizeInitialCriteria(req.getCriteria())));
        entity.setVersion(0);
        entity.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);

        try {
            goalMapper.insert(entity);
        } catch (DuplicateKeyException dup) {
            // Lost the race against another create. The unique index hit
            // is the authoritative "conversation already has active goal".
            throw new MateClawException("err.goal.conversation_has_active", 409,
                    "Conversation already has an active goal (DB unique index)");
        }

        writeEvent(entity.getId(), GoalEventType.CREATED, null, Map.of(
                "title", entity.getTitle(),
                "turnBudget", entity.getTurnBudget(),
                "llmCallBudget", entity.getLlmCallBudget(),
                "by", username));
        recordAudit("goal.created", entity, Map.of("by", username, "title", entity.getTitle()));
        return entity;
    }

    @Override
    public GoalEntity getById(Long id) {
        GoalEntity g = goalMapper.selectById(id);
        if (g == null) {
            throw new MateClawException("err.goal.not_found", 404, "Goal not found: " + id);
        }
        return g;
    }

    @Override
    public GoalEntity findActiveByConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return goalMapper.selectOne(new LambdaQueryWrapper<GoalEntity>()
                .eq(GoalEntity::getConversationId, conversationId)
                .eq(GoalEntity::getStatus, GoalStatus.ACTIVE)
                .last("LIMIT 1"));
    }

    @Override
    public List<GoalEntity> list(String status, String username, int limit) {
        LambdaQueryWrapper<GoalEntity> w = new LambdaQueryWrapper<GoalEntity>()
                .orderByDesc(GoalEntity::getCreateTime);
        if (username != null && !username.isBlank()) {
            w.eq(GoalEntity::getCreatedBy, username);
        }
        if (status != null && !status.isBlank()) {
            GoalStatus s;
            try {
                s = GoalStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new MateClawException("err.goal.bad_status", 400, "Unknown status: " + status);
            }
            w.eq(GoalEntity::getStatus, s);
        }
        w.last("LIMIT " + Math.max(1, Math.min(200, limit)));
        return goalMapper.selectList(w);
    }

    @Override
    @Transactional
    public GoalEntity update(Long id, GoalUpdateRequest req, String username) {
        // Pre-validate constant fields once; the actual not-terminal check
        // happens inside the builder against the fresh entity so a status
        // flip between this method's entry and a CAS retry is honoured.
        if (req.getTurnBudget() != null) validateBudget(req.getTurnBudget(), "turnBudget");
        if (req.getLlmCallBudget() != null) validateBudget(req.getLlmCallBudget(), "llmCallBudget");

        GoalEntity updated = retryOptimistic(id, "update", fresh -> {
            ensureNotTerminal(fresh, "update");
            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh);
            boolean changed = false;
            if (req.getTitle() != null && !req.getTitle().isBlank()) {
                w.set(GoalEntity::getTitle, req.getTitle().trim()); changed = true;
            }
            if (req.getDescription() != null) {
                w.set(GoalEntity::getDescription, req.getDescription()); changed = true;
            }
            if (req.getExitCriteria() != null) {
                w.set(GoalEntity::getExitCriteria, req.getExitCriteria()); changed = true;
            }
            if (req.getSuccessCheckPrompt() != null) {
                w.set(GoalEntity::getSuccessCheckPrompt, req.getSuccessCheckPrompt()); changed = true;
            }
            if (req.getTurnBudget() != null) {
                w.set(GoalEntity::getTurnBudget, req.getTurnBudget()); changed = true;
            }
            if (req.getLlmCallBudget() != null) {
                w.set(GoalEntity::getLlmCallBudget, req.getLlmCallBudget()); changed = true;
            }
            if (req.getAutoFollowupEnabled() != null) {
                w.set(GoalEntity::getAutoFollowupEnabled, req.getAutoFollowupEnabled()); changed = true;
            }
            if (req.getFollowupCooldownSeconds() != null) {
                w.set(GoalEntity::getFollowupCooldownSeconds, req.getFollowupCooldownSeconds());
                changed = true;
            }
            if (!changed) {
                return null; // idempotent no-op
            }
            bumpVersionAndTime(w);
            return w;
        });
        recordAudit("goal.updated", updated, Map.of("by", username));
        return updated;
    }

    @Override
    public List<GoalEventEntity> listEvents(Long goalId, int limit) {
        return eventMapper.selectList(new LambdaQueryWrapper<GoalEventEntity>()
                .eq(GoalEventEntity::getGoalId, goalId)
                .orderByDesc(GoalEventEntity::getId)
                .last("LIMIT " + Math.max(1, Math.min(500, limit))));
    }

    // ==================== State machine ====================

    @Override
    @Transactional
    public GoalEntity pause(Long id, String username) {
        return flipStatus(id, GoalStatus.ACTIVE, GoalStatus.PAUSED,
                GoalEventType.PAUSED, "goal.paused", username);
    }

    @Override
    @Transactional
    public GoalEntity resume(Long id, String username) {
        return flipStatus(id, GoalStatus.PAUSED, GoalStatus.ACTIVE,
                GoalEventType.RESUMED, "goal.resumed", username);
    }

    @Override
    @Transactional
    public GoalEntity abandon(Long id, String username) {
        // Allows abandon from both ACTIVE and PAUSED.
        GoalEntity updated = retryOptimistic(id, "abandon", fresh -> {
            ensureNotTerminal(fresh, "abandon");
            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh)
                    .set(GoalEntity::getStatus, GoalStatus.ABANDONED);
            bumpVersionAndTime(w);
            return w;
        });
        writeEvent(id, GoalEventType.ABANDONED, null, Map.of("by", username));
        recordAudit("goal.abandoned", updated, Map.of("by", username));
        return updated;
    }

    @Override
    @Transactional
    public GoalEntity markCompleted(Long id, GoalEvaluationResult result) {
        GoalEntity g = retryOptimistic(id, "markCompleted", fresh -> {
            if (fresh.getStatus().isTerminal()) return null; // idempotent
            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh)
                    .set(GoalEntity::getStatus, GoalStatus.COMPLETED);
            if (result != null) {
                w.set(GoalEntity::getCompletionScore, result.score())
                 .set(GoalEntity::getProgressSummary, result.gap());
            }
            // Snapshot the checklist as fully satisfied. Idempotent for the
            // auto path (recordEvaluation already merged all-passed); required
            // for manual completion, which has no preceding verdict.
            List<GoalCriterion> existing = GoalCriteriaCodec.parse(fresh.getCriteria(), objectMapper);
            if (!existing.isEmpty()) {
                List<GoalCriterion> allPassed = existing.stream()
                        .map(c -> c.passed() ? c : new GoalCriterion(c.id(), c.text(), true,
                                c.evidence() == null || c.evidence().isBlank()
                                        ? "marked complete" : c.evidence()))
                        .toList();
                w.set(GoalEntity::getCriteria, GoalCriteriaCodec.serialize(allPassed, objectMapper));
            }
            bumpVersionAndTime(w);
            return w;
        });
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("finalScore", result != null ? result.score() : null);
        detail.put("agentLlmCallsUsed", g.getAgentLlmCallsUsed());
        detail.put("evalLlmCallsUsed", g.getEvalLlmCallsUsed());
        detail.put("criteria", GoalCriteriaCodec.parse(g.getCriteria(), objectMapper));
        writeEvent(id, GoalEventType.COMPLETED, null, detail);
        recordAudit("goal.completed", g, detail);

        // Forward to long-term memory on completion. Best-effort: a failing
        // memory pipeline must not roll back the DB transition.
        if (memoryManager != null) {
            try {
                String summary = g.getProgressSummary() != null && !g.getProgressSummary().isBlank()
                        ? g.getProgressSummary()
                        : "Final score: " + (result != null ? result.score() : "—");
                memoryManager.syncAll(
                        g.getAgentId(),
                        g.getConversationId(),
                        "[goal completed] " + g.getTitle(),
                        summary);
            } catch (Exception e) {
                log.debug("[GoalService] memory syncAll on goal completion failed: {}", e.getMessage());
            }
        }

        return g;
    }

    @Override
    @Transactional
    public GoalEntity markExhausted(Long id, String reason) {
        GoalEntity g = retryOptimistic(id, "markExhausted", fresh -> {
            if (fresh.getStatus().isTerminal()) return null;
            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh)
                    .set(GoalEntity::getStatus, GoalStatus.EXHAUSTED);
            bumpVersionAndTime(w);
            return w;
        });
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason != null ? reason : "unknown");
        detail.put("turnsUsed", g.getTurnsUsed());
        detail.put("agentLlmCallsUsed", g.getAgentLlmCallsUsed());
        detail.put("evalLlmCallsUsed", g.getEvalLlmCallsUsed());
        writeEvent(id, GoalEventType.EXHAUSTED, null, detail);
        recordAudit("goal.exhausted", g, detail);
        return g;
    }

    // ==================== Evaluation bookkeeping ====================

    @Override
    @Transactional
    public void recordEvaluation(Long id, GoalEvaluationResult result,
                                 int agentLlmCallsDelta, int evalLlmCallsDelta) {
        // Cheap pre-check so terminal goals also skip the event write — the
        // in-loop guard below still protects against a status flip during a
        // contended retry, but it would also issue the event log entry that
        // a no-op skip should not produce.
        GoalEntity initial = goalMapper.selectById(id);
        if (initial == null || initial.getStatus().isTerminal()) return;

        int agentDelta = Math.max(0, agentLlmCallsDelta);
        int evalDelta = Math.max(0, evalLlmCallsDelta);

        GoalEntity g = retryOptimistic(id, "recordEvaluation", fresh -> {
            if (fresh.getStatus().isTerminal()) return null; // ignore late evaluations
            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh)
                    .setSql("turns_used = turns_used + 1")
                    .setSql("agent_llm_calls_used = agent_llm_calls_used + " + agentDelta)
                    .setSql("eval_llm_calls_used = eval_llm_calls_used + " + evalDelta)
                    .set(GoalEntity::getLastEvaluationAt, LocalDateTime.now());
            if (result != null) {
                w.set(GoalEntity::getCompletionScore, result.score())
                 .set(GoalEntity::getProgressSummary, result.gap());
                // Persist the checklist by carrier: bootstrap writes the fresh
                // draft; verdict merges the per-criterion delta into the
                // current list (re-read on the locked `fresh` to avoid races).
                String criteriaJson = nextCriteriaJson(fresh, result);
                if (criteriaJson != null) {
                    w.set(GoalEntity::getCriteria, criteriaJson);
                }
            }
            bumpVersionAndTime(w);
            return w;
        });

        Map<String, Object> detail = new LinkedHashMap<>();
        if (result != null) {
            detail.put("completionScore", result.score());
            detail.put("gap", result.gap());
            detail.put("decision", result.decision());
            detail.put("evaluatorModel", result.evaluatorModel());
            detail.put("latencyMs", result.latencyMs());
        }
        detail.put("agentLlmCallsDelta", agentDelta);
        detail.put("evalLlmCallsDelta", evalDelta);
        // Full checklist (array) so the timeline / SSE consumer never sees the
        // raw String column or has to reconstruct from the per-round delta.
        detail.put("criteria", GoalCriteriaCodec.parse(g.getCriteria(), objectMapper));
        writeEvent(id, GoalEventType.EVALUATED, null, detail);
    }

    /**
     * Compute the next criteria JSON for a record-evaluation write, or
     * {@code null} when the result carries no checklist change. Bootstrap
     * results replace the list with the freshly derived draft; verdict
     * results merge their per-criterion delta into the locked-row list.
     */
    private String nextCriteriaJson(GoalEntity fresh, GoalEvaluationResult result) {
        if (result.bootstrapCriteria() != null && !result.bootstrapCriteria().isEmpty()) {
            return GoalCriteriaCodec.serialize(result.bootstrapCriteria(), objectMapper);
        }
        if (result.criterionVerdicts() != null && !result.criterionVerdicts().isEmpty()) {
            List<GoalCriterion> existing = GoalCriteriaCodec.parse(fresh.getCriteria(), objectMapper);
            if (existing.isEmpty()) {
                return null;
            }
            return GoalCriteriaCodec.serialize(
                    GoalCriteriaCodec.merge(existing, result.criterionVerdicts()), objectMapper);
        }
        return null;
    }

    @Override
    public boolean isBudgetExhausted(GoalEntity goal) {
        int turns = goal.getTurnsUsed() != null ? goal.getTurnsUsed() : 0;
        int turnBudget = goal.getTurnBudget() != null ? goal.getTurnBudget() : Integer.MAX_VALUE;
        if (turns >= turnBudget) return true;
        int callBudget = goal.getLlmCallBudget() != null ? goal.getLlmCallBudget() : Integer.MAX_VALUE;
        return goal.totalLlmCallsUsed() >= callBudget;
    }

    @Override
    public String exhaustionReason(GoalEntity goal) {
        int turns = goal.getTurnsUsed() != null ? goal.getTurnsUsed() : 0;
        int turnBudget = goal.getTurnBudget() != null ? goal.getTurnBudget() : Integer.MAX_VALUE;
        if (turns >= turnBudget) return "turn_budget";
        return "llm_call_budget";
    }

    @Override
    @Transactional
    public void recordFollowupInjected(Long id, String prompt) {
        // Mirror recordEvaluation's pre-check so a late followup on a
        // terminal goal does not produce an event-log entry.
        GoalEntity initial = goalMapper.selectById(id);
        if (initial == null || initial.getStatus().isTerminal()) return;

        GoalEntity g = retryOptimistic(id, "recordFollowupInjected", fresh -> {
            if (fresh.getStatus().isTerminal()) return null;
            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh)
                    .set(GoalEntity::getLastFollowupAt, LocalDateTime.now());
            bumpVersionAndTime(w);
            return w;
        });
        writeEvent(id, GoalEventType.FOLLOWUP_INJECTED, null, Map.of(
                "prompt", prompt != null ? prompt : "",
                "turnsUsed", g.getTurnsUsed()));
    }

    @Override
    @Transactional
    public GoalEntity appendCriterion(Long id, String criterion, String username) {
        if (criterion == null || criterion.isBlank()) {
            throw new MateClawException("err.goal.criterion_empty", 400, "Criterion must not be empty");
        }
        String trimmed = criterion.trim();
        // Double-write: append a structured criterion (authoritative) and
        // mirror the text into exit_criteria for backward compatibility /
        // human readability. New id is the current max ordinal + 1. Merge
        // against the freshly refetched row so concurrent appends don't clobber.
        GoalEntity g = retryOptimistic(id, "appendCriterion", fresh -> {
            ensureNotTerminal(fresh, "appendCriterion");

            List<GoalCriterion> list = GoalCriteriaCodec.parse(fresh.getCriteria(), objectMapper);
            list.add(new GoalCriterion("C" + (list.size() + 1), trimmed, false, ""));
            String criteriaJson = GoalCriteriaCodec.serialize(GoalCriteriaCodec.reindex(list), objectMapper);

            String existing = fresh.getExitCriteria() != null ? fresh.getExitCriteria() : "";
            String mergedText = existing.isEmpty() ? trimmed : existing + "\n+ " + trimmed;

            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh)
                    .set(GoalEntity::getCriteria, criteriaJson)
                    .set(GoalEntity::getExitCriteria, mergedText);
            bumpVersionAndTime(w);
            return w;
        });
        List<GoalCriterion> full = GoalCriteriaCodec.parse(g.getCriteria(), objectMapper);
        String criterionId = full.isEmpty() ? "" : full.get(full.size() - 1).id();
        writeEvent(id, GoalEventType.CRITERION_ADDED, null, Map.of(
                "criterion", trimmed,
                "criterionId", criterionId,
                "criteria", full,
                "by", username));
        return g;
    }

    // ==================== Internals ====================

    /**
     * Normalize a caller-supplied initial checklist: keep only non-blank
     * {@code text}, assign stable ids {@code C1..Cn} (ignore any caller ids),
     * force {@code passed=false} and clear evidence. Returns {@code null} for
     * an empty/null result so the column stays NULL and the first evaluation
     * bootstraps the list.
     */
    private List<GoalCriterion> normalizeInitialCriteria(List<GoalCriterion> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<GoalCriterion> kept = new java.util.ArrayList<>(raw.size());
        for (GoalCriterion c : raw) {
            if (c != null && c.text() != null && !c.text().isBlank()) {
                kept.add(new GoalCriterion("", c.text().trim(), false, ""));
            }
        }
        return kept.isEmpty() ? null : GoalCriteriaCodec.reindex(kept);
    }

    /** Serialize a checklist to JSON text, or {@code null} for a null list. */
    private String serializeCriteria(List<GoalCriterion> criteria) {
        return GoalCriteriaCodec.serialize(criteria, objectMapper);
    }

    @Override
    public GoalResponse toResponse(GoalEntity e) {
        if (e == null) {
            return null;
        }
        GoalResponse r = new GoalResponse();
        r.setId(e.getId());
        r.setConversationId(e.getConversationId());
        r.setAgentId(e.getAgentId());
        r.setWorkspaceId(e.getWorkspaceId());
        r.setCreatedBy(e.getCreatedBy());
        r.setTitle(e.getTitle());
        r.setDescription(e.getDescription());
        r.setExitCriteria(e.getExitCriteria());
        r.setSuccessCheckPrompt(e.getSuccessCheckPrompt());
        r.setStatus(e.getStatus());
        r.setTurnBudget(e.getTurnBudget());
        r.setTurnsUsed(e.getTurnsUsed());
        r.setLlmCallBudget(e.getLlmCallBudget());
        r.setAgentLlmCallsUsed(e.getAgentLlmCallsUsed());
        r.setEvalLlmCallsUsed(e.getEvalLlmCallsUsed());
        r.setTotalLlmCallsUsed(e.totalLlmCallsUsed());
        r.setProgressSummary(e.getProgressSummary());
        r.setCompletionScore(e.getCompletionScore());
        r.setLastEvaluationAt(e.getLastEvaluationAt());
        r.setAutoFollowupEnabled(e.getAutoFollowupEnabled());
        r.setFollowupCooldownSeconds(e.getFollowupCooldownSeconds());
        r.setLastFollowupAt(e.getLastFollowupAt());
        r.setVersion(e.getVersion());
        r.setCreateTime(e.getCreateTime());
        r.setUpdateTime(e.getUpdateTime());
        // Always an array; empty when the column is null/unparseable.
        r.setCriteria(GoalCriteriaCodec.parse(e.getCriteria(), objectMapper));
        return r;
    }

    @Override
    public List<GoalResponse> toResponseList(List<GoalEntity> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream().map(this::toResponse).toList();
    }

    private void validateCreate(GoalCreateRequest req) {
        if (req == null) {
            throw new MateClawException("err.goal.bad_request", 400, "Request body required");
        }
        if (req.getConversationId() == null || req.getConversationId().isBlank()) {
            throw new MateClawException("err.goal.bad_request", 400, "conversationId required");
        }
        if (req.getAgentId() == null) {
            throw new MateClawException("err.goal.bad_request", 400, "agentId required");
        }
        if (req.getWorkspaceId() == null) {
            throw new MateClawException("err.goal.bad_request", 400, "workspaceId required");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new MateClawException("err.goal.bad_request", 400, "title required");
        }
        if (req.getTitle().length() > 255) {
            throw new MateClawException("err.goal.bad_request", 400, "title too long (>255)");
        }
        if (req.getTurnBudget() != null) validateBudget(req.getTurnBudget(), "turnBudget");
        if (req.getLlmCallBudget() != null) validateBudget(req.getLlmCallBudget(), "llmCallBudget");
    }

    private static void validateBudget(int v, String name) {
        if (v <= 0) {
            throw new MateClawException("err.goal.invalid_budget", 400,
                    name + " must be > 0, got " + v);
        }
    }

    private void ensureNotTerminal(GoalEntity g, String op) {
        if (g.getStatus().isTerminal()) {
            throw new MateClawException("err.goal.terminal_state", 409,
                    "Cannot " + op + " a goal in terminal state " + g.getStatus().getValue());
        }
    }

    /** Build an update wrapper that enforces version match + soft-delete guard. */
    private LambdaUpdateWrapper<GoalEntity> baseLockedUpdate(GoalEntity g) {
        return new LambdaUpdateWrapper<GoalEntity>()
                .eq(GoalEntity::getId, g.getId())
                .eq(GoalEntity::getVersion, g.getVersion())
                .eq(GoalEntity::getDeleted, 0);
    }

    private static void bumpVersionAndTime(LambdaUpdateWrapper<GoalEntity> w) {
        w.setSql("version = version + 1")
         .set(GoalEntity::getUpdateTime, LocalDateTime.now());
    }

    /**
     * Builder that takes the just-refetched goal entity and either returns a
     * fully prepared {@link LambdaUpdateWrapper} (with version pinned to the
     * fresh row) or returns {@code null} to signal "no-op, treat as success"
     * for idempotent skips (e.g. terminal-state guards).
     */
    @FunctionalInterface
    private interface UpdateBuilder {
        LambdaUpdateWrapper<GoalEntity> build(GoalEntity fresh);
    }

    /**
     * Refetch + rebuild + CAS loop. The builder is invoked on each attempt
     * against a freshly loaded entity so the {@code WHERE version=?} clause
     * always matches the current row version. Returns the post-update entity
     * (or the unchanged fresh entity when the builder signals a no-op).
     *
     * <p>This replaces the earlier single-shot wrapper capture which could
     * not recover from the very first CAS miss — once {@code oldVersion}
     * went stale, all subsequent retries with the same wrapper were
     * doomed. Refetching per attempt is the correct shape for optimistic
     * locking: read, build delta against the read, CAS on the read's
     * version.
     */
    private GoalEntity retryOptimistic(Long id, String op, UpdateBuilder builder) {
        for (int i = 0; i < OPTIMISTIC_LOCK_MAX_RETRIES; i++) {
            GoalEntity fresh = goalMapper.selectById(id);
            if (fresh == null) {
                throw new MateClawException("err.goal.not_found", 404,
                        "Goal not found: " + id);
            }
            LambdaUpdateWrapper<GoalEntity> w = builder.build(fresh);
            if (w == null) {
                // Builder declined to issue a write (idempotent skip). Treat
                // as success — callers like markCompleted hit this when the
                // goal already moved to a terminal state via another path.
                return fresh;
            }
            int rows = goalMapper.update(null, w);
            if (rows > 0) {
                return goalMapper.selectById(id);
            }
            log.debug("[GoalService] Optimistic lock miss on {} (attempt {}/{})",
                    op, i + 1, OPTIMISTIC_LOCK_MAX_RETRIES);
        }
        throw new MateClawException("err.goal.optimistic_lock_conflict", 409,
                "Concurrent modification on " + op + " after "
                        + OPTIMISTIC_LOCK_MAX_RETRIES + " retries");
    }


    private GoalEntity flipStatus(Long id, GoalStatus from, GoalStatus to,
                                  String eventType, String auditAction, String username) {
        GoalEntity g = retryOptimistic(id, to.getValue(), fresh -> {
            if (fresh.getStatus() != from) {
                throw new MateClawException("err.goal.bad_transition", 409,
                        "Cannot transition " + fresh.getStatus().getValue() + " -> " + to.getValue());
            }
            LambdaUpdateWrapper<GoalEntity> w = baseLockedUpdate(fresh)
                    .set(GoalEntity::getStatus, to);
            bumpVersionAndTime(w);
            return w;
        });
        writeEvent(id, eventType, null, Map.of("by", username,
                "from", from.getValue(), "to", to.getValue()));
        recordAudit(auditAction, g, Map.of("by", username));
        return g;
    }

    private void writeEvent(Long goalId, String type, Long messageId, Map<String, Object> detail) {
        GoalEventEntity ev = new GoalEventEntity();
        ev.setGoalId(goalId);
        ev.setEventType(type);
        ev.setMessageId(messageId);
        ev.setDetailJson(safeJson(detail));
        ev.setCreateTime(LocalDateTime.now());
        eventMapper.insert(ev);
    }

    private void recordAudit(String action, GoalEntity g, Map<String, Object> detail) {
        try {
            auditEventService.record(action, "goal",
                    String.valueOf(g.getId()), g.getTitle(),
                    safeJson(detail), g.getWorkspaceId());
        } catch (Exception ex) {
            log.warn("[GoalService] audit record failed: {}", ex.getMessage());
        }
    }

    private String safeJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Returns the time elapsed since the last followup, or null if never. */
    public Duration timeSinceLastFollowup(GoalEntity g) {
        if (g.getLastFollowupAt() == null) return null;
        return Duration.between(g.getLastFollowupAt(), LocalDateTime.now());
    }
}
