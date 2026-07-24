package vip.mate.team.service;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Delivers settled task results back to the team lead. Results arriving close
 * together are debounced per lead conversation and merged into ONE combined
 * announcement, so parallel members finishing near-simultaneously wake the
 * lead once instead of once per task.
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
    private final AgentService agentService;
    private final AgentMapper agentMapper;
    private final RunningConversationRegistry runningConversations;
    private final ChatStreamTracker streamTracker;

    /** Pending items per lead conversation; the first item arms the drain timer. */
    private final Map<String, List<AnnounceItem>> pending = new ConcurrentHashMap<>();

    record AnnounceItem(Long teamId, Integer taskNumber, String subject, String status,
                        String memberName, String detail) {
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
        AnnounceItem item = new AnnounceItem(task.getTeamId(), task.getTaskNumber(),
                task.getSubject(), task.getStatus(),
                agentName(task.getAssigneeAgentId()),
                detail == null ? "" : detail);

        String key = task.getLeadConversationId();
        List<AnnounceItem> drainNow = null;
        synchronized (pending) {
            List<AnnounceItem> queue = pending.computeIfAbsent(key, k -> new ArrayList<>());
            queue.add(item);
            if (queue.size() >= MAX_BATCH) {
                drainNow = pending.remove(key);
            } else if (queue.size() == 1) {
                DEBOUNCE_SCHEDULER.schedule(() -> drain(key), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
        if (drainNow != null) {
            deliver(key, drainNow);
        }
    }

    /** Timer callback: take whatever accumulated and deliver it. */
    void drain(String leadConversationId) {
        List<AnnounceItem> items;
        synchronized (pending) {
            items = pending.remove(leadConversationId);
        }
        if (items != null && !items.isEmpty()) {
            deliver(leadConversationId, items);
        }
    }

    void deliver(String leadConversationId, List<AnnounceItem> items) {
        deliver(leadConversationId, items, 0);
    }

    private void deliver(String leadConversationId, List<AnnounceItem> items, int busyRetries) {
        Long teamId = items.get(0).teamId();
        AgentTeamEntity team = teamService.getTeam(teamId);
        if (team == null) {
            log.warn("Announce dropped: team {} vanished", teamId);
            return;
        }
        if (runningConversations.isActive(leadConversationId) && busyRetries < MAX_BUSY_RETRIES) {
            // Lead is mid-turn. Late tasks settling meanwhile join this batch
            // via the pending map, so re-queue and re-arm instead of injecting
            // into the running turn (which can drop the message on turn end).
            List<AnnounceItem> merged = items;
            synchronized (pending) {
                List<AnnounceItem> late = pending.remove(leadConversationId);
                if (late != null) {
                    merged = new ArrayList<>(items);
                    merged.addAll(late);
                }
            }
            List<AnnounceItem> retryItems = merged;
            DEBOUNCE_SCHEDULER.schedule(() -> deliver(leadConversationId, retryItems, busyRetries + 1),
                    BUSY_RETRY_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }
        String message = buildAnnouncement(items);
        ANNOUNCE_EXECUTOR.submit(() -> wakeLead(team, leadConversationId, message, items.size()));
    }

    /** Start a fresh lead turn carrying the merged results; its reply reaches the user. */
    private void wakeLead(AgentTeamEntity team, String leadConversationId,
                          String message, int taskCount) {
        try {
            streamTracker.broadcastObject(leadConversationId, "team_announce_start",
                    Map.of("teamId", String.valueOf(team.getId()), "tasks", taskCount));
            AgentService.ChatResult result = agentService.chatWithUsage(
                    team.getLeadAgentId(), message, leadConversationId);
            streamTracker.broadcastObject(leadConversationId, "team_announce_reply",
                    Map.of("teamId", String.valueOf(team.getId()),
                            "content", result == null || result.content() == null
                                    ? "" : result.content()));
            log.info("Team {} lead woken with {} task result(s)", team.getId(), taskCount);
        } catch (Exception e) {
            log.warn("Team {} lead wake-up failed: {}", team.getId(), e.getMessage());
        }
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
