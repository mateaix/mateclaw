package vip.mate.agent.runtime.dsh;


import com.fasterxml.jackson.databind.JsonNode;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Correlates one prompt's durable inbox receipt with its root-session activity. */
public final class DshSdkTurn {
    // Session vocabulary pinned to the reviewed SDK prereleases.
    private static final Set<String> KNOWN_EVENTS = Set.of(
            "agent-preset/selected", "agent/inbox/spliced", "approval/asked", "approval/decided",
            "approval/policy", "assistant/attempt", "assistant/message", "command/done",
            "command/run", "compaction/end", "compaction/prune", "compaction/start",
            "compaction/summary", "deliverables/presented", "developer/message", "feedback/message-delete",
            "feedback/message-put", "feedback/record", "goal/change", "hook/invoked",
            "hook/result", "image/offload", "llm/retry", "llm/retry-started",
            "job/registered", "job/settled", "working-directory/change",
            "model/selection", "permission/preset", "plan/mode", "request/context",
            "request/header", "sandbox/mode", "schedule/change", "session-log-deepseek/delivery-accepted",
            "session/end-seed", "session/title", "session/title-llm-request", "step/end",
            "step/start", "subagent/catalog", "subagent/descriptor", "subagent/model-selection-policy",
            "system/message", "team/member", "team/message/delivered", "team/message/queued",
            "team/task", "todo/write", "tool-workflow/agent-end", "tool-workflow/agent-start",
            "tool-workflow/run-end", "tool-workflow/run-start", "tool/call", "tool/ptc-dispatch",
            "tool/ptc-dispatch-start", "tool/result", "turn/end", "turn/start",
            "user/message", "web/deepseek-search-llm-request", "workspace/changes");
    private final String sessionId;
    private final String promptId;
    private final List<JsonNode> pending = new ArrayList<>();
    private final Set<Long> sequences = new HashSet<>();
    private int pendingBytes;
    private String messageId;
    private boolean received;
    private boolean complete;
    private String reason;
    private String answer = "";
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;

    public DshSdkTurn(String sessionId, String promptId) { this.sessionId = sessionId; this.promptId = promptId; }

    /** Returns newly admitted root events; caller projects tools and settled reasoning. */
    public List<JsonNode> accept(JsonNode frame) {
        if (complete) return List.of();
        if (frame.has("id") && promptId.equals(frame.path("id").asText())) {
            if (frame.has("error")) throw new IllegalStateException("DSH_PROMPT_REJECTED");
            messageId = frame.path("result").path("messageId").asText("");
            if (messageId.isBlank()) throw new IllegalStateException("DSH_PROMPT_RECEIPT_INVALID");
            List<JsonNode> events = new ArrayList<>();
            for (JsonNode notification : pending) events.addAll(admit(notification));
            pending.clear(); pendingBytes = 0;
            return events;
        }
        if (!frame.has("method")) return List.of();
        if (messageId == null) {
            int size = frame.toString().getBytes(StandardCharsets.UTF_8).length;
            if (pending.size() >= 4096 || (long) pendingBytes + size > 16 * 1024 * 1024)
                throw new IllegalStateException("DSH_NOTIFICATION_OVERFLOW");
            pending.add(frame); pendingBytes += size;
            return List.of();
        }
        return admit(frame);
    }

    private List<JsonNode> admit(JsonNode frame) {
        JsonNode params = frame.path("params");
        if (!sessionId.equals(params.path("sessionId").asText())) return List.of();
        String method = frame.path("method").asText();
        JsonNode event = params.path("event");
        if (!received) {
            if (!"session.event".equals(method) || !"agent/inbox/spliced".equals(event.path("type").asText())) return List.of();
            for (JsonNode inserted : event.path("data").path("inserted"))
                if (messageId.equals(inserted.path("id").asText())) received = true;
            if (!received) return List.of();
        }
        if ("session.status".equals(method) && "idle".equals(params.path("status").asText())) {
            complete = true;
            if (reason == null) reason = "missing-turn-end";
            return List.of();
        }
        if (!"session.event".equals(method)) return List.of();
        if (!event.path("seq").canConvertToLong() || !event.path("seq").isIntegralNumber())
            throw new IllegalStateException("DSH_EVENT_SEQUENCE_INVALID");
        if (!sequences.add(event.path("seq").asLong())) return List.of();
        String type = event.path("type").asText();
        if (!KNOWN_EVENTS.contains(type) && !event.path("ignorable").asBoolean(false))
            throw new IllegalStateException("DSH_UNKNOWN_REQUIRED_EVENT: " + type);
        JsonNode data = event.path("data");
        if ("assistant/message".equals(type)) {
            JsonNode content = data.path("message").path("content");
            if (!content.isArray()) throw new IllegalStateException("DSH_ASSISTANT_CONTENT_INVALID");
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                if (!block.path("type").isTextual()) throw new IllegalStateException("DSH_ASSISTANT_CONTENT_INVALID");
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
            }
            answer = text.toString();
            cacheReadTokens += Math.max(0, data.path("usage").path("cacheReadTokens").asLong());
            cacheWriteTokens += Math.max(0, data.path("usage").path("cacheWriteTokens").asLong());
            inputTokens += Math.max(0, data.path("usage").path("inputTokens").asLong())
                    + Math.max(0, data.path("usage").path("cacheReadTokens").asLong())
                    + Math.max(0, data.path("usage").path("cacheWriteTokens").asLong());
            outputTokens += Math.max(0, data.path("usage").path("outputTokens").asLong());
            if (data.path("interrupted").asBoolean(false)) reason = "interrupted";
        } else if ("turn/end".equals(type)) {
            String terminal = data.path("reason").path("kind").asText("unknown");
            if (!"interrupted".equals(reason)) reason = terminal;
        }
        return List.of(event);
    }

    public boolean complete() { return complete; }
    public RuntimeEventType terminalType() { return "completed".equals(reason) ? RuntimeEventType.COMPLETED : RuntimeEventType.FAILED; }
    public String reason() { return reason == null ? "missing-turn-end" : reason; }
    public String answer() { return answer; }
    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }
    public long cacheReadTokens() { return cacheReadTokens; }
    public long cacheWriteTokens() { return cacheWriteTokens; }
}
