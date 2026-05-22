package vip.mate.goal.model;

import lombok.Data;

/**
 * Request body for {@code POST /api/v1/goals}.
 *
 * <p>Only {@code conversationId}, {@code agentId}, {@code workspaceId} and
 * {@code title} are mandatory. Budgets default to the values in
 * {@link vip.mate.goal.config.GoalProperties}.
 *
 * <p>ID fields stay as {@code Long} on the wire (Jackson accepts both
 * numeric and string forms via the project's default coercion), but the
 * frontend must send them as strings to preserve snowflake precision —
 * see CLAUDE.md "ID Handling" section.
 */
@Data
public class GoalCreateRequest {

    private String conversationId;
    private Long agentId;
    private Long workspaceId;

    private String title;
    private String description;
    private String exitCriteria;
    private String successCheckPrompt;

    private Integer turnBudget;
    private Integer llmCallBudget;
    private Boolean autoFollowupEnabled;
    private Integer followupCooldownSeconds;
}
