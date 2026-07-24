package vip.mate.team.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Input for creating a team task. The assignee is mandatory: every task must
 * name the member expected to execute it, so each delegation is trackable on
 * the board.
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class TeamTaskCreateCommand {

    private Long teamId;

    private String subject;

    private String description;

    /** Required: the member agent expected to execute this task. */
    private Long assigneeAgentId;

    /** Creating agent id; NULL when a human creates the task from the board. */
    private Long createdByAgentId;

    /** Higher dispatches first; defaults to 0. */
    private Integer priority;

    /** general / request / note; defaults to general. */
    private String taskType;

    /** Prerequisite task ids; non-empty list creates the task in blocked status. */
    private List<Long> blockedBy;

    /** Park completion in in_review for human approval. */
    private boolean requireApproval;

    /** Lead conversation that originated the task (result routing). */
    private String leadConversationId;

    private String username;

    private String channel;

    /** Optional JSON metadata (attachments, origin routing, trace ids, ...). */
    private String metadata;
}
