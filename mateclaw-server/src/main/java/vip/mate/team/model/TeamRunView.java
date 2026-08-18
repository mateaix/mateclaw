package vip.mate.team.model;

import java.time.LocalDateTime;
import java.util.List;

/** Stable read projection for a team run and its tasks. */
public record TeamRunView(
        Long id,
        Long teamId,
        Long workspaceId,
        Long leadAgentId,
        String leadConversationId,
        Long originMessageId,
        String title,
        String objective,
        String status,
        String finalSummary,
        String stopReason,
        String metadata,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        String projectionCompleteness,
        String outcomeQuality,
        List<Deliverable> deliverables,
        List<MemberContribution> contributions,
        List<AttentionItem> attentionItems,
        Liveness liveness,
        Metrics metrics,
        Progress progress,
        List<Task> tasks
) {

    /** Compatibility constructor retained while clients adopt the canonical projection fields. */
    public TeamRunView(Long id, Long teamId, Long workspaceId, Long leadAgentId,
                       String leadConversationId, Long originMessageId, String title,
                       String objective, String status, String finalSummary, String stopReason,
                       String metadata, LocalDateTime startedAt, LocalDateTime completedAt,
                       LocalDateTime createTime, LocalDateTime updateTime, Progress progress,
                       List<Task> tasks) {
        this(id, teamId, workspaceId, leadAgentId, leadConversationId, originMessageId, title,
                objective, status, finalSummary, stopReason, metadata, startedAt, completedAt,
                createTime, updateTime, "full", null, List.of(), List.of(), List.of(), null, null,
                progress, tasks);
    }

    public record Progress(int total, int done, int failed, int inReview, int percent) {
    }

    public record Deliverable(String id, String name, String url, String type,
                              List<Long> sourceTaskIds, List<Long> sourceAgentIds,
                              LocalDateTime createdAt, String verificationStatus) {
    }

    public record MemberContribution(Long taskId, Long agentId, String subject, String status,
                                     Long durationSeconds, LocalDateTime lastActivityAt,
                                     String resultSummary, String conversationId) {
    }

    public record AttentionItem(String id, String type, String severity, int priority, Long taskId,
                                String message, LocalDateTime createdAt) {
    }

    public record Liveness(String state, LocalDateTime lastActivityAt) {
    }

    public record Metrics(Long durationSeconds, int totalTasks, int completedTasks,
                          int failedTasks, int deliverableCount) {
    }

    public record Task(
            Long id,
            Long teamId,
            Long runId,
            Integer taskNumber,
            String subject,
            String description,
            String status,
            Integer priority,
            String taskType,
            Long assigneeAgentId,
            Long ownerAgentId,
            String blockedBy,
            Boolean requireApproval,
            Integer progressPercent,
            String progressStep,
            String result,
            String reason,
            String conversationId,
            String metadata,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {

        public static Task from(TeamTaskEntity task) {
            return new Task(task.getId(), task.getTeamId(), task.getRunId(), task.getTaskNumber(),
                    task.getSubject(), task.getDescription(), task.getStatus(), task.getPriority(),
                    task.getTaskType(), task.getAssigneeAgentId(), task.getOwnerAgentId(),
                    task.getBlockedBy(), task.getRequireApproval(), task.getProgressPercent(),
                    task.getProgressStep(), task.getResult(), task.getReason(), task.getConversationId(),
                    task.getMetadata(), task.getCreateTime(), task.getUpdateTime());
        }

        public static Task summaryFrom(TeamTaskEntity task) {
            return new Task(task.getId(), task.getTeamId(), task.getRunId(), task.getTaskNumber(),
                    task.getSubject(), null, task.getStatus(), task.getPriority(), task.getTaskType(),
                    task.getAssigneeAgentId(), task.getOwnerAgentId(), task.getBlockedBy(),
                    task.getRequireApproval(), task.getProgressPercent(), task.getProgressStep(), null,
                    task.getReason(), task.getConversationId(), null, task.getCreateTime(),
                    task.getUpdateTime());
        }
    }
}
