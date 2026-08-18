package vip.mate.agent.graph.state;

import vip.mate.agent.GraphEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authoritative per-run tool completion receipts used by the action completion gate. */
public final class ActionExecutionLedger {

    private static final int MAX_RESULT_SUMMARY_CHARS = 512;
    private static final Set<String> NON_SUBSTANTIVE_TOOLS = Set.of(
            "load_skill", "enable_tool", "tool_search", "tool_describe",
            "progress_update", "get_progress");

    public enum Status { SUCCEEDED, FAILED }

    public record Receipt(String toolCallId, String toolName, Status status,
                          String resultSummary, long completedAt) {
        public boolean substantive() {
            return toolName != null && !NON_SUBSTANTIVE_TOOLS.contains(toolName);
        }
    }

    private static final ActionExecutionLedger EMPTY = new ActionExecutionLedger(Map.of());

    private final Map<String, Receipt> receipts;

    private ActionExecutionLedger(Map<String, Receipt> receipts) {
        this.receipts = Map.copyOf(receipts);
    }

    public static ActionExecutionLedger empty() {
        return EMPTY;
    }

    public static ActionExecutionLedger fromEvents(List<GraphEventPublisher.GraphEvent> events) {
        if (events == null || events.isEmpty()) return empty();
        Map<String, Receipt> receipts = new LinkedHashMap<>();
        int legacyIndex = 0;
        for (GraphEventPublisher.GraphEvent event : events) {
            if (event == null || !GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type())) continue;
            Map<String, Object> data = event.data();
            String id = String.valueOf(data.getOrDefault("toolCallId", ""));
            String name = String.valueOf(data.getOrDefault("toolName", ""));
            if (id.isBlank()) id = "legacy-" + name + "-" + legacyIndex++;
            boolean success = Boolean.parseBoolean(String.valueOf(data.getOrDefault("success", false)));
            String result = String.valueOf(data.getOrDefault("result", ""));
            if (result.length() > MAX_RESULT_SUMMARY_CHARS) {
                result = result.substring(0, MAX_RESULT_SUMMARY_CHARS) + "...";
            }
            receipts.put(id, new Receipt(id, name,
                    success ? Status.SUCCEEDED : Status.FAILED, result, event.timestamp()));
        }
        return receipts.isEmpty() ? empty() : new ActionExecutionLedger(receipts);
    }

    public Map<String, Receipt> receipts() {
        return receipts;
    }

    public boolean hasSubstantiveAttempt() {
        return receipts.values().stream().anyMatch(Receipt::substantive);
    }

    public boolean hasSuccessfulSubstantiveCall() {
        return receipts.values().stream()
                .anyMatch(receipt -> receipt.substantive() && receipt.status() == Status.SUCCEEDED);
    }

    public boolean hasSuccessfulTool(String toolName) {
        return receipts.values().stream().anyMatch(receipt ->
                receipt.status() == Status.SUCCEEDED && receipt.toolName().equals(toolName));
    }

    public ActionExecutionLedger merge(ActionExecutionLedger other) {
        if (other == null || other.receipts.isEmpty()) return this;
        if (receipts.isEmpty()) return other;
        Map<String, Receipt> merged = new LinkedHashMap<>(receipts);
        merged.putAll(other.receipts);
        return new ActionExecutionLedger(merged);
    }
}
