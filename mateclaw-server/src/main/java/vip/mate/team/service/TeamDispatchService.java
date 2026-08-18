package vip.mate.team.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vip.mate.team.event.TeamTasksDelegatedEvent;
import vip.mate.agent.AgentService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskEventEntity;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    /**
     * Lease-renewal cadence while a member run is in flight: a third of the
     * lease keeps two renewal chances in hand even if one write is lost, so a
     * long-running member is never reclaimed as stale while still working.
     */
    private static final long HEARTBEAT_MINUTES = TeamTaskService.LOCK_MINUTES / 3;

    /** Single daemon thread firing lease-renewal heartbeats for all running tasks. */
    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "team-task-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final TeamService teamService;
    private final TeamTaskService taskService;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ChatStreamTracker streamTracker;
    private final TeamAnnounceService announceService;
    private final TeamEventChannel eventChannel;

    /** Members with a run currently in flight in this JVM (belt-and-braces on top of hasActiveTask). */
    private final Set<Long> runningMembers = ConcurrentHashMap.newKeySet();

    /**
     * Plan hand-off notification: sweep the board as soon as a delegated
     * plan's tasks land. Event-driven because the hand-off bridge cannot
     * depend on this service directly (bean cycle through the graph builder).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTeamTasksDelegated(TeamTasksDelegatedEvent event) {
        requestDispatch(event.teamId());
    }

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
        for (AgentTeamEntity team : teamService.listAllTeams()) {
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
            taskService.recordEvent(teamId, task.getId(), TeamTaskEventEntity.DISPATCHED,
                    TeamTaskService.AUTHOR_SYSTEM, null, "agent " + assignee);
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
    void runTask(Long teamId, TeamTaskEntity task) {
        Long memberId = task.getAssigneeAgentId();
        if (!runningMembers.add(memberId)) {
            // Same member picked up concurrently in this JVM; put the task back.
            taskService.retryTask(task.getId());
            return;
        }
        String childConvId = "team-task-" + IdUtil.fastSimpleUUID();
        ScheduledFuture<?> heartbeat = null;
        try {
            AgentTeamEntity team = teamService.getTeam(teamId);
            conversationService.createChildConversation(childConvId, memberId, "system",
                    team == null ? null : team.getWorkspaceId(), task.getLeadConversationId(),
                    "team_worker");
            taskService.attachConversation(task.getId(), childConvId);
            // Track the child run so graph nodes honor requestStop() — without a
            // registered RunState, cancelling the task could never interrupt the
            // member mid-run.
            streamTracker.register(childConvId);
            streamTracker.incrementFlux(childConvId);
            // Renew the execution lease while the member works; the conditional
            // UPDATE inside renewLock makes this a no-op once the task settles.
            heartbeat = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(
                    () -> taskService.renewLock(task.getId()),
                    HEARTBEAT_MINUTES, HEARTBEAT_MINUTES, TimeUnit.MINUTES);
            broadcast(task, "team_task_dispatched", Map.of());
            log.info("Team {} task #{} dispatched to agent {} (conv {})",
                    teamId, task.getTaskNumber(), memberId, childConvId);

            // Message persistence is the caller's contract (the graph expects the
            // current user message to already be the conversation's last row),
            // and the persisted pair is what makes the run's transcript
            // reviewable from the task card.
            String dispatchContent = buildDispatchContent(task);
            conversationService.saveMessage(childConvId, "user", dispatchContent);
            AgentService.ChatResult result = agentService.chatWithUsage(
                    memberId, dispatchContent, childConvId);
            String reply = result == null ? null : result.content();
            if (reply != null && !reply.isBlank()) {
                conversationService.saveMessage(childConvId, "assistant", reply);
            }

            settleOutcome(task, reply);
        } catch (Exception e) {
            log.warn("Team {} task #{} member run ended exceptionally: {}", teamId,
                    task.getTaskNumber(), e.getMessage());
            // Only report a failure the guarded transition actually applied — an
            // interrupted run whose task is already cancelled must not produce a
            // misleading failed event on top of the terminal state.
            boolean failed = taskService.failTask(task.getId(),
                    truncate("member run error: " + e.getMessage(), 1000));
            if (failed) {
                broadcast(task, "team_task_failed", Map.of("reason", String.valueOf(e.getMessage())));
                announceService.announceTaskSettled(taskService.getTask(task.getId()));
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            streamTracker.complete(childConvId);
            runningMembers.remove(memberId);
            // Chain: dispatch released dependents and the member's next task.
            requestDispatch(teamId);
        }
    }

    /**
     * Ask the member conversation executing this task to stop at the next graph
     * node boundary (cancel path). No-op when the task never dispatched or the
     * run already ended.
     */
    public void interruptRun(TeamTaskEntity task) {
        if (task == null || task.getConversationId() == null) {
            return;
        }
        if (streamTracker.requestStop(task.getConversationId())) {
            log.info("Team task #{} member run interrupted (conv {})",
                    task.getTaskNumber(), task.getConversationId());
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
            String invalidReason = invalidResultReason(current, reply);
            if (invalidReason != null) {
                int attempts = current.getDispatchCount() == null ? 0 : current.getDispatchCount();
                if (attempts < TeamTaskService.MAX_DISPATCHES
                        && taskService.requeueUnusableResult(task.getId(), invalidReason)) {
                    log.warn("Team task #{} produced an unusable result on attempt {}/{}; requeued: {}",
                            task.getTaskNumber(), attempts, TeamTaskService.MAX_DISPATCHES,
                            invalidReason);
                    broadcast(task, "team_task_retrying", Map.of("reason", invalidReason));
                    return;
                }
                boolean failed = taskService.failTask(task.getId(), invalidReason);
                TeamTaskEntity failedTask = taskService.getTask(task.getId());
                if (failed) {
                    broadcast(task, "team_task_failed", Map.of("reason", invalidReason));
                    announceService.announceTaskSettled(failedTask);
                }
                return;
            }
            String terminalCheckpoint = taskService.checkpointTerminalTag(current);
            if (terminalCheckpoint != null) {
                String terminalEvidence = "[checkpoint:" + terminalCheckpoint + "] acknowledged";
                boolean terminalAlreadyAcknowledged = taskService.listComments(current.getId()).stream()
                        .anyMatch(comment -> comment.getContent() != null
                                && comment.getContent().contains(terminalEvidence));
                if (terminalAlreadyAcknowledged) {
                    List<Long> released = taskService.completeTask(task.getId(), null,
                            truncate(reply, MAX_RESULT_CHARS));
                    TeamTaskEntity completed = taskService.getTask(task.getId());
                    log.info("Team task #{} completed after deferred {} acknowledgement "
                                    + "({} dependents released)",
                            task.getTaskNumber(), terminalCheckpoint, released.size());
                    broadcast(task, "team_task_completed", Map.of("status", TeamTaskStatus.COMPLETED));
                    announceService.announceTaskSettled(completed);
                    return;
                }
                int percent = current.getProgressPercent() == null
                        ? 1 : Math.max(1, current.getProgressPercent());
                taskService.updateProgress(task.getId(), null, percent,
                        "waiting for " + terminalCheckpoint + " checkpoint");
                log.info("Team task #{} parked as long-running checkpoint tracker until {}",
                        task.getTaskNumber(), terminalCheckpoint);
                return;
            }
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

    private String invalidResultReason(TeamTaskEntity task, String reply) {
        if (reply == null || reply.isBlank()) {
            return "member produced no result";
        }
        String normalized = reply.strip().toLowerCase();
        if (normalized.contains("failed to generate a response, please retry")
                || normalized.equals("(no output)")) {
            return "member response generation failed";
        }
        if (requiresDeliverable(task) && taskService.listDeliverables(task).isEmpty()) {
            return "required deliverable was not attached";
        }
        return null;
    }

    private boolean requiresDeliverable(TeamTaskEntity task) {
        try {
            return task.getMetadata() != null
                    && JSONUtil.parseObj(task.getMetadata()).getBool("deliverableRequired", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Per-prerequisite and whole-section caps keeping the envelope bounded. */
    static final int MAX_PREREQ_RESULT_CHARS = 1500;
    static final int MAX_PREREQ_SECTION_CHARS = 6000;

    /** The full instruction envelope the member receives; it cannot see the lead's conversation. */
    private String buildDispatchContent(TeamTaskEntity task) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("[Assigned team task #").append(task.getTaskNumber())
                .append(" (taskId: ").append(task.getId()).append(")]\n")
                .append("Subject: ").append(task.getSubject()).append('\n');
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("\n").append(task.getDescription()).append('\n');
        }
        appendPrerequisiteResults(sb, task);
        sb.append("""

                [Instructions]
                - Execute this task now. Your final reply becomes the task result reported to the team lead, so end with a complete, self-contained summary of what you produced.
                - Report milestones with team_tasks(action="progress", taskId=%s, percent=..., step=...).
                - If the output is a document, spreadsheet or presentation, generate a real file (renderDocx / renderXlsx / renderPptx or the docx/pptx/xlsx skills) and register it with team_tasks(action="attach", taskId=%s, name="<file name>", url=<the download link the render tool returned>). Keep the result a summary — do not paste file contents.
                - If you are missing an input you cannot obtain yourself, call team_tasks(action="comment", taskId=%s, type="blocker", text="what you need") and stop.
                """.formatted(task.getId(), task.getId(), task.getId()));
        return sb.toString();
    }

    /**
     * Hand the member everything its prerequisites produced: result summaries
     * and deliverable links, so upstream output flows downstream without the
     * lead re-typing it. Bounded by per-item and whole-section caps — the
     * member can fetch the full record with team_tasks(action="get").
     */
    void appendPrerequisiteResults(StringBuilder sb, TeamTaskEntity task) {
        List<Long> blockerIds = TeamTaskService.parseIdArray(task.getBlockedBy());
        if (blockerIds.isEmpty()) {
            return;
        }
        StringBuilder section = new StringBuilder();
        for (Long blockerId : blockerIds) {
            TeamTaskEntity blocker = taskService.getTask(blockerId);
            if (blocker == null) {
                continue;
            }
            section.append("- #").append(blocker.getTaskNumber())
                    .append(" \"").append(blocker.getSubject()).append("\" (")
                    .append(blocker.getStatus()).append(')');
            if (blocker.getResult() != null && !blocker.getResult().isBlank()) {
                section.append(": ").append(truncate(blocker.getResult().strip(),
                        MAX_PREREQ_RESULT_CHARS));
            }
            section.append('\n');
            for (TeamTaskService.Deliverable file : taskService.listDeliverables(blocker)) {
                section.append("  File: ").append(file.name()).append(" → ")
                        .append(file.url()).append('\n');
                String inspectionPath = generatedFileInspectionPath(file.url());
                if (inspectionPath != null) {
                    section.append("  Inspect locally: ").append(inspectionPath)
                            .append(" (do not guess an HTTP port)\n");
                }
            }
        }
        if (section.isEmpty()) {
            return;
        }
        sb.append("\n[Prerequisite results]\n")
                .append(truncate(section.toString(), MAX_PREREQ_SECTION_CHARS))
                .append("Use team_tasks(action=\"get\", taskId=...) for any full record.\n");
    }

    static String generatedFileInspectionPath(String url) {
        String prefix = "/api/v1/files/generated/";
        if (url == null || !url.startsWith(prefix)) {
            return null;
        }
        String fileId = url.substring(prefix.length());
        if (!fileId.matches("[A-Za-z0-9-]+")) {
            return null;
        }
        return "../generated-files/" + fileId;
    }

    /** Push a task event onto the team channel and the lead conversation's stream. */
    private void broadcast(TeamTaskEntity task, String event, Map<String, Object> extra) {
        eventChannel.publishTaskEvent(task, event, extra);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n...(truncated)";
    }
}
