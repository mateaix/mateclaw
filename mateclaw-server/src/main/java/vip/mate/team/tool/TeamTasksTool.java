package vip.mate.team.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskCommentEntity;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.service.TeamDispatchService;
import vip.mate.team.service.TeamService;
import vip.mate.team.service.TeamTaskService;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared team task board exposed to the LLM. One multi-action tool (rather
 * than one tool per action) keeps the schema compact and mirrors how the
 * model already phrases board operations as an action verb plus fields.
 *
 * Role gating: only the lead creates/cancels/retries tasks; members complete,
 * report progress, and comment; everyone reads. All errors return structured
 * strings written for LLM self-correction, never exceptions.
 *
 * @author MateClaw Team
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TeamTasksTool {

    private final TeamService teamService;
    private final TeamTaskService taskService;
    private final TeamDispatchService dispatchService;
    private final ConversationService conversationService;
    private final AgentMapper agentMapper;

    @Tool(description = "Operate your team's shared task board. Actions: "
            + "'list' all tasks; 'get' one task with comments (taskId); "
            + "'create' a task (lead only; subject, description, assigneeAgentId required, "
            + "optional blockedBy comma-separated prerequisite task ids, priority, higher first); "
            + "'complete' a task with its result summary (taskId, result); "
            + "'progress' to report execution progress (taskId, percent 0-100, step); "
            + "'comment' to leave a note, or type='blocker' when you are stuck and need the lead "
            + "(taskId, text); 'cancel' (lead only; taskId, text as reason); "
            + "'retry' a failed/stale task back to pending (lead only; taskId). "
            + "Only usable when you belong to an agent team.")
    public String team_tasks(
            @ToolParam(description = "One of: list, get, create, complete, progress, comment, cancel, retry")
            String action,
            @ToolParam(description = "Task id (string form is fine) — required by every action except list/create", required = false)
            String taskId,
            @ToolParam(description = "create: short task title", required = false)
            String subject,
            @ToolParam(description = "create: full task instructions; include every input the member needs — members do not see this conversation", required = false)
            String description,
            @ToolParam(description = "create: agentId of the member who should execute the task", required = false)
            String assigneeAgentId,
            @ToolParam(description = "create: comma-separated ids of tasks that must finish first", required = false)
            String blockedBy,
            @ToolParam(description = "create: priority, higher dispatches first (default 0)", required = false)
            Integer priority,
            @ToolParam(description = "complete: result summary reported back to the lead", required = false)
            String result,
            @ToolParam(description = "progress: completion percent 0-100", required = false)
            Integer percent,
            @ToolParam(description = "progress: one-line description of the current step", required = false)
            String step,
            @ToolParam(description = "comment/cancel: comment text or cancellation reason", required = false)
            String text,
            @ToolParam(description = "comment: 'note' (default) or 'blocker' to escalate to the lead", required = false)
            String type,
            @Nullable ToolContext ctx) {

        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId == null || conversationId.isBlank()) {
            return "Error: no conversation context bound to this call.";
        }
        ConversationEntity conversation = conversationService.findByConversationId(conversationId);
        if (conversation == null || conversation.getAgentId() == null) {
            return "Error: cannot resolve the calling agent for this conversation.";
        }
        Long agentId = conversation.getAgentId();
        Optional<AgentTeamEntity> teamOpt = teamService.getTeamForAgent(agentId);
        if (teamOpt.isEmpty()) {
            return "Error: you are not part of any agent team; team_tasks is unavailable.";
        }
        AgentTeamEntity team = teamOpt.get();
        boolean isLead = teamService.isLead(team, agentId);

        try {
            return switch (action == null ? "" : action) {
                case "list" -> renderBoard(team);
                case "get" -> renderDetail(team, parseId(taskId, "taskId"));
                case "create" -> createTask(team, agentId, isLead, subject, description,
                        assigneeAgentId, blockedBy, priority, conversationId);
                case "complete" -> completeTask(team, agentId, parseId(taskId, "taskId"), result);
                case "progress" -> progress(team, agentId, parseId(taskId, "taskId"), percent, step);
                case "comment" -> comment(team, agentId, parseId(taskId, "taskId"), type, text);
                case "cancel" -> cancel(team, isLead, parseId(taskId, "taskId"), text);
                case "retry" -> retry(team, isLead, parseId(taskId, "taskId"));
                default -> "Error: unknown action '" + action
                        + "'. Use one of: list, get, create, complete, progress, comment, cancel, retry.";
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.warn("team_tasks {} failed for team={} agent={}: {}",
                    action, team.getId(), agentId, e.getMessage());
            return "Error: team_tasks failed — " + e.getMessage();
        }
    }

    // ==================== actions ====================

    private String createTask(AgentTeamEntity team, Long agentId, boolean isLead,
                              String subject, String description, String assigneeAgentId,
                              String blockedBy, Integer priority, String conversationId) {
        if (!isLead) {
            return "Error: only the team lead can create tasks. Report blockers or ask the "
                    + "lead via a comment on your current task instead.";
        }
        TeamTaskEntity task = taskService.createTask(TeamTaskCreateCommand.builder()
                .teamId(team.getId())
                .subject(subject)
                .description(description)
                .assigneeAgentId(parseId(assigneeAgentId, "assigneeAgentId"))
                .createdByAgentId(agentId)
                .priority(priority)
                .blockedBy(parseIdList(blockedBy))
                .leadConversationId(conversationId)
                .build());
        if (TeamTaskStatus.PENDING.equals(task.getStatus())) {
            dispatchService.requestDispatch(team.getId());
        }
        return "✓ Created task #" + task.getTaskNumber() + " (id: " + task.getId()
                + ") \"" + task.getSubject() + "\" assigned to " + agentName(task.getAssigneeAgentId())
                + ". Status: " + task.getStatus()
                + (TeamTaskStatus.BLOCKED.equals(task.getStatus())
                        ? " (starts automatically once its prerequisites finish)." : ".")
                + " Members are dispatched automatically — do not wait in this turn.";
    }

    private String completeTask(AgentTeamEntity team, Long agentId, Long taskId, String result) {
        requireTaskInTeam(team, taskId);
        if (result == null || result.isBlank()) {
            return "Error: result is required — summarize what was produced.";
        }
        List<Long> released = taskService.completeTask(taskId, agentId, result);
        if (!released.isEmpty()) {
            dispatchService.requestDispatch(team.getId());
        }
        TeamTaskEntity task = taskService.getTask(taskId);
        StringBuilder sb = new StringBuilder("✓ Task #" + task.getTaskNumber() + " "
                + task.getStatus() + ".");
        if (TeamTaskStatus.IN_REVIEW.equals(task.getStatus())) {
            sb.append(" It awaits human approval before counting as done.");
        }
        if (!released.isEmpty()) {
            sb.append(" Released ").append(released.size()).append(" dependent task(s).");
        }
        return sb.toString();
    }

    private String progress(AgentTeamEntity team, Long agentId, Long taskId,
                            Integer percent, String step) {
        requireTaskInTeam(team, taskId);
        if (percent != null && (percent < 0 || percent > 100)) {
            return "Error: percent must be between 0 and 100.";
        }
        boolean ok = taskService.updateProgress(taskId, agentId, percent, step);
        return ok ? "✓ Progress recorded."
                : "Error: task is not in progress under your ownership; progress not recorded.";
    }

    private String comment(AgentTeamEntity team, Long agentId, Long taskId,
                           String type, String text) {
        requireTaskInTeam(team, taskId);
        if (text == null || text.isBlank()) {
            return "Error: text is required for a comment.";
        }
        boolean escalated = taskService.addComment(taskId, TeamTaskService.AUTHOR_AGENT,
                String.valueOf(agentId), type, text);
        return escalated
                ? "✓ Blocker recorded. The task is now failed and the lead has been notified — stop working on it."
                : "✓ Comment added.";
    }

    private String cancel(AgentTeamEntity team, boolean isLead, Long taskId, String reason) {
        if (!isLead) {
            return "Error: only the team lead can cancel tasks.";
        }
        requireTaskInTeam(team, taskId);
        taskService.cancelTask(taskId, reason);
        return "✓ Task cancelled.";
    }

    private String retry(AgentTeamEntity team, boolean isLead, Long taskId) {
        if (!isLead) {
            return "Error: only the team lead can retry tasks.";
        }
        requireTaskInTeam(team, taskId);
        if (!taskService.retryTask(taskId)) {
            return "Error: only failed or stale tasks can be retried.";
        }
        dispatchService.requestDispatch(team.getId());
        return "✓ Task reset to pending; it will be re-dispatched.";
    }

    // ==================== rendering ====================

    private String renderBoard(AgentTeamEntity team) {
        List<TeamTaskEntity> tasks = taskService.listTasks(team.getId(), null);
        if (tasks.isEmpty()) {
            return "The task board is empty.";
        }
        StringBuilder sb = new StringBuilder("Task board for team \"")
                .append(team.getName()).append("\" (").append(tasks.size()).append(" tasks):\n");
        for (TeamTaskEntity task : tasks) {
            sb.append("- #").append(task.getTaskNumber())
                    .append(" [").append(task.getStatus()).append("] ")
                    .append(task.getSubject())
                    .append(" (id: ").append(task.getId())
                    .append(", assignee: ").append(agentName(task.getAssigneeAgentId()));
            if (task.getProgressPercent() != null
                    && TeamTaskStatus.IN_PROGRESS.equals(task.getStatus())) {
                sb.append(", ").append(task.getProgressPercent()).append('%');
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    private String renderDetail(AgentTeamEntity team, Long taskId) {
        TeamTaskEntity task = requireTaskInTeam(team, taskId);
        StringBuilder sb = new StringBuilder(512);
        sb.append("Task #").append(task.getTaskNumber())
                .append(" (id: ").append(task.getId()).append(")\n")
                .append("Subject: ").append(task.getSubject()).append('\n')
                .append("Status: ").append(task.getStatus()).append('\n')
                .append("Assignee: ").append(agentName(task.getAssigneeAgentId())).append('\n');
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("Description: ").append(task.getDescription()).append('\n');
        }
        if (task.getProgressStep() != null) {
            sb.append("Progress: ").append(task.getProgressPercent() == null ? "?"
                    : task.getProgressPercent()).append("% — ").append(task.getProgressStep()).append('\n');
        }
        if (task.getResult() != null && !task.getResult().isBlank()) {
            sb.append("Result: ").append(task.getResult()).append('\n');
        }
        if (task.getReason() != null && !task.getReason().isBlank()) {
            sb.append("Reason: ").append(task.getReason()).append('\n');
        }
        List<TeamTaskCommentEntity> comments = taskService.listComments(taskId);
        if (!comments.isEmpty()) {
            sb.append("Comments:\n");
            for (TeamTaskCommentEntity comment : comments) {
                sb.append("- [").append(comment.getCommentType()).append("] ")
                        .append(comment.getAuthorType()).append(' ').append(comment.getAuthorId())
                        .append(": ").append(comment.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    // ==================== helpers ====================

    private TeamTaskEntity requireTaskInTeam(AgentTeamEntity team, Long taskId) {
        TeamTaskEntity task = taskService.getTask(taskId);
        if (task == null || !task.getTeamId().equals(team.getId())) {
            throw new IllegalArgumentException("task " + taskId + " not found on this team's board");
        }
        return task;
    }

    private String agentName(Long agentId) {
        if (agentId == null) {
            return "-";
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        return agent != null && agent.getName() != null ? agent.getName() : String.valueOf(agentId);
    }

    private static Long parseId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a numeric id, got: " + raw);
        }
    }

    private static List<Long> parseIdList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                ids.add(parseId(part, "blockedBy entry"));
            }
        }
        return ids;
    }
}
