package vip.mate.goal.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outward-facing shape of a goal. Identical to {@link GoalEntity} except the
 * checklist is the parsed {@code List<GoalCriterion>} array rather than the
 * raw JSON String stored in the column — so REST responses and SSE payloads
 * always carry {@code criteria} as an array, never a string.
 *
 * <p>{@code criteria} is never null on the wire: a missing / unparseable
 * column maps to an empty list.
 */
@Data
public class GoalResponse {

    private Long id;
    private String conversationId;
    private Long agentId;
    private Long workspaceId;
    private String createdBy;

    private String title;
    private String description;
    private String exitCriteria;
    private String successCheckPrompt;

    private GoalStatus status;

    private Integer turnBudget;
    private Integer turnsUsed;
    private Integer llmCallBudget;
    private Integer agentLlmCallsUsed;
    private Integer evalLlmCallsUsed;
    private int totalLlmCallsUsed;

    private String progressSummary;
    private Double completionScore;
    private LocalDateTime lastEvaluationAt;

    private Boolean autoFollowupEnabled;
    private Integer followupCooldownSeconds;
    private LocalDateTime lastFollowupAt;

    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** Parsed checklist; empty (never null) when the column is null/unparseable. */
    private List<GoalCriterion> criteria;
}
