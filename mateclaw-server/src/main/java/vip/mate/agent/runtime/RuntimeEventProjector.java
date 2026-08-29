package vip.mate.agent.runtime;

import vip.mate.agent.AgentService;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Projects normalized runtime events onto the existing chat stream vocabulary. */
public final class RuntimeEventProjector {
    private RuntimeEventProjector() {}

    public static AgentService.StreamDelta project(RuntimeEvent event) {
        if (event == null) return AgentService.StreamDelta.empty();
        Map<String, Object> data = new LinkedHashMap<>(event.data());
        data.putIfAbsent("runtimeSessionId", event.sessionId());
        data.putIfAbsent("runtimeSequence", event.sequence());
        return switch (event.type()) {
            case RUNTIME_READY -> AgentService.StreamDelta.event("phase",
                    with(data, "phase", "runtime_ready"));
            case ASSISTANT_DELTA -> new AgentService.StreamDelta(
                    text(event, data), null, null, null, false, false, null);
            case THINKING_DELTA -> new AgentService.StreamDelta(
                    null, text(event, data), null, null, false, false, null);
            case TOOL_STARTED -> AgentService.StreamDelta.event("tool_call_started",
                    rename(data, "toolName", "name", "callId", "toolCallId"));
            case TOOL_APPROVAL_REQUIRED -> AgentService.StreamDelta.event("tool_approval_requested",
                    rename(data, "requestId", "pendingId", "toolName", "toolName"));
            case TOOL_FINISHED -> AgentService.StreamDelta.event("tool_call_completed",
                    rename(data, "callId", "toolCallId", "toolName", "toolName"));
            case SUBAGENT_STARTED -> AgentService.StreamDelta.event("subagent_start", data);
            case SUBAGENT_FINISHED -> AgentService.StreamDelta.event("subagent_complete", data);
            case CONTEXT_USAGE -> AgentService.StreamDelta.event("_usage_final", data);
            case COMPLETED -> AgentService.StreamDelta.event("done", data);
            case FAILED -> AgentService.StreamDelta.event("error", data);
            case CANCELLED -> AgentService.StreamDelta.event("cancelled", data);
        };
    }

    private static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }

    private static String text(RuntimeEvent event, Map<String, Object> data) {
        Object delta = data.get("delta");
        return delta != null ? String.valueOf(delta) : event.text() == null ? "" : event.text();
    }

    private static Map<String, Object> rename(Map<String, Object> source, String from, String to,
                                               String secondFrom, String secondTo) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        copyIfPresent(result, from, to);
        copyIfPresent(result, secondFrom, secondTo);
        return result;
    }

    private static void copyIfPresent(Map<String, Object> data, String from, String to) {
        if (!data.containsKey(to) && data.containsKey(from)) data.put(to, data.get(from));
    }
}
