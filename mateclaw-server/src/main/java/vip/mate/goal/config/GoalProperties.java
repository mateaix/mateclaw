package vip.mate.goal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration knobs for the persistent-goal subsystem.
 *
 * <p>{@link #enabled} is the master gate: when {@code false} the StateGraph
 * wiring stays inactive (no graph node touches the table), while
 * {@code findActiveByConversation} still works for tests.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mateclaw.goal")
public class GoalProperties {

    /**
     * Compile-time ceiling for {@link #maxHardContinuationsPerRun}. The graph
     * recursion limit is sized statically to accommodate this many extra
     * fresh-budget ReAct segments per run, so the runtime value is clamped to
     * it — an operator cannot push hard continuations past what the recursion
     * backstop was sized for. Raising this requires re-sizing the recursion
     * ceiling in {@code AgentGraphBuilder.frameworkRecursionLimit()}.
     */
    public static final int MAX_HARD_CONTINUATIONS_CEILING = 3;

    /**
     * Master switch — when off, the graph never invokes GoalEvaluationNode
     * (the conditional edge sees no active goal, so the node is unreachable).
     * Operators who want to disable goal evaluation entirely can override via
     * {@code mateclaw.goal.enabled=false} in application.yml.
     */
    private boolean enabled = true;

    /**
     * Create-time default for a goal's {@code autoFollowupEnabled} when the
     * caller leaves it unspecified (null). Explicit true/false in the request
     * is never overridden by this.
     */
    private boolean defaultAutoFollowup = true;

    /**
     * Runtime hard gate for auto-followup. When false, no goal injects a
     * follow-up regardless of its per-goal {@code autoFollowupEnabled} flag —
     * the operator's kill switch for the self-continuation loop that takes
     * effect immediately, even for goals created with the flag on.
     */
    private boolean allowAutoFollowup = true;

    /**
     * Auto-derive a goal from a multi-step Plan-Execute plan. The Plan-Execute
     * planner decomposes the request into steps and the step executor is a
     * narrow "task runner" — neither calls {@code setGoal}, so without this a
     * Plan-Execute run never engages the goal subsystem. When enabled, a goal is
     * created server-side at plan generation (title = request, acceptance
     * criteria seeded from the plan steps), so the already-wired
     * GoalEvaluationNode tracks completion. Gated by {@link #enabled}; only
     * fires for genuine multi-step plans and when the conversation has no active
     * goal yet. Set to {@code false} to keep Plan-Execute goal-free.
     */
    private boolean autoGoalFromPlan = true;

    /** Default turn budget when the user doesn't override. */
    private int defaultTurnBudget = 20;

    /** Default combined (agent + eval) LLM call budget. */
    private int defaultLlmCallBudget = 200;

    /** Default cooldown between auto-followups in seconds. */
    private int autoFollowupCooldownSeconds = 0;

    /**
     * Max auto-followups injected within a single graph run (one user turn).
     * Caps the self-continuation loop so one message can't drive too many
     * autonomous steps or approach the graph recursion limit. The goal's
     * overall {@code turn_budget} still bounds total turns across messages;
     * this is the tighter per-message safety net.
     */
    private int maxFollowupsPerRun = 8;

    /**
     * Max "hard continuations" per single graph run. A hard continuation is a
     * goal follow-up that re-enters the ReAct loop with a FRESH iteration
     * budget after a turn that hit {@code MAX_ITERATIONS_REACHED} — letting a
     * task too large for one budget keep going autonomously instead of stalling
     * until the user sends another message. Each one costs up to a full
     * {@code maxIterations} worth of node visits, so this is a dedicated cap on
     * top of {@link #maxFollowupsPerRun}, clamped to
     * {@link #MAX_HARD_CONTINUATIONS_CEILING} and sized into the graph recursion
     * ceiling. The goal's cross-turn turn / LLM-call budgets still apply. Set to
     * 0 to keep the previous behaviour (max-iterations turns end the run).
     */
    private int maxHardContinuationsPerRun = 1;

    /**
     * Provider/model id for the evaluator. Empty string means "use the
     * same model as the chat agent" — convenient for dev, expensive in
     * production. Operators should point this at a cheap model.
     */
    private String evaluatorModel = "";

    /** Max messages from parent conversation included in evaluator prompt. */
    private int evaluatorContextMessages = 8;
}
