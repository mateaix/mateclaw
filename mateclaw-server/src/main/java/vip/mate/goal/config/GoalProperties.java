package vip.mate.goal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration knobs for the persistent-goal subsystem.
 *
 * <p>{@link #enabled} is the master gate: when {@code false} (PR1-4 default)
 * the StateGraph wiring stays inactive and {@code findActiveByConversation}
 * still works for tests, but no graph node touches the table. PR5 flips it
 * to {@code true}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mateclaw.goal")
public class GoalProperties {

    /**
     * Master switch — when off, the graph never invokes GoalEvaluationNode
     * (the conditional edge sees no active goal, so the node is unreachable).
     * Defaults to true now that the full PR1-5 chain is in place; operators
     * who want to disable goal evaluation can override via
     * {@code mateclaw.goal.enabled=false} in application.yml.
     */
    private boolean enabled = true;

    /** Default turn budget when the user doesn't override. */
    private int defaultTurnBudget = 20;

    /** Default combined (agent + eval) LLM call budget. */
    private int defaultLlmCallBudget = 200;

    /** Default cooldown between auto-followups in seconds. */
    private int autoFollowupCooldownSeconds = 0;

    /**
     * Provider/model id for the evaluator. Empty string means "use the
     * same model as the chat agent" — convenient for dev, expensive in
     * production. Operators should point this at a cheap model.
     */
    private String evaluatorModel = "";

    /** Max messages from parent conversation included in evaluator prompt. */
    private int evaluatorContextMessages = 8;
}
