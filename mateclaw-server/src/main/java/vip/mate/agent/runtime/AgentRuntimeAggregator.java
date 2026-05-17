package vip.mate.agent.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.channel.web.ChatStreamTracker.RunSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Joins the live in-memory views ({@link ChatStreamTracker}, {@link SubagentRegistry})
 * with agent metadata so the admin Live view can render one card per
 * working agent without making the frontend traverse three independent
 * services.
 *
 * <p>The "stuck" verdict is computed here rather than persisted on
 * {@link RunSnapshot} so the thresholds can be tuned at runtime without
 * touching every producer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntimeAggregator {

    /**
     * Idle threshold: a run with no events for this long while NOT inside a
     * tool call is treated as wedged. Aligns with the upstream cli reference
     * (5 × 30s heartbeat cycles) so pre-token latency does not false-alarm.
     */
    private static final long STUCK_IDLE_MS = 150_000L;

    /**
     * In-tool threshold: a run with a {@code runningToolName} but no events
     * for this long. Looser than idle because slow tool calls (LLM-backed
     * tools, long-running shell commands) routinely sit silent for minutes.
     */
    private static final long STUCK_TOOL_MS = 600_000L;

    /**
     * Hard cap regardless of activity. A run older than this is suspicious
     * even when the bytes are flowing: the user has likely walked away and
     * the model is in a feedback loop.
     */
    private static final long STUCK_HARD_CAP_MS = 1_800_000L;

    private final ChatStreamTracker streamTracker;
    private final SubagentRegistry subagentRegistry;
    private final AgentService agentService;

    /** One in-flight run, enriched with agent label and stuck verdict. */
    public record RunCard(
            String conversationId,
            Long agentId,
            String agentName,
            String agentIcon,
            String username,
            String currentPhase,
            String runningToolName,
            String waitingReason,
            boolean done,
            boolean stopRequested,
            boolean firstTokenReceived,
            int subscriberCount,
            int queueLen,
            long ageMs,
            long msSinceLastEvent,
            String stuckReason,
            boolean orphan,
            int subagentCount
    ) {}

    /** One sub-agent under a parent run, ready for tree rendering. */
    public record SubagentCard(
            String subagentId,
            String parentConversationId,
            String childConversationId,
            Long agentId,
            String agentName,
            String agentIcon,
            String goal,
            String status,
            String currentPhase,
            String lastTool,
            int toolCount,
            long ageMs
    ) {}

    /** Top-level summary used to drive the breathing sidebar dot. */
    public record Summary(
            int running,
            int stuck,
            int orphan,
            int queued,
            int subagentsActive
    ) {}

    /** Full snapshot envelope returned to the admin UI. */
    public record RuntimeSnapshot(
            Summary summary,
            List<RunCard> runs,
            List<SubagentCard> subagents,
            long timestamp
    ) {}

    public RuntimeSnapshot snapshot() {
        List<RunSnapshot> rawRuns = streamTracker.getAllSnapshot();
        Set<Long> agentIds = rawRuns.stream()
                .map(RunSnapshot::agentId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        for (var rec : subagentRegistry.allActive()) {
            if (rec.agentId() != null) agentIds.add(rec.agentId());
        }
        Map<Long, AgentEntity> agentInfo = resolveAgents(agentIds);

        Map<String, Long> subagentCountByParent = new HashMap<>();
        for (var rec : subagentRegistry.allActive()) {
            String parent = rec.parentConversationId();
            if (parent != null) {
                subagentCountByParent.merge(parent, 1L, Long::sum);
            }
        }

        List<RunCard> cards = new ArrayList<>(rawRuns.size());
        int stuckCount = 0;
        int orphanCount = 0;
        int queuedTotal = 0;
        int runningCount = 0;
        for (RunSnapshot s : rawRuns) {
            if (s.done()) continue;
            runningCount++;
            String stuckReason = computeStuckReason(s);
            boolean orphan = s.subscriberCount() == 0;
            if (stuckReason != null) stuckCount++;
            if (orphan) orphanCount++;
            queuedTotal += s.queueLen();
            int subCount = subagentCountByParent.getOrDefault(s.conversationId(), 0L).intValue();
            AgentEntity ag = s.agentId() == null ? null : agentInfo.get(s.agentId());
            cards.add(new RunCard(
                    s.conversationId(),
                    s.agentId(),
                    ag == null ? null : ag.getName(),
                    ag == null ? null : ag.getIcon(),
                    s.username(),
                    s.currentPhase(),
                    s.runningToolName(),
                    s.waitingReason(),
                    s.done(),
                    s.stopRequested(),
                    s.firstTokenReceived(),
                    s.subscriberCount(),
                    s.queueLen(),
                    s.ageMs(),
                    s.msSinceLastEvent(),
                    stuckReason,
                    orphan,
                    subCount
            ));
        }
        // Sort: stuck first (loudest first), then orphan, then by lastEventAt asc
        cards.sort((a, b) -> {
            int aStuck = a.stuckReason() != null ? 1 : 0;
            int bStuck = b.stuckReason() != null ? 1 : 0;
            if (aStuck != bStuck) return bStuck - aStuck;
            int aOrph = a.orphan() ? 1 : 0;
            int bOrph = b.orphan() ? 1 : 0;
            if (aOrph != bOrph) return bOrph - aOrph;
            return Long.compare(b.msSinceLastEvent(), a.msSinceLastEvent());
        });

        List<SubagentCard> subCards = subagentRegistry.allActive().stream()
                .map(rec -> {
                    long now = System.currentTimeMillis();
                    AgentEntity ag = rec.agentId() == null ? null : agentInfo.get(rec.agentId());
                    return new SubagentCard(
                            rec.subagentId(),
                            rec.parentConversationId(),
                            rec.childConversationId(),
                            rec.agentId(),
                            ag == null ? null : ag.getName(),
                            ag == null ? null : ag.getIcon(),
                            rec.goal(),
                            rec.status() != null ? rec.status().get() : null,
                            rec.currentPhase() != null ? rec.currentPhase().get() : null,
                            rec.lastTool() != null ? rec.lastTool().get() : null,
                            rec.toolCount() != null ? rec.toolCount().get() : 0,
                            now - rec.startedAt()
                    );
                })
                .toList();

        Summary summary = new Summary(
                runningCount,
                stuckCount,
                orphanCount,
                queuedTotal,
                subCards.size()
        );

        return new RuntimeSnapshot(summary, cards, subCards, System.currentTimeMillis());
    }

    /**
     * Returns null when the run looks healthy. The returned tag is a stable
     * machine-readable code (not a translated label) so the frontend can
     * decide presentation: {@code idle_silent} / {@code tool_silent} /
     * {@code hard_cap}.
     */
    private String computeStuckReason(RunSnapshot s) {
        if (s.ageMs() > STUCK_HARD_CAP_MS) return "hard_cap";
        boolean inTool = s.runningToolName() != null && !s.runningToolName().isBlank();
        long since = s.msSinceLastEvent();
        if (inTool && since > STUCK_TOOL_MS) return "tool_silent";
        if (!inTool && since > STUCK_IDLE_MS) return "idle_silent";
        return null;
    }

    private Map<Long, AgentEntity> resolveAgents(Set<Long> ids) {
        Map<Long, AgentEntity> out = new LinkedHashMap<>();
        for (Long id : ids) {
            if (id == null) continue;
            try {
                AgentEntity a = agentService.getAgent(id);
                if (a != null) out.put(id, a);
            } catch (Exception e) {
                log.debug("agent lookup failed for id={}: {}", id, e.getMessage());
            }
        }
        return out;
    }
}
