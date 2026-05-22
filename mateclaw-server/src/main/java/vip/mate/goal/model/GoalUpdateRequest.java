package vip.mate.goal.model;

import lombok.Data;

/**
 * Request body for {@code PATCH /api/v1/goals/{id}}. All fields nullable;
 * only present fields are applied (sparse update).
 *
 * <p>Fields not editable post-create: {@code conversationId},
 * {@code agentId}, {@code workspaceId}, {@code createdBy}, {@code status}
 * (use the dedicated pause/resume/abandon endpoints).
 */
@Data
public class GoalUpdateRequest {

    private String title;
    private String description;
    private String exitCriteria;
    private String successCheckPrompt;

    private Integer turnBudget;
    private Integer llmCallBudget;
    private Boolean autoFollowupEnabled;
    private Integer followupCooldownSeconds;
}
