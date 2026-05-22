package vip.mate.goal.service;

import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalEventEntity;
import vip.mate.goal.model.GoalUpdateRequest;

import java.util.List;

/**
 * Persistent goal service — CRUD, status transitions, and bookkeeping
 * called from {@code GoalEvaluationNode} (PR2).
 *
 * <p>Concurrency model: writes use a per-row {@code WHERE version=?}
 * compare-and-set. On conflict the service retries up to 3 times before
 * surfacing {@code MateClawException("err.goal.optimistic_lock_conflict",
 * 409)}. Create additionally uses the DB-level
 * {@code uk_agent_goal_active_conv} unique index as the last line of
 * defence; service-layer pre-check is only a UX nicety.
 */
public interface GoalService {

    // ==================== CRUD ====================

    /**
     * Create a new goal. Fails 409 when the conversation already has an
     * active goal (DB unique index hit).
     */
    GoalEntity create(GoalCreateRequest req, String username);

    GoalEntity getById(Long id);

    /** Active goal for the conversation, or null. Used by buildInitialState. */
    GoalEntity findActiveByConversation(String conversationId);

    /** Paged list filtered by status / owner. */
    List<GoalEntity> list(String status, String username, int limit);

    /** Sparse update. Throws if any terminal-state goal is targeted. */
    GoalEntity update(Long id, GoalUpdateRequest req, String username);

    /** Events for the timeline drawer, newest first. */
    List<GoalEventEntity> listEvents(Long goalId, int limit);

    // ==================== State machine ====================

    GoalEntity pause(Long id, String username);
    GoalEntity resume(Long id, String username);
    GoalEntity abandon(Long id, String username);

    /** Flip active->completed. Writes a 'completed' event. */
    GoalEntity markCompleted(Long id, GoalEvaluationResult result);

    /** Flip active->exhausted with the reason that triggered it. */
    GoalEntity markExhausted(Long id, String reason);

    // ==================== Evaluation bookkeeping ====================

    /**
     * Atomic bookkeeping for one evaluation pass. Bumps turns_used (+1),
     * agent_llm_calls_used (+agentDelta), eval_llm_calls_used (+evalDelta),
     * persists progress_summary / completion_score / last_evaluation_at,
     * writes one 'evaluated' GoalEventEntity. Optimistic-lock retry x3.
     */
    void recordEvaluation(Long id,
                          GoalEvaluationResult result,
                          int agentLlmCallsDelta,
                          int evalLlmCallsDelta);

    /** True when turns_used >= turn_budget OR (agent + eval) >= llm_call_budget. */
    boolean isBudgetExhausted(GoalEntity goal);

    /** "turn_budget" or "llm_call_budget" — the dimension that hit the cap. */
    String exhaustionReason(GoalEntity goal);

    void recordFollowupInjected(Long id, String prompt);

    /** Append a sub-criterion without restarting the goal. */
    GoalEntity appendCriterion(Long id, String criterion, String username);
}
