package vip.mate.team.service;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.workspace.conversation.ConversationService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dispatches board tasks to their assigned member agents and closes the
 * execution loop: run the member in an isolated child conversation, complete
 * (or fail) the task from the run outcome, then re-sweep so released
 * dependents and the now-idle member pick up follow-up work.
 *
 * Concurrency model: the sweep itself takes no locks — {@code assignTask}'s
 * conditional UPDATE (pending → in_progress) is the single arbiter, so
 * overlapping sweeps can never double-dispatch a task. One member executes at
 * most one task at a time. A scheduled sweep self-heals anything a
 * notification-path dispatch missed (releases, retries, restarts).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamDispatchService {

    /** Result summaries are capped before persisting to keep the board readable. */
    static final int MAX_RESULT_CHARS = 8000;

    /** One JDK 21 virtual thread per member-agent run. */
    private static final ExecutorService DISPATCH_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final TeamService teamService;
    private final TeamTaskService taskService;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ChatStreamTracker streamTracker;
    private final TeamAnnounceService announceService;

    /** Members with a run currently in flight in this JVM (belt-and-braces on top of hasActiveTask). */
    private final Set<Long> runningMembers = ConcurrentHashMap.newKeySet();

    /** Asynchronously sweep the team's board and dispatch whatever is eligible. */
    public void requestDispatch(Long teamId) {
        DISPATCH_EXECUTOR.submit(() -> {
            try {
                sweep(teamId);
            } catch (Exception e) {
                log.warn("Team {} dispatch sweep failed: {}", teamId, e.getMessage());
            }
        });
    }

    /**
     * Periodic self-heal: mark expired leases stale, then sweep every active
     * team so released dependents, manual retries and work orphaned by a
     * restart are dispatched even when no tool-path notification fired.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledSweep() {
        taskService.recoverStaleTasks();
        for (AgentTeamEntity team : teamService.listTeams()) {
            if (TeamService.STATUS_ACTIVE.equals(team.getStatus())) {
                try {
                    sweep(team.getId());
                } catch (Exception e) {
                    log.warn("Scheduled sweep failed for team {}: {}", team.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Dispatch at most one eligible pending task per assignee. Priority order
     * comes from the query; the conditional assign makes the winner unique.
     */
    void sweep(Long teamId) {
        List<TeamTaskEntity> candidates = taskService.findDispatchable(teamId);
        if (candidates.isEmpty()) {
            return;
        }
        Set<Long> dispatchedThisRound = new HashSet<>();
        for (TeamTaskEntity task : candidates) {
            Long assignee = task.getAssigneeAgentId();
            if (dispatchedThisRound.contains(assignee)
                    || runningMembers.contains(assignee)
                    || taskService.hasActiveTask(teamId, assignee)) {
                continue;
            }
            if (!taskService.assignTask(task.getId(), assignee)) {
                continue; // another sweep won the race, or status moved on
            }
            if (!taskService.tryAcquireDispatch(task.getId())) {
                // Circuit breaker tripped; the task was auto-failed — the lead
                // must hear about it or the work silently disappears.
                announceService.announceTaskSettled(taskService.getTask(task.getId()));
                continue;
            }
            dispatchedThisRound.add(assignee);
            TeamTaskEntity assigned = taskService.getTask(task.getId());
            DISPATCH_EXECUTOR.submit(() -> runTask(teamId, assigned));
        }
    }

    /** Execute one dispatched task on its member agent, then settle the outcome. */
    private void runTask(Long teamId, TeamTaskEntity task) {
        Long memberId = task.getAssigneeAgentId();
        if (!runningMembers.add(memberId)) {
            // Same member picked up concurrently in this JVM; put the task back.
            taskService.retryTask(task.getId());
            return;
        }
        String childConvId = "team-task-" + IdUtil.fastSimpleUUID();
        try {
            conversationService.createChildConversation(childConvId, memberId, "system",
                    null, task.getLeadConversationId());
            taskService.attachConversation(task.getId(), childConvId);
            broadcast(task, "team_task_dispatched", Map.of());
            log.info("Team {} task #{} dispatched to agent {} (conv {})",
                    teamId, task.getTaskNumber(), memberId, childConvId);

            AgentService.ChatResult result = agentService.chatWithUsage(
                    memberId, buildDispatchContent(task), childConvId);

            settleOutcome(task, result == null ? null : result.content());
        } catch (Exception e) {
            log.warn("Team {} task #{} member run failed: {}", teamId, task.getTaskNumber(),
                    e.getMessage());
            taskService.failTask(task.getId(), truncate("member run error: " + e.getMessage(), 1000));
            broadcast(task, "team_task_failed", Map.of("reason", String.valueOf(e.getMessage())));
            announceService.announceTaskSettled(taskService.getTask(task.getId()));
        } finally {
            runningMembers.remove(memberId);
            // Chain: dispatch released dependents and the member's next task.
            requestDispatch(teamId);
        }
    }

    /**
     * Settle a finished member run. If the member already moved the task
     * (explicit complete, blocker fail) the run outcome is not applied on top;
     * otherwise the final reply becomes the task result (auto-completion).
     */
    void settleOutcome(TeamTaskEntity task, String reply) {
        TeamTaskEntity current = taskService.getTask(task.getId());
        if (current == null) {
            return;
        }
        if (TeamTaskStatus.IN_PROGRESS.equals(current.getStatus())) {
            List<Long> released = taskService.completeTask(task.getId(), null,
                    truncate(reply == null || reply.isBlank() ? "(no output)" : reply,
                            MAX_RESULT_CHARS));
            current = taskService.getTask(task.getId());
            log.info("Team task #{} auto-completed ({} dependents released)",
                    task.getTaskNumber(), released.size());
        }
        String event = switch (current.getStatus()) {
            case TeamTaskStatus.FAILED -> "team_task_failed";
            case TeamTaskStatus.IN_REVIEW -> "team_task_in_review";
            default -> "team_task_completed";
        };
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", current.getStatus());
        if (current.getResult() != null) {
            payload.put("resultPreview", truncate(current.getResult(), 200));
        }
        if (current.getReason() != null) {
            payload.put("reason", current.getReason());
        }
        broadcast(task, event, payload);
        announceService.announceTaskSettled(current);
    }

    /** The full instruction envelope the member receives; it cannot see the lead's conversation. */
    private String buildDispatchContent(TeamTaskEntity task) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("[Assigned team task #").append(task.getTaskNumber())
                .append(" (taskId: ").append(task.getId()).append(")]\n")
                .append("Subject: ").append(task.getSubject()).append('\n');
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("\n").append(task.getDescription()).append('\n');
        }
        sb.append("""

                [Instructions]
                - Execute this task now. Your final reply becomes the task result reported to the team lead, so end with a complete, self-contained summary of what you produced.
                - Report milestones with team_tasks(action="progress", taskId=%s, percent=..., step=...).
                - If you are missing an input you cannot obtain yourself, call team_tasks(action="comment", taskId=%s, type="blocker", text="what you need") and stop.
                """.formatted(task.getId(), task.getId()));
        return sb.toString();
    }

    /** Push a task event onto the lead conversation's SSE stream (UI + observability). */
    private void broadcast(TeamTaskEntity task, String event, Map<String, Object> extra) {
        if (task.getLeadConversationId() == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>(extra);
            payload.put("taskId", String.valueOf(task.getId()));
            payload.put("taskNumber", task.getTaskNumber());
            payload.put("subject", task.getSubject());
            payload.put("teamId", String.valueOf(task.getTeamId()));
            payload.put("assigneeAgentId", String.valueOf(task.getAssigneeAgentId()));
            streamTracker.broadcastObject(task.getLeadConversationId(), event, payload);
        } catch (Exception e) {
            log.debug("Team task event broadcast skipped: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n...(truncated)";
    }
}
