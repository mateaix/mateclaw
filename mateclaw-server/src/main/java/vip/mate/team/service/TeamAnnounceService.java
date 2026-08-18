package vip.mate.team.service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.workspace.conversation.ConversationService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers settled task results back to the team lead. Results arriving close
 * together are debounced per lead conversation and run and merged into ONE
 * combined announcement, so parallel members in the same run wake the lead
 * once instead of once per task. Different runs never share a batch.
 *
 * Delivery is guaranteed, not opportunistic: when the lead is mid-turn the
 * announcement is NOT injected into the running turn (an in-turn notification
 * is dropped if the turn ends before the next reasoning round — silent result
 * loss). Instead delivery re-arms itself until the lead is idle, then starts a
 * fresh lead turn in the originating conversation; the lead's synthesized
 * reply is persisted there and pushed over SSE.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamAnnounceService {

    /** Collect window: results arriving within it join the same announcement. */
    static final long DEBOUNCE_MILLIS = 2000;

    /** A batch drains immediately once it reaches this size. */
    static final int MAX_BATCH = 20;

    /** Re-check interval while waiting for a busy lead to go idle. */
    static final long BUSY_RETRY_MILLIS = 2000;

    /** Give up waiting and wake the lead anyway after this many busy retries. */
    static final int MAX_BUSY_RETRIES = 900; // ~30 minutes

    private static final ScheduledExecutorService DEBOUNCE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "team-announce-debounce");
                t.setDaemon(true);
                return t;
            });

    /** One JDK 21 virtual thread per lead wake-up run. */
    private static final ExecutorService ANNOUNCE_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final TeamService teamService;
    private final TeamTaskService taskService;
    private final AgentService agentService;
    private final AgentMapper agentMapper;
    private final RunningConversationRegistry runningConversations;
    private final ChatStreamTracker streamTracker;
    private final ConversationService conversationService;

    /** Pending items stay isolated by run while lead wake turns serialize by conversation. */
    private final Map<BatchKey, PendingBatch> pending = new ConcurrentHashMap<>();
    private final Set<String> drainOwners = ConcurrentHashMap.newKeySet();
    private final AtomicLong batchSequence = new AtomicLong();

    record BatchKey(String conversationId, Long runId) {
    }

    record AnnounceItem(Long taskId, Long teamId, Integer taskNumber, String subject, String status,
                        String memberName, String detail) {
    }

    private static final class PendingBatch {
        private final long sequence;
        private final List<AnnounceItem> items;
        private long readyAtMillis;
        private int retries;

        private PendingBatch(long sequence) {
            this(sequence, new ArrayList<>(), 0, 0);
        }

        private PendingBatch(long sequence, List<AnnounceItem> items, long readyAtMillis, int retries) {
            this.sequence = sequence;
            this.items = items;
            this.readyAtMillis = readyAtMillis;
            this.retries = retries;
        }
    }

    /**
     * Queue a settled task for announcement to its lead. Safe to call from any
     * thread; no-op when the task has no originating lead conversation.
     */
    public void announceTaskSettled(TeamTaskEntity task) {
        if (task == null || task.getLeadConversationId() == null) {
            return;
        }
        String detail = TeamTaskStatus.COMPLETED.equals(task.getStatus())
                || TeamTaskStatus.IN_REVIEW.equals(task.getStatus())
                ? task.getResult() : task.getReason();
        StringBuilder detailWithFiles = new StringBuilder(detail == null ? "" : detail);
        List<TeamTaskService.Deliverable> deliverables = taskService.listDeliverables(task);
        if (!deliverables.isEmpty()) {
            detailWithFiles.append("\nDeliverables (share these download links with the user):");
            for (TeamTaskService.Deliverable file : deliverables) {
                detailWithFiles.append("\n- ").append(file.name()).append(" → ").append(file.url());
            }
        }
        AnnounceItem item = new AnnounceItem(task.getId(), task.getTeamId(), task.getTaskNumber(),
                task.getSubject(), task.getStatus(),
                agentName(task.getAssigneeAgentId()),
                detailWithFiles.toString());

        BatchKey key = new BatchKey(task.getLeadConversationId(), task.getRunId());
        boolean drainNow = false;
        synchronized (pending) {
            PendingBatch batch = pending.computeIfAbsent(key,
                    ignored -> new PendingBatch(batchSequence.incrementAndGet()));
            batch.items.add(item);
            if (batch.items.size() >= MAX_BATCH) {
                drainNow = true;
            } else if (batch.items.size() == 1) {
                DEBOUNCE_SCHEDULER.schedule(() -> drain(key), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
        if (drainNow) {
            drain(key);
        }
    }

    /** Acquire the conversation turn, then take and deliver one run-isolated batch. */
    void drain(BatchKey key) {
        String conversationId = key.conversationId();
        if (!drainOwners.add(conversationId)) {
            return;
        }
        PendingBatch batch;
        synchronized (pending) {
            batch = pending.get(key);
            if (batch != null && batch.readyAtMillis <= System.currentTimeMillis()) {
                pending.remove(key);
            } else {
                batch = null;
            }
        }
        if (batch == null) {
            releaseAndScheduleNext(conversationId);
            return;
        }
        PendingBatch ownedBatch = batch;
        try {
            ANNOUNCE_EXECUTOR.submit(() -> deliverOwned(key, ownedBatch));
        } catch (RuntimeException e) {
            requeue(key, ownedBatch);
            releaseAndScheduleNext(conversationId);
            throw e;
        }
    }

    private void deliverOwned(BatchKey key, PendingBatch batch) {
        try {
            List<AnnounceItem> items = batch.items;
            Long teamId = items.get(0).teamId();
            AgentTeamEntity team = teamService.getTeam(teamId);
            if (team == null) {
                log.warn("Announce dropped: team {} vanished", teamId);
                return;
            }
            if (runningConversations.isActive(key.conversationId()) && batch.retries < MAX_BUSY_RETRIES) {
                batch.retries++;
                batch.readyAtMillis = System.currentTimeMillis() + BUSY_RETRY_MILLIS;
                requeue(key, batch);
                return;
            }
            wakeLead(team, key, buildAnnouncement(items), List.copyOf(items));
        } catch (Exception e) {
            batch.retries++;
            batch.readyAtMillis = System.currentTimeMillis() + BUSY_RETRY_MILLIS;
            requeue(key, batch);
            log.warn("Lead wake-up failed for conversation {} run {}: {}",
                    key.conversationId(), key.runId(), e.getMessage());
        } finally {
            releaseAndScheduleNext(key.conversationId());
        }
    }

    private void requeue(BatchKey key, PendingBatch batch) {
        synchronized (pending) {
            PendingBatch late = pending.remove(key);
            if (late != null) {
                batch.items.addAll(late.items);
            }
            pending.put(key, batch);
        }
    }

    private void releaseAndScheduleNext(String conversationId) {
        drainOwners.remove(conversationId);
        BatchKey nextKey;
        long delay;
        synchronized (pending) {
            Map.Entry<BatchKey, PendingBatch> next = pending.entrySet().stream()
                    .filter(entry -> conversationId.equals(entry.getKey().conversationId()))
                    .min(Comparator.comparingLong(entry -> entry.getValue().sequence))
                    .orElse(null);
            if (next == null) {
                return;
            }
            nextKey = next.getKey();
            delay = Math.max(0, next.getValue().readyAtMillis - System.currentTimeMillis());
        }
        DEBOUNCE_SCHEDULER.schedule(() -> drain(nextKey), delay, TimeUnit.MILLISECONDS);
    }

    /** Start a fresh lead turn carrying the merged results; its reply reaches the user. */
    private void wakeLead(AgentTeamEntity team, BatchKey key,
                          String message, List<AnnounceItem> items) {
        String leadConversationId = key.conversationId();
            List<String> taskIds = items.stream().map(item -> String.valueOf(item.taskId())).toList();
            Map<String, Object> startPayload = new HashMap<>();
            startPayload.put("teamId", String.valueOf(team.getId()));
            startPayload.put("tasks", items.size());
            if (taskIds.size() == 1) {
                startPayload.put("taskId", taskIds.get(0));
            } else {
                startPayload.put("taskIds", taskIds);
            }
            if (key.runId() != null) {
                startPayload.put("runId", String.valueOf(key.runId()));
            }
            streamTracker.broadcastObject(leadConversationId, "team_announce_start",
                    startPayload);
            // Persist the announce turn: message persistence is the caller's
            // contract, and without it the lead's synthesized reply would
            // vanish from the conversation history on the next reload.
            // Role stays "user" (the agent context pipeline resolves the
            // current turn's input from the last user row); the metadata type
            // marks it as an internal orchestration note so the chat UI can
            // render a compact system strip instead of a user bubble.
            conversationService.saveMessage(leadConversationId, "user", message, null, "completed",
                    0, 0, null, null,
                    announceMetadata("team_announce", key, taskIds));
            AgentService.ChatResult result = agentService.chatWithUsage(
                    team.getLeadAgentId(), message, leadConversationId);
            String reply = result == null ? null : result.content();
            if (reply != null && !reply.isBlank()) {
                conversationService.saveMessage(leadConversationId, "assistant", reply, null, "completed",
                        0, 0, null, null,
                        announceMetadata("team_announce_reply", key, taskIds));
            }
            Map<String, Object> replyPayload = new HashMap<>(startPayload);
            replyPayload.put("content", reply == null ? "" : reply);
            streamTracker.broadcastObject(leadConversationId, "team_announce_reply", replyPayload);
        log.info("Team {} lead woken with {} task result(s)", team.getId(), items.size());
    }

    private String announceMetadata(String type, BatchKey key, List<String> taskIds) {
        JSONObject metadata = new JSONObject()
                .set("type", type)
                .set("taskCount", taskIds.size());
        if (taskIds.size() == 1) {
            metadata.set("taskId", taskIds.get(0));
        } else {
            metadata.set("taskIds", taskIds);
        }
        if (key.runId() != null) {
            metadata.set("runId", String.valueOf(key.runId()));
        }
        return metadata.toString();
    }

    /** Merged announcement text; single- and multi-result variants. */
    static String buildAnnouncement(List<AnnounceItem> items) {
        StringBuilder sb = new StringBuilder(512);
        long failed = items.stream().filter(i -> TeamTaskStatus.FAILED.equals(i.status())).count();
        if (items.size() == 1) {
            sb.append("[System Message] A delegated team task has settled.\n");
        } else {
            sb.append("[System Message] ").append(items.size())
                    .append(" delegated team tasks have settled");
            if (failed > 0) {
                sb.append(" (").append(failed).append(" failed)");
            }
            sb.append(".\n");
        }
        for (AnnounceItem item : items) {
            sb.append("\n--- Task #").append(item.taskNumber())
                    .append(" \"").append(item.subject()).append("\" — ")
                    .append(item.status())
                    .append(" (member: ").append(item.memberName()).append(") ---\n");
            if (!item.detail().isBlank()) {
                sb.append(item.detail()).append('\n');
            }
        }
        sb.append("""

                Review these results against the original request, then reply to the user with ONE synthesized answer. \
                For failed tasks, fix the missing input and re-dispatch with team_tasks(action="retry", taskId=...), or cancel them. \
                Tasks in in_review await human approval — mention that instead of treating them as done.""");
        return sb.toString();
    }

    private String agentName(Long agentId) {
        if (agentId == null) {
            return "-";
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        return agent != null && agent.getName() != null ? agent.getName() : String.valueOf(agentId);
    }
}
