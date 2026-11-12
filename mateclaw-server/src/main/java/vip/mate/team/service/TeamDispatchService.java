package vip.mate.team.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import vip.mate.decision.api.DecisionRecordingException;
import vip.mate.decision.api.DecisionTicket;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vip.mate.team.event.TeamTasksDelegatedEvent;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskEventEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private WorkerDecisionAdapter decisionAdapter;

    @Autowired(required = false)
    public void setDecisionAdapter(WorkerDecisionAdapter adapter) { this.decisionAdapter = adapter; }

    /** Result summaries are capped before persisting to keep the board readable. */
    static final int MAX_RESULT_CHARS = TeamTaskService.MAX_RESULT_CHARS;

    /** Empty/fallback member runs get one recovery attempt, not three long identical runs. */
    static final int MAX_RESPONSE_FAILURE_DISPATCHES = 2;

    private static final Pattern GENERATED_FILE_MARKDOWN_LINK = Pattern.compile(
            "\\[([^\\]\\r\\n]{1,200})]\\(((?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/[A-Za-z0-9-]+)\\)");

    private static final Set<String> CLARIFICATION_CUES = Set.of(
            "您希望",
            "你希望",
            "是否继续",
            "请问",
            "能否",
            "可以提供",
            "请提供",
            "需要您",
            "需要你",
            "我应该",
            "如何处理",
            "what would you like",
            "how should i",
            "could you provide",
            "please provide",
            "do you want me",
            "should i",
            "would you like");

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
    private final ApprovalWorkflowService approvalService;

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
            // assignTask clears the persisted reason for a clean running state,
            // but the worker needs the previous failure feedback or an
            // automatic retry is just the same prompt sent to the same agent.
            if (assigned != null && (assigned.getReason() == null || assigned.getReason().isBlank())
                    && task.getReason() != null && !task.getReason().isBlank()) {
                assigned.setReason(task.getReason());
            }
            DISPATCH_EXECUTOR.submit(() -> runTask(teamId, assigned));
        }
    }

    /** Execute one dispatched task on its member agent, then settle the outcome. */
    void runTask(Long teamId, TeamTaskEntity task) {
        Long memberId = task.getAssigneeAgentId();
        if (!runningMembers.add(memberId)) {
            // Same member picked up concurrently in this JVM; put the task back.
            taskService.requeueUnusableResult(task.getId(),
                    "Member is already executing another task; waiting for availability.");
            return;
        }
        String childConvId = "team-task-" + IdUtil.fastSimpleUUID();
        WorkerDecisionAdapter.Snapshot snapshot = WorkerDecisionAdapter.Snapshot.capture(task).withConversation(childConvId);
        ScheduledFuture<?> heartbeat = null;
        String reply = null;
        String finishReason = null;
        Exception runFailure = null;
        try {
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
                heartbeat = startLeaseHeartbeat(task.getId());
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
                reply = result == null ? null : result.content();
                finishReason = result == null ? null : result.finishReason();
                if (reply != null && !reply.isBlank()) {
                    conversationService.saveMessage(childConvId, "assistant", reply);
                }

                PendingApproval pending = approvalService.findPendingByConversation(childConvId);
                if (pending != null) {
                    String summary = pending.getSummary() == null || pending.getSummary().isBlank()
                            ? pending.getReason() : pending.getSummary();
                    if (taskService.parkForToolApproval(task.getId(), pending.getPendingId(), summary)) {
                        TeamTaskEntity parked = taskService.getTask(task.getId());
                        broadcast(parked != null ? parked : task, "team_task_awaiting_approval",
                                Map.of("pendingId", pending.getPendingId(),
                                        "toolName", pending.getToolName() == null ? "" : pending.getToolName(),
                                        "summary", summary == null ? "Tool approval required" : summary));
                    }
                    return;
                }

            } catch (Exception error) {
                runFailure = error;
            } finally {
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
                streamTracker.complete(childConvId);
                runningMembers.remove(memberId);
            }
            // Close the child before settlement can wake the parent or dispatch
            // follow-up work. A lead must never observe a finished task with its
            // worker still registered as running.
            try {
                if (runFailure != null) {
                    throw runFailure;
                }
                settleOutcome(task, reply, snapshot, finishReason);
            } catch (Exception error) {
                settleRunFailure(teamId, task, error);
            }
        } finally {
            requestDispatch(teamId);
        }
    }

    private void settleRunFailure(Long teamId, TeamTaskEntity task, Exception error) {
        if (DecisionRecordingException.find(error) != null) {
            log.warn("Team task #{} decision recording failed; retaining current state", task.getTaskNumber());
            return;
        }
        log.warn("Team {} task #{} member run ended exceptionally: {}", teamId,
                task.getTaskNumber(), error.getMessage());
        // A cancelled worker must not publish a failure over its terminal state.
        boolean failed = taskService.failTask(task.getId(),
                truncate("member run error: " + error.getMessage(), 1000));
        if (failed) {
            broadcast(task, "team_task_failed", Map.of("reason", String.valueOf(error.getMessage())));
            announceService.announceTaskSettled(taskService.getTask(task.getId()));
        }
    }

    /** Share the same DB-backed lease heartbeat with controlled worker replays. */
    ScheduledFuture<?> startLeaseHeartbeat(Long taskId) {
        return HEARTBEAT_SCHEDULER.scheduleAtFixedRate(
                () -> taskService.renewLock(taskId),
                HEARTBEAT_MINUTES, HEARTBEAT_MINUTES, TimeUnit.MINUTES);
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
        settleOutcome(task, reply, WorkerDecisionAdapter.Snapshot.capture(task));
    }

    void settleOutcome(TeamTaskEntity task, String reply, WorkerDecisionAdapter.Snapshot snapshot) {
        settleOutcome(task, reply, snapshot, null);
    }

    void settleOutcome(TeamTaskEntity task, String reply, WorkerDecisionAdapter.Snapshot snapshot, String finishReason) {
        TeamTaskEntity current = taskService.getTask(task.getId());
        if (current == null) {
            return;
        }
        if (decisionAdapter != null && decisionAdapter.enabled() && decisionAdapter.active()
                && TeamTaskStatus.IN_PROGRESS.equals(current.getStatus()) && !snapshot.matches(current)) {
            var rejected = decisionAdapter.judge(task, "STALE_ATTEMPT", null);
            decisionAdapter.outcome(rejected.ticket(), false, "STALE_ATTEMPT");
            return;
        }
        if (TeamTaskStatus.IN_PROGRESS.equals(current.getStatus())) {
            // Runtime diagnostic prose is not a malformed business envelope. Explicit non-completion wins
            // even if the retained candidate happens to contain a valid COMPLETED object.
            if (finishReason != null && !finishReason.isBlank()
                    && !Set.of("normal", "summarized", "return_direct").contains(finishReason)) {
                String reason = "WORKER_RUNTIME_NOT_COMPLETED: finishReason="
                        + TeamResultExcerpt.label(finishReason, 80);
                boolean failed;
                if (decisionAdapter != null && decisionAdapter.enabled()) {
                    var judgment = decisionAdapter.judge(current, "WORKER_RUNTIME_NOT_COMPLETED", reply);
                    failed = taskService.failTask(task.getId(), reason, judgment.ticket(), snapshot);
                } else {
                    failed = taskService.failTask(task.getId(), reason);
                }
                if (failed) {
                    TeamTaskEntity settled = taskService.getTask(task.getId());
                    broadcast(settled != null ? settled : task, "team_task_failed", Map.of("reason", reason));
                    announceService.announceTaskSettled(settled);
                }
                return;
            }
            // Domain guard: a successful model invocation is not a completed business task.
            // OFF retains the legacy text contract; SHADOW records the corrected baseline.
            if (decisionAdapter != null && decisionAdapter.enabled()) {
                var result = vip.mate.agent.runtime.WorkerResultContract.parse(reply, MAX_RESULT_CHARS);
                if (!result.completed()) {
                    String reason = result.reason() + ": " + truncate(result.evidence(), 1000);
                    var judgment = decisionAdapter.judge(current, result.reason(), reply);
                    if (taskService.failTask(task.getId(), reason, judgment.ticket(), snapshot)) {
                        broadcast(task, "team_task_failed", Map.of("reason", reason));
                        announceService.announceTaskSettled(taskService.getTask(task.getId()));
                    }
                    return;
                }
                reply = result.text();
            }
            boolean attachedGeneratedFile = attachGeneratedFileDeliverable(current, reply, snapshot);
            String invalidReason = invalidResultReason(current, reply, attachedGeneratedFile);
            if (invalidReason != null) {
                int attempts = current.getDispatchCount() == null ? 0 : current.getDispatchCount();
                int maxAttempts = isResponseGenerationFailure(invalidReason)
                        ? MAX_RESPONSE_FAILURE_DISPATCHES
                        : TeamTaskService.MAX_DISPATCHES;
                if (decisionAdapter != null && decisionAdapter.enabled()) {
                    var judgment = decisionAdapter.judge(current, invalidCode(invalidReason), reply);
                    String actual;
                    if (decisionAdapter.active()) {
                        actual = taskService.settleUnusableResult(task.getId(), invalidReason,
                                attempts < maxAttempts, judgment.ticket(), snapshot);
                    } else {
                        actual = attempts < maxAttempts && taskService.requeueUnusableResult(task.getId(), invalidReason)
                                ? TeamTaskStatus.PENDING : taskService.failTask(task.getId(), invalidReason)
                                ? TeamTaskStatus.FAILED : null;
                        decisionAdapter.outcome(judgment.ticket(), actual != null,
                                actual == null ? "TRANSITION_REJECTED" : actual);
                    }
                    if (TeamTaskStatus.PENDING.equals(actual)) {
                        broadcast(task, "team_task_retrying", Map.of("reason", invalidReason));
                    } else if (TeamTaskStatus.FAILED.equals(actual)) {
                        broadcast(task, "team_task_failed", Map.of("reason", invalidReason));
                        announceService.announceTaskSettled(taskService.getTask(task.getId()));
                    }
                    return;
                }
                if (attempts < maxAttempts
                        && taskService.requeueUnusableResult(task.getId(), invalidReason)) {
                    log.warn("Team task #{} produced an unusable result on attempt {}/{}; requeued: {}",
                            task.getTaskNumber(), attempts, maxAttempts,
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
                    DecisionTicket ticket = (decisionAdapter == null || !decisionAdapter.enabled()) ? null
                            : decisionAdapter.judge(current, "CHECKPOINT_ACK", reply).ticket();
                    List<Long> released = completeWorker(task.getId(),
                            TeamResultExcerpt.render(reply, MAX_RESULT_CHARS, task.getId()), ticket, snapshot);
                    if (released == null) return;
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
                String step = "waiting for " + terminalCheckpoint + " checkpoint";
                if (decisionAdapter != null && decisionAdapter.enabled()) {
                    var judgment = decisionAdapter.judge(current, "CHECKPOINT_WAIT", reply);
                    if (decisionAdapter.active()) {
                        taskService.parkWorkerCheckpoint(task.getId(), percent, step, judgment.ticket(), snapshot);
                    } else {
                        boolean applied = taskService.updateProgress(task.getId(), null, percent, step);
                        decisionAdapter.outcome(judgment.ticket(), applied,
                                applied ? TeamTaskStatus.IN_PROGRESS : "TRANSITION_REJECTED");
                    }
                } else {
                    taskService.updateProgress(task.getId(), null, percent, step);
                }
                log.info("Team task #{} parked as long-running checkpoint tracker until {}",
                        task.getTaskNumber(), terminalCheckpoint);
                return;
            }
            DecisionTicket ticket = null;
            if (decisionAdapter != null && decisionAdapter.enabled()) {
                var judgment = decisionAdapter.judge(current, "VALID", reply);
                ticket = judgment.ticket();
                if (!judgment.accepted()) {
                    String reason = "worker result did not satisfy task requirements";
                    if (taskService.failTask(task.getId(), reason, ticket, snapshot)) {
                        broadcast(task, "team_task_failed", Map.of("reason", reason));
                        announceService.announceTaskSettled(taskService.getTask(task.getId()));
                    }
                    return;
                }
            }
            List<Long> released = completeWorker(task.getId(),
                    TeamResultExcerpt.render(reply == null || reply.isBlank() ? "(no output)" : reply,
                            MAX_RESULT_CHARS, task.getId()), ticket, snapshot);
            if (released == null) return;
            current = taskService.getTask(task.getId());
            log.info("Team task #{} auto-completed ({} dependents released)",
                    task.getTaskNumber(), released.size());
        }
        String event = switch (current.getStatus()) {
            case TeamTaskStatus.FAILED -> "team_task_failed";
            case TeamTaskStatus.IN_REVIEW -> "team_task_in_review";
            case TeamTaskStatus.COMPLETED -> "team_task_completed";
            default -> null;
        };
        if (event == null) {
            return;
        }
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

    private List<Long> completeWorker(Long taskId, String result, DecisionTicket ticket,
                                      WorkerDecisionAdapter.Snapshot snapshot) {
        if (ticket == null) return taskService.completeTask(taskId, null, result);
        var completed = taskService.completeTask(taskId, null, result, ticket, snapshot);
        return completed.applied() ? completed.released() : null;
    }

    private static String invalidCode(String reason) {
        return switch (reason) {
            case "member produced no result" -> "BLANK";
            case "member response generation failed" -> "PLACEHOLDER";
            case "required deliverable was not attached" -> "ARTIFACT_MISSING";
            default -> "CLARIFICATION";
        };
    }

    private String invalidResultReason(TeamTaskEntity task, String reply,
                                       boolean attachedGeneratedFile) {
        if (reply == null || reply.isBlank()) {
            return "member produced no result";
        }
        String normalized = reply.strip().toLowerCase();
        if (normalized.contains("failed to generate a response, please retry")
                || normalized.equals("(no output)")) {
            return "member response generation failed";
        }
        if (requiresDeliverable(task) && !attachedGeneratedFile
                && taskService.listDeliverables(task).isEmpty()) {
            return "required deliverable was not attached";
        }
        if (looksLikeClarificationQuestion(reply)) {
            return "member asked for clarification instead of producing a result";
        }
        return null;
    }

    private boolean isResponseGenerationFailure(String reason) {
        return "member produced no result".equals(reason)
                || "member response generation failed".equals(reason);
    }

    private boolean looksLikeClarificationQuestion(String reply) {
        if (reply == null) {
            return false;
        }
        String normalized = reply.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank() || normalized.length() > 800) {
            return false;
        }
        String lower = normalized.toLowerCase();
        boolean hasCue = CLARIFICATION_CUES.stream().anyMatch(lower::contains);
        if (!hasCue) {
            return false;
        }
        return normalized.endsWith("?")
                || normalized.endsWith("？")
                || lower.contains("what would you like")
                || lower.contains("how should i")
                || lower.contains("could you provide")
                || lower.contains("please provide")
                || normalized.contains("请问")
                || normalized.contains("是否继续")
                || normalized.contains("如何处理")
                || normalized.contains("请提供");
    }

    private boolean attachGeneratedFileDeliverable(TeamTaskEntity task, String reply, WorkerDecisionAdapter.Snapshot snapshot) {
        // A render link is a useful task artifact regardless of how the task
        // was created. The metadata flag controls validation/retry semantics,
        // not whether an otherwise valid generated file is discoverable in UI.
        if (reply == null || reply.isBlank()) {
            return false;
        }
        Matcher link = GENERATED_FILE_MARKDOWN_LINK.matcher(reply);
        while (link.find()) {
            String name = link.group(1).trim();
            String url = link.group(2).trim();
            if (!GeneratedFileCache.GENERATED_URL_PATTERN.matcher(url).matches()) {
                continue;
            }
            try {
                if (decisionAdapter != null && decisionAdapter.enabled() && decisionAdapter.active()) {
                    if (!taskService.addWorkerDeliverable(snapshot, task.getAssigneeAgentId(), name, url)) return false;
                } else {
                    taskService.addDeliverable(task.getId(), task.getAssigneeAgentId(), name, url);
                }
                log.info("Team task #{} auto-attached generated deliverable from member reply: {}",
                        task.getTaskNumber(), name);
                return true;
            } catch (Exception e) {
                log.warn("Team task #{} generated deliverable auto-attach failed for {}: {}",
                        task.getTaskNumber(), name, e.getMessage());
            }
        }
        return false;
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
    static final int LEAD_ATTACHMENT_CONTEXT_MESSAGES = 12;
    static final int MAX_LEAD_ATTACHMENT_ITEM_CHARS = 1200;
    static final int MAX_LEAD_ATTACHMENT_SECTION_CHARS = 6000;

    /** The full instruction envelope the member receives. */
    String buildDispatchContent(TeamTaskEntity task) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("[Assigned team task #").append(task.getTaskNumber())
                .append(" (taskId: ").append(task.getId()).append(")]\n")
                .append("Subject: ").append(task.getSubject()).append('\n');
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("\n").append(task.getDescription()).append('\n');
        }
        if (task.getDispatchCount() != null && task.getDispatchCount() > 1
                && task.getReason() != null && !task.getReason().isBlank()) {
            sb.append("\n[Retry feedback]\n")
                    .append("The previous attempt was rejected: ")
                    .append(truncate(task.getReason().strip(), 500))
                    .append(". Correct that failure in this attempt; do not repeat the same empty or fallback response.\n");
        }
        appendLeadAttachmentContext(sb, task);
        appendPrerequisiteResults(sb, task);
        sb.append("""

                [Instructions]
                - Execute this task now. Your final reply becomes the task result reported to the team lead, so end with a complete, self-contained summary of what you produced.
                - Report milestones with team_tasks(action="progress", taskId=%s, percent=..., step=...).
                - If the output is a document, spreadsheet or presentation, generate a real file (renderDocx / renderXlsx / renderPptx or the docx/pptx/xlsx skills) and register it with team_tasks(action="attach", taskId=%s, name="<file name>", url=<the download link the render tool returned>). Keep the result a summary — do not paste file contents.
                - If scope is ambiguous but you can make a reasonable assumption, state the assumption and continue.
                - If you are missing an input you cannot obtain yourself, call team_tasks(action="comment", taskId=%s, type="blocker", text="what you need") and stop. Do not ask the lead or user for clarification in your final reply.
                """.formatted(task.getId(), task.getId(), task.getId()));
        if (decisionAdapter != null && decisionAdapter.enabled()) {
            sb.append("\n[Final result contract]\n")
                    .append(vip.mate.agent.runtime.WorkerResultContract.INSTRUCTIONS)
                    .append("Keep the entire JSON under ").append(MAX_RESULT_CHARS).append(" characters.\n");
        }
        return sb.toString();
    }

    /**
     * Child worker conversations are isolated from the lead transcript, so
     * upload paths from the lead turn must be copied into the dispatch
     * envelope explicitly. Only rendered attachment/media rows with local
     * paths are included; ordinary lead chat text stays out of the member
     * prompt.
     */
    void appendLeadAttachmentContext(StringBuilder sb, TeamTaskEntity task) {
        String leadConversationId = task.getLeadConversationId();
        if (leadConversationId == null || leadConversationId.isBlank()) {
            return;
        }
        List<MessageEntity> messages = conversationService.listRecentMessages(
                leadConversationId, LEAD_ATTACHMENT_CONTEXT_MESSAGES);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        StringBuilder section = new StringBuilder();
        for (MessageEntity message : messages) {
            if (message == null || message.getContentParts() == null
                    || message.getContentParts().isBlank()) {
                continue;
            }
            String rendered = conversationService.renderMessageContent(message, true);
            if (rendered == null || rendered.isBlank() || !hasRenderedAttachmentPath(rendered)) {
                continue;
            }
            section.append("- ")
                    .append(truncate(rendered.strip(), MAX_LEAD_ATTACHMENT_ITEM_CHARS)
                            .replace("\n", "\n  "))
                    .append('\n');
        }
        if (section.isEmpty()) {
            return;
        }
        sb.append("\n[Lead conversation attachments]\n")
                .append(truncate(section.toString(), MAX_LEAD_ATTACHMENT_SECTION_CHARS))
                .append("Use these paths when this task refers to files uploaded in the lead conversation.\n");
    }

    private static boolean hasRenderedAttachmentPath(String rendered) {
        if (!rendered.contains("路径:")) {
            return false;
        }
        return rendered.contains("[附件]")
                || rendered.contains("[图片]")
                || rendered.contains("[视频]")
                || rendered.contains("[音频]")
                || rendered.contains("[3D 模型]");
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
        int visibleCount = Math.min(blockerIds.size(), 8);
        int itemBudget = (MAX_PREREQ_SECTION_CHARS - 256) / visibleCount;
        StringBuilder section = new StringBuilder("\n[Prerequisite results]\n");
        for (Long blockerId : blockerIds.subList(0, visibleCount)) {
            TeamTaskEntity blocker = taskService.getTask(blockerId);
            if (blocker == null) {
                continue;
            }
            String heading = "- #" + blocker.getTaskNumber() + " \""
                    + TeamResultExcerpt.label(blocker.getSubject(), 120) + "\" ("
                    + blocker.getStatus() + ")\n";
            String reference = "  Full worker output: " + TeamResultExcerpt.outputReference(blockerId) + "\n";
            StringBuilder row = new StringBuilder(heading);
            int resultBudget = Math.min(MAX_PREREQ_RESULT_CHARS,
                    (itemBudget - heading.length() - reference.length() - 120) * 2 / 3);
            if (blocker.getResult() != null && !blocker.getResult().isBlank()) {
                row.append(TeamResultExcerpt.render(blocker.getResult().strip(), resultBudget, blockerId))
                        .append('\n');
            }
            row.append(reference);
            int omittedFiles = 0;
            for (TeamTaskService.Deliverable file : taskService.listDeliverables(blocker)) {
                String fileLine = "  File: " + file.name() + " → " + file.url() + "\n";
                String inspectionPath = generatedFileInspectionPath(file.url());
                if (inspectionPath != null) {
                    fileLine += "  Inspect locally: " + inspectionPath + " (do not guess an HTTP port)\n";
                }
                if (row.length() + fileLine.length() + 120 <= itemBudget) {
                    row.append(fileLine);
                } else {
                    omittedFiles++;
                }
            }
            if (omittedFiles > 0) {
                row.append("  ").append(omittedFiles).append(" deliverable(s) omitted; use team_tasks(action=\"get\", taskId=\"")
                        .append(blockerId).append("\").\n");
            }
            section.append(row);
        }
        if (blockerIds.size() > visibleCount) {
            section.append(blockerIds.size() - visibleCount)
                    .append(" additional prerequisite(s) omitted; use team_tasks(action=\"get\", taskId=\"")
                    .append(task.getId()).append("\") to inspect dependencies.\n");
        }
        section.append("Use team_tasks(action=\"get\", taskId=...) for task records and deliverable links.\n");
        sb.append(section);
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
