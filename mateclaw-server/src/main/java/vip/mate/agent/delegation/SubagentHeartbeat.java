package vip.mate.agent.delegation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Periodic watchdog that flips a sub-agent's status to {@code stale} when its
 * child stream stops making observable progress.
 *
 * <p>Progress is probed via {@link ChatStreamTracker#getRunningToolName} and
 * {@link ChatStreamTracker#getCurrentPhase}. When neither has changed across
 * the configured number of cycles, the record is marked stale and a
 * {@code subagent_stale} event is broadcast on the parent conversation so the
 * UI can surface the issue. Cycle count uses two separate thresholds — one
 * for idle children and one for children mid-tool — because legitimately slow
 * tools (large file scans, slow LLM calls) need a longer window than an idle
 * model that has simply gone quiet.
 *
 * <p>The runtime tool name + phase combination is a deliberately coarse
 * progress signal: it does not require introspecting LLM token deltas, which
 * keeps the watchdog cheap and avoids racing with the streaming hot path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubagentHeartbeat {

    private final SubagentRegistry registry;
    private final SubagentHeartbeatConfig cfg;
    private final ChatStreamTracker streamTracker;

    /**
     * Scheduled tick. Defaults to every 30 s; controlled by
     * {@code mateclaw.delegation.heartbeat.intervalSec}.
     */
    @Scheduled(fixedRateString = "#{@subagentHeartbeatConfig.intervalSec * 1000L}")
    public void check() {
        for (var rec : registry.allActive()) {
            if (!"running".equals(rec.status().get())) {
                continue;
            }
            evaluate(rec);
        }
    }

    /**
     * Visible for testing — apply one heartbeat tick to a single record so
     * tests can drive the watchdog deterministically without scheduling.
     */
    void evaluate(SubagentRegistry.SubagentRecord rec) {
        // Probe child progress. We use (currentTool, currentPhase) as the
        // monotonic-progress signal: any change in either implies the child
        // advanced at least one observable step. We deliberately do NOT
        // depend on a private apiCallCount field — the RunState does not
        // expose one, and counting deltas across the streaming hot path
        // would race with token emission. Tool/phase ticks are atomic
        // volatile writes from the streaming layer, so reading them here
        // is cheap and correct.
        String currentTool = streamTracker.getRunningToolName(rec.childConversationId());
        String currentPhase = streamTracker.getCurrentPhase(rec.childConversationId());
        int phaseHash = currentPhase != null ? currentPhase.hashCode() : 0;

        boolean toolChanged = !Objects.equals(currentTool, rec.lastSeenTool().get());
        boolean phaseChanged = phaseHash != rec.lastSeenIter().get();

        if (toolChanged || phaseChanged) {
            rec.lastSeenTool().set(currentTool);
            rec.lastSeenIter().set(phaseHash);
            rec.staleCount().set(0);
            return;
        }

        int sc = rec.staleCount().incrementAndGet();
        int limit = (currentTool != null && !currentTool.isEmpty())
                ? cfg.getStaleCyclesInTool()
                : cfg.getStaleCyclesIdle();

        if (sc >= limit) {
            // Atomic transition: only the first thread to flip running -> stale
            // emits the event. Subsequent ticks fall through the running guard
            // in check().
            if (rec.status().compareAndSet("running", "stale")) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("subagentId", rec.subagentId());
                payload.put("parentSubagentId", rec.parentSubagentId());
                payload.put("depth", rec.depth());
                payload.put("cycles", sc);
                payload.put("lastTool", currentTool != null ? currentTool : "");
                payload.put("elapsedMs", System.currentTimeMillis() - rec.startedAt());
                // Broadcast to the root (human-facing) conversation so the event
                // reaches the stream the user is watching at any tree depth.
                String target = rec.rootConversationId() != null
                        ? rec.rootConversationId() : rec.parentConversationId();
                streamTracker.broadcastObject(target, "subagent_stale", payload);
                log.info("[SubagentHeartbeat] subagent {} marked stale after {} idle cycles (limit={})",
                        rec.subagentId(), sc, limit);
            }
        }
    }
}
