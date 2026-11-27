package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.HashMap;
import java.util.Map;

/** Admits lifecycle notifications only for descendants of the current SDK root. */
final class DshSubagentEvents {
    private final String rootSessionId;
    private final Map<String, String> parents = new HashMap<>();
    private final Map<String, Integer> depths = new HashMap<>();

    DshSubagentEvents(String rootSessionId) {
        this.rootSessionId = rootSessionId;
    }

    RuntimeEvent accept(String conversationId, long sequence, JsonNode frame) {
        String method = frame.path("method").asText();
        if (!"subagent.started".equals(method) && !"subagent.finished".equals(method)) return null;
        JsonNode params = frame.path("params");
        String parent = params.path("parentSessionId").asText("");
        String child = params.path("childSessionId").asText("");
        if (child.isBlank() || child.equals(rootSessionId)) return null;
        if ("subagent.started".equals(method)) {
            if ((!rootSessionId.equals(parent) && !parents.containsKey(parent)) || parents.containsKey(child)) return null;
            parents.put(child, parent);
            int depth = depths.getOrDefault(parent, 0) + 1;
            depths.put(child, depth);
            return RuntimeEvent.of(conversationId, sequence, RuntimeEventType.SUBAGENT_STARTED, null,
                    Map.of("agentId", child, "childSessionId", child, "parentSessionId", parent,
                            "subagentId", child, "parentSubagentId", rootSessionId.equals(parent) ? "" : parent,
                            "depth", depth, "childAgentName", "DSH"));
        }
        if (!parent.equals(parents.get(child))) return null;
        parents.remove(child);
        depths.remove(child);
        StringBuilder preview = new StringBuilder();
        for (JsonNode block : params.path("lastAssistantMessage")) {
            if ("text".equals(block.path("type").asText())) preview.append(block.path("text").asText(""));
            if (preview.length() >= 4096) break;
        }
        if (preview.length() > 4096) {
            int end = Character.isHighSurrogate(preview.charAt(4095)) ? 4095 : 4096;
            preview.setLength(end);
            preview.append("\n[truncated]");
        }
        return RuntimeEvent.of(conversationId, sequence, RuntimeEventType.SUBAGENT_FINISHED, null,
                Map.of("agentId", child, "childSessionId", child, "parentSessionId", parent,
                        "subagentId", child, "success", "ok".equals(params.path("status").asText()),
                        "resultPreview", preview.toString(),
                        "status", params.path("status").asText("error"),
                        "stopReason", params.path("stopReason").asText("unknown")));
    }
}
