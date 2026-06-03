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
     * Provider/model id for the evaluator. Empty string means "use the
     * same model as the chat agent" — convenient for dev, expensive in
     * production. Operators should point this at a cheap model.
     */
    private String evaluatorModel = "";

    /** Max messages from parent conversation included in evaluator prompt. */
    private int evaluatorContextMessages = 8;
}
