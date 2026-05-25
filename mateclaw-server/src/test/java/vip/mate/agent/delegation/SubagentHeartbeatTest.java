package vip.mate.agent.delegation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubagentHeartbeatTest {

    private SubagentRegistry registry;
    private SubagentHeartbeatConfig cfg;
    private ChatStreamTracker streamTracker;
    private SubagentHeartbeat heartbeat;

    @BeforeEach
    void setUp() {
        registry = new SubagentRegistry();
        cfg = new SubagentHeartbeatConfig();
        // Tight thresholds keep tests fast.
        cfg.setIntervalSec(30);
        cfg.setStaleCyclesIdle(3);
        cfg.setStaleCyclesInTool(5);
        streamTracker = mock(ChatStreamTracker.class);
        heartbeat = new SubagentHeartbeat(registry, cfg, streamTracker);
    }

    @Test
    @DisplayName("idle child flips to stale exactly at the configured idle threshold")
    void idleChildBecomesStale() {
        String id = registry.register("parent-1", "child-1", 1L, "g", null);
        // No tool, no phase change across cycles → idle path.
        when(streamTracker.getRunningToolName("child-1")).thenReturn(null);
        when(streamTracker.getCurrentPhase("child-1")).thenReturn("thinking");

        var rec = registry.get(id).orElseThrow();

        // Cycle 1: first observation seeds lastSeen, no stale increment.
        heartbeat.evaluate(rec);
        assertThat(rec.staleCount().get()).isEqualTo(0);
        assertThat(rec.status().get()).isEqualTo("running");

        // Cycles 2 and 3: no change → counter increments to 1, then 2.
        heartbeat.evaluate(rec);
        heartbeat.evaluate(rec);
        assertThat(rec.staleCount().get()).isEqualTo(2);
        assertThat(rec.status().get()).isEqualTo("running");
        verify(streamTracker, never()).broadcastObject(anyString(), eq("subagent_stale"), any());

        // Cycle 4: counter hits 3 → stale and event broadcast.
        heartbeat.evaluate(rec);
        assertThat(rec.status().get()).isEqualTo("stale");
        verify(streamTracker, times(1)).broadcastObject(eq("parent-1"), eq("subagent_stale"), any());
    }

    @Test
    @DisplayName("in-tool child uses the longer in-tool threshold before stale fires")
    void inToolChildUsesLongerThreshold() {
        String id = registry.register("parent-2", "child-2", 1L, "g", null);
        when(streamTracker.getRunningToolName("child-2")).thenReturn("read_file");
        when(streamTracker.getCurrentPhase("child-2")).thenReturn("action");

        var rec = registry.get(id).orElseThrow();

        // Cycle 1 seeds lastSeen (no increment). Each subsequent no-change
        // tick increments staleCount by 1; staleCyclesInTool=5 fires when
        // the counter HITS 5. So we need 1 seed + 5 increment ticks.
        heartbeat.evaluate(rec); // seed
        for (int i = 0; i < 5; i++) {
            heartbeat.evaluate(rec);
        }
        assertThat(rec.status().get()).isEqualTo("stale");
        verify(streamTracker, times(1)).broadcastObject(eq("parent-2"), eq("subagent_stale"), any());
    }

    @Test
    @DisplayName("phase or tool change resets stale counter")
    void progressResetsCounter() {
        String id = registry.register("parent-3", "child-3", 1L, "g", null);
        var rec = registry.get(id).orElseThrow();

        when(streamTracker.getRunningToolName("child-3")).thenReturn(null);
        when(streamTracker.getCurrentPhase("child-3")).thenReturn("thinking");
        heartbeat.evaluate(rec); // seed
        heartbeat.evaluate(rec); // +1
        heartbeat.evaluate(rec); // +2
        assertThat(rec.staleCount().get()).isEqualTo(2);

        // Phase change → counter resets.
        when(streamTracker.getCurrentPhase("child-3")).thenReturn("action");
        heartbeat.evaluate(rec);
        assertThat(rec.staleCount().get()).isEqualTo(0);

        // Tool change while staying in same phase also resets.
        when(streamTracker.getRunningToolName("child-3")).thenReturn("read_file");
        heartbeat.evaluate(rec); // (tool changed) → reset
        assertThat(rec.staleCount().get()).isEqualTo(0);
    }

    @Test
    @DisplayName("heartbeat skips non-running records")
    void skipsNonRunning() {
        String id = registry.register("parent-4", "child-4", 1L, "g", null);
        registry.get(id).orElseThrow().status().set("interrupted");

        heartbeat.check();

        verify(streamTracker, never()).getRunningToolName(anyString());
        verify(streamTracker, never()).broadcastObject(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("subagent_stale payload carries id, cycles, lastTool, elapsedMs")
    void stalePayloadShape() {
        cfg.setStaleCyclesIdle(2);
        String id = registry.register("parent-5", "child-5", 1L, "g", null);
        when(streamTracker.getRunningToolName("child-5")).thenReturn(null);
        when(streamTracker.getCurrentPhase("child-5")).thenReturn("thinking");

        var rec = registry.get(id).orElseThrow();
        heartbeat.evaluate(rec); // seed
        heartbeat.evaluate(rec); // +1
        heartbeat.evaluate(rec); // +2 → stale

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(streamTracker).broadcastObject(eq("parent-5"), eq("subagent_stale"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captor.getValue();
        assertThat(payload).containsKeys("subagentId", "cycles", "lastTool", "elapsedMs");
        assertThat(payload.get("subagentId")).isEqualTo(id);
    }
}
