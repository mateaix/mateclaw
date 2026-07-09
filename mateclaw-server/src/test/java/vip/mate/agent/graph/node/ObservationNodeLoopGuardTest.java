package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.config.GraphObservationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * ObservationNode wiring for the tool-call loop guard and the one-shot
 * post-mutation verification reminder: warnings land in the observation text,
 * a halt lands in the ERROR slot (routing to graceful wrap-up), and the
 * reminder fires exactly once per run.
 */
class ObservationNodeLoopGuardTest {

    private ObservationNode node() {
        return new ObservationNode(new ObservationProcessor(new GraphObservationProperties()));
    }

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    private static ToolResponseMessage.ToolResponse result(String id, String name, String data) {
        return new ToolResponseMessage.ToolResponse(id, name, data);
    }

    private OverAllState state(Map<String, Object> loopStats, Boolean reminderInjected,
                               List<AssistantMessage.ToolCall> calls,
                               List<ToolResponseMessage.ToolResponse> results) {
        Map<String, Object> m = new HashMap<>();
        m.put(CURRENT_ITERATION, 1);
        m.put(MAX_ITERATIONS, 25);
        m.put(OBSERVATION_HISTORY, new ArrayList<String>());
        m.put(TOOL_CALLS, calls);
        m.put(TOOL_RESULTS, results);
        m.put(TOOL_CALL_COUNT, 0);
        if (loopStats != null) {
            m.put(TOOL_LOOP_STATS, loopStats);
        }
        if (reminderInjected != null) {
            m.put(MUTATION_REMINDER_INJECTED, reminderInjected);
        }
        return new OverAllState(m);
    }

    @SuppressWarnings("unchecked")
    private static String lastObservation(Map<String, Object> out) {
        List<String> history = (List<String>) out.get(OBSERVATION_HISTORY);
        return history.get(history.size() - 1);
    }

    @Test
    @DisplayName("同参二次失败：警告注入观察文本，计数器写回状态")
    void warnInjectedIntoObservation() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(call("c1", "web_search", "{\"q\":\"x\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "web_search", "Error: rate limited"));

        Map<String, Object> round1 = node().apply(state(null, null, calls, results));
        assertFalse(lastObservation(round1).contains("循环警告"), "1st failure: no warning yet");
        Map<String, Object> stats = (Map<String, Object>) round1.get(TOOL_LOOP_STATS);
        assertNotNull(stats, "counters must be written back to state");

        Map<String, Object> round2 = node().apply(state(stats, null, calls, results));
        assertTrue(lastObservation(round2).contains("循环警告"), "2nd identical failure warns");
        assertNull(round2.get(ERROR), "warning must not set the error slot");
    }

    @Test
    @DisplayName("同参五次失败：置 ERROR 走优雅收尾路由")
    void haltSetsError() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(call("c1", "web_search", "{\"q\":\"x\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "web_search", "Error: rate limited"));

        Map<String, Object> stats = null;
        Map<String, Object> out = null;
        for (int i = 0; i < 5; i++) {
            out = node().apply(state(stats, null, calls, results));
            stats = (Map<String, Object>) out.get(TOOL_LOOP_STATS);
        }
        assertNotNull(out.get(ERROR), "5th identical failure must halt via the ERROR slot");
        assertTrue(((String) out.get(ERROR)).contains("web_search"));
    }

    @Test
    @DisplayName("成功写文件：验证提醒注入一次，后续轮不重复")
    void verificationReminderFiresOnce() throws Exception {
        List<AssistantMessage.ToolCall> calls =
                List.of(call("c1", "write_file", "{\"filePath\":\"a.txt\",\"content\":\"x\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "write_file", "{\"filePath\":\"a.txt\",\"bytesWritten\":1}"));

        Map<String, Object> round1 = node().apply(state(null, null, calls, results));
        assertTrue(lastObservation(round1).contains("验证提醒"), "first successful mutation reminds");
        assertEquals(Boolean.TRUE, round1.get(MUTATION_REMINDER_INJECTED));

        Map<String, Object> round2 = node().apply(state(
                (Map<String, Object>) round1.get(TOOL_LOOP_STATS), true, calls, results));
        assertFalse(lastObservation(round2).contains("验证提醒"), "reminder is one-shot per run");
    }

    @Test
    @DisplayName("写文件失败不触发验证提醒")
    void failedMutationDoesNotRemind() throws Exception {
        List<AssistantMessage.ToolCall> calls =
                List.of(call("c1", "write_file", "{\"filePath\":\"a.txt\",\"content\":\"x\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "write_file", "Tool execution failed: disk full"));

        Map<String, Object> out = node().apply(state(null, null, calls, results));
        assertFalse(lastObservation(out).contains("验证提醒"));
        assertNull(out.get(MUTATION_REMINDER_INJECTED));
    }

    @Test
    @DisplayName("只读工具正常成功：无警告、无提醒、无 ERROR")
    void healthyRoundIsUntouched() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(call("c1", "read_file", "{\"path\":\"a\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "read_file", "file content"));

        Map<String, Object> out = node().apply(state(null, null, calls, results));
        String obs = lastObservation(out);
        assertFalse(obs.contains("循环"));
        assertFalse(obs.contains("验证提醒"));
        assertNull(out.get(ERROR));
    }

    // ==================== warning events (UI visibility) ====================

    @SuppressWarnings("unchecked")
    private static List<vip.mate.agent.GraphEventPublisher.GraphEvent> warningEvents(Map<String, Object> out) {
        var events = (List<vip.mate.agent.GraphEventPublisher.GraphEvent>) out.get(PENDING_EVENTS);
        if (events == null) return List.of();
        return events.stream()
                .filter(e -> vip.mate.agent.GraphEventPublisher.EVENT_WARNING.equals(e.type()))
                .toList();
    }

    @Test
    @DisplayName("循环警告轮：PENDING_EVENTS 携带 warning 事件（source=loop_guard）")
    void warningRoundEmitsWarningEvent() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(call("c1", "web_search", "{\"q\":\"x\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "web_search", "Error: rate limited"));

        Map<String, Object> round1 = node().apply(state(null, null, calls, results));
        assertTrue(warningEvents(round1).isEmpty(), "1st failure: no warning event");

        Map<String, Object> round2 = node().apply(state(
                (Map<String, Object>) round1.get(TOOL_LOOP_STATS), null, calls, results));
        var events = warningEvents(round2);
        assertEquals(1, events.size(), "2nd identical failure emits one warning event");
        assertEquals("loop_guard", events.get(0).data().get("source"));
        assertTrue(String.valueOf(events.get(0).data().get("message")).contains("循环警告"));
    }

    @Test
    @DisplayName("熔断轮：额外携带循环熔断 warning 事件")
    void haltRoundEmitsHaltWarningEvent() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(call("c1", "web_search", "{\"q\":\"x\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "web_search", "Error: rate limited"));

        Map<String, Object> stats = null;
        Map<String, Object> out = null;
        for (int i = 0; i < 5; i++) {
            out = node().apply(state(stats, null, calls, results));
            stats = (Map<String, Object>) out.get(TOOL_LOOP_STATS);
        }
        var events = warningEvents(out);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e ->
                String.valueOf(e.data().get("message")).contains("循环熔断")));
    }

    @Test
    @DisplayName("健康轮：PENDING_EVENTS 无 warning 事件")
    void healthyRoundEmitsNoWarningEvent() throws Exception {
        List<AssistantMessage.ToolCall> calls = List.of(call("c1", "read_file", "{\"path\":\"a\"}"));
        List<ToolResponseMessage.ToolResponse> results =
                List.of(result("c1", "read_file", "file content"));

        Map<String, Object> out = node().apply(state(null, null, calls, results));
        assertTrue(warningEvents(out).isEmpty());
    }
}
