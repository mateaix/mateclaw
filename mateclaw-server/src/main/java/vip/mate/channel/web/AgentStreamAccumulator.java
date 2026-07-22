package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.agent.AgentService;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式累积器 — 收集 StreamDelta 事件，持久化到 DB。
 * <p>
 * 维护两份数据：
 * <ul>
 *   <li>{@code toolCalls} — 兼容旧逻辑（执行面板等 UI 使用）</li>
 *   <li>{@code segments} — 按事件到达顺序记录的有序时间线（前端分段渲染用）</li>
 * </ul>
 * 两份数据从同一事件流构建，保证一致。segments 保留了 thinking → tools → content
 * 的真实交错顺序，toolCalls 是 segments 中 tool_call 类型的平铺视图。
 * <p>
 * Shared by the Web SSE path ({@code ChatController}) and the IM channel
 * router — live fan-out side effects go through the injected {@link Sink}
 * so each caller keeps its own broadcast semantics. Internal bookkeeping
 * events ({@code _usage_final}, {@code _routing_decision}) are consumed
 * here and never reach the sink.
 */
@Slf4j
public final class AgentStreamAccumulator {

    /**
     * Live fan-out hooks. The accumulator itself only builds the persisted
     * metadata/parts; anything a subscriber should see in real time is
     * delegated here.
     */
    public interface Sink {
        /** Broadcast a named event to live subscribers of the conversation. */
        void broadcast(String conversationId, String eventName, Object payload);

        /** Update the conversation's current phase indicator. */
        void updatePhase(String conversationId, String phase);
    }

    /** Markdown link pointing at a generated-file download URL. Used to
     *  surface generated artifacts in the run-overview rail. */
    private static final Pattern GENERATED_FILE_LINK_PATTERN =
            Pattern.compile("\\[([^\\]]+)\\]\\(((?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/[A-Za-z0-9-]+)\\)");

    private final ObjectMapper objectMapper;
    private final Sink sink;

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final List<Map<String, Object>> toolCalls = new ArrayList<>();
    /** 有序事件时间线 — 前端分段渲染的权威数据源 */
    private final List<Map<String, Object>> segments = new ArrayList<>();
    private final List<Map<String, Object>> browserActions = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<Map<String, Object>> planStepResults = new ArrayList<>();
    /** Tool names whose returnDirect output was folded into the assistant message */
    private final List<String> directToolNames = new ArrayList<>();
    /** Generated file artifacts extracted from tool results — surfaced in the run-overview rail. */
    private final List<Map<String, Object>> generatedFiles = new ArrayList<>();
    private int segCounter = 0;
    private int promptTokens = 0;
    private int completionTokens = 0;
    private int cacheReadTokens = 0;
    private int cacheWriteTokens = 0;
    private int reasoningTokens = 0;
    private String runtimeModelName = "";
    private String runtimeProviderId = "";
    private boolean awaitingApproval = false;
    private String currentPhase = "";
    /**
     * Graph-emitted FinishReason for the turn (e.g. {@code "incomplete"},
     * {@code "stopped"}, {@code "evidence_insufficient"}). Sourced from
     * the {@code finish_reason} {@link GraphEventPublisher}
     * event that {@code FinalAnswerNode} attaches to its PENDING_EVENTS
     * output — same pipeline the SSE accumulator already drains, so the
     * value is delivered alongside the assistant content (not via a
     * sibling SSE-only broadcast that would bypass this accumulator).
     * Persisted into message metadata so downstream filters
     * (memory promotion gate) see a machine-readable status instead of
     * having to guess from text. Empty string until the event arrives.
     */
    private String finishReason = "";
    /**
     * Recovery affordance payload from {@link GraphEventPublisher#feedback}.
     * Persisted into {@code metadata.feedbackEvent} so a page reload still
     * surfaces the retry/regenerate/report card on the failed assistant
     * bubble. Null when the turn ended cleanly.
     */
    private Map<String, Object> feedbackEvent = null;
    private Long planId = null;
    private List<String> planSteps = List.of();
    private Integer currentPlanStep = null;
    private Map<String, Object> pendingApproval = null;
    /**
     * Multimodal sidecar routing decision for this turn (null when no
     * routing happened). Captured from the {@code _routing_decision}
     * event emitted before the graph stream and folded into
     * {@code metadata.routing} on persistence so the chat UI can show
     * which sidecar (if any) was invoked.
     */
    private Map<String, Object> routingDecision = null;

    public AgentStreamAccumulator(ObjectMapper objectMapper, Sink sink) {
        this.objectMapper = objectMapper;
        this.sink = sink;
    }

    public synchronized void accept(AgentService.StreamDelta delta, String conversationId) {
        if (delta == null) return;

        if (delta.isEvent()) {
            if ("_usage_final".equals(delta.eventType())) {
                Map<String, Object> data = delta.eventData();
                promptTokens = ((Number) data.getOrDefault("promptTokens", 0)).intValue();
                completionTokens = ((Number) data.getOrDefault("completionTokens", 0)).intValue();
                cacheReadTokens = ((Number) data.getOrDefault("cacheReadTokens", 0)).intValue();
                cacheWriteTokens = ((Number) data.getOrDefault("cacheWriteTokens", 0)).intValue();
                reasoningTokens = ((Number) data.getOrDefault("reasoningTokens", 0)).intValue();
                runtimeModelName = String.valueOf(data.getOrDefault("runtimeModelName", ""));
                runtimeProviderId = String.valueOf(data.getOrDefault("runtimeProviderId", ""));
                return;
            }
            if ("phase".equals(delta.eventType())) {
                String phase = String.valueOf(delta.eventData().getOrDefault("phase", ""));
                if (!phase.isBlank()) {
                    currentPhase = phase;
                    sink.updatePhase(conversationId, phase);
                    // phase 切换时关闭 running 的 content/thinking segment，保留边界
                    finalizeRunningSegments("content", "thinking");
                }
            }
            if ("finish_reason".equals(delta.eventType())) {
                Object reason = delta.eventData().get("reason");
                if (reason != null) {
                    // Last-write-wins: graph normally fires this exactly once
                    // at FinalAnswerNode completion. Replay paths that re-enter
                    // the graph after approval will emit a fresh value, which
                    // is the correct behavior — the latest reason is what gets
                    // persisted with the assistant message.
                    finishReason = String.valueOf(reason);
                }
            }
            if (GraphEventPublisher.EVENT_FEEDBACK.equals(delta.eventType())) {
                // Snapshot the affordance payload so it persists into
                // message metadata. The same event is also rebroadcast
                // live (via the sink fall-through below) so an
                // already-mounted UI sees it instantly without
                // waiting for the message-save round trip.
                feedbackEvent = delta.eventData();
            }
            if (GraphEventPublisher.EVENT_ROUTING_DECISION.equals(delta.eventType())) {
                // Captured at turn start; persisted under metadata.routing so the
                // chat UI can render which sidecar (if any) was invoked. Internal
                // event — return early to skip rebroadcast on IM channels.
                routingDecision = delta.eventData();
                return;
            }
            accumulateToolEvent(delta.eventType(), delta.eventData(), conversationId);
            try {
                sink.broadcast(conversationId, delta.eventType(), delta.eventData());
            } catch (Exception e) {
                log.warn("Failed to broadcast event {}: {}", delta.eventType(), e.getMessage());
            }
            return;
        }

        // content_delta
        if (delta.content() != null && !delta.content().isBlank()) {
            // segmentOnly deltas route per-iteration narration to the
            // segments timeline only — the persisted top-level content
            // field stays clean so it carries the final answer span,
            // not "我来…让我…" concatenations across iterations (issue
            // #120 narration leg). segmentOnly implies persistenceOnly,
            // so no broadcast either.
            if (!delta.segmentOnly()) {
                content.append(delta.content());
            }
            sink.updatePhase(conversationId, "drafting_answer");
            if (!delta.persistenceOnly()) {
                sink.broadcast(conversationId, "content_delta", Map.of("delta", delta.content()));
            }
            // segments: 追加到当前 running content segment，或创建新的
            var seg = findLastRunning("content");
            if (seg != null) {
                seg.put("text", seg.getOrDefault("text", "") + delta.content());
            } else {
                finalizeRunningSegments("thinking");
                var s = newSegment("content");
                s.put("text", delta.content());
                segments.add(s);
            }
        }

        // thinking_delta
        if (delta.thinking() != null && !delta.thinking().isBlank()) {
            if (!delta.segmentOnly()) {
                thinking.append(delta.thinking());
            }
            if (!delta.persistenceOnly()) {
                sink.broadcast(conversationId, "thinking_delta", Map.of("delta", delta.thinking()));
            }
            var seg = findLastRunning("thinking");
            if (seg != null) {
                seg.put("thinkingText", seg.getOrDefault("thinkingText", "") + delta.thinking());
            } else {
                var s = newSegment("thinking");
                s.put("thinkingText", delta.thinking());
                segments.add(s);
            }
        }
    }

    public boolean isAwaitingApproval() { return awaitingApproval; }

    private void accumulateToolEvent(String eventType, Map<String, Object> data, String conversationId) {
        if ("tool_approval_requested".equals(eventType)) {
            awaitingApproval = true;
            currentPhase = "awaiting_approval";
            pendingApproval = new LinkedHashMap<>();
            pendingApproval.put("pendingId", data.getOrDefault("pendingId", ""));
            pendingApproval.put("toolName", data.getOrDefault("toolName", ""));
            pendingApproval.put("arguments", data.getOrDefault("arguments", ""));
            pendingApproval.put("reason", data.getOrDefault("reason", ""));
            pendingApproval.put("status", "pending_approval");
            if (data.containsKey("findings")) pendingApproval.put("findings", data.get("findings"));
            if (data.containsKey("maxSeverity")) pendingApproval.put("maxSeverity", data.get("maxSeverity"));
            if (data.containsKey("summary")) pendingApproval.put("summary", data.get("summary"));
            sink.updatePhase(conversationId, "awaiting_approval");
        } else if ("tool_approval_resolved".equals(eventType)) {
            if (pendingApproval != null) {
                pendingApproval.put("status",
                        "approved".equals(String.valueOf(data.getOrDefault("decision", ""))) ? "approved" : "denied");
            }
        } else if ("plan_created".equals(eventType)) {
            Object rawPlanId = data.get("planId");
            if (rawPlanId instanceof Number n) {
                planId = n.longValue();
            } else if (rawPlanId != null) {
                try { planId = Long.valueOf(String.valueOf(rawPlanId)); } catch (Exception ignored) {}
            }
            Object steps = data.get("steps");
            if (steps instanceof List<?> list) {
                planSteps = list.stream().map(String::valueOf).toList();
                planStepResults.clear();
                for (int i = 0; i < planSteps.size(); i++) {
                    planStepResults.add(null);
                }
            }
            currentPlanStep = 0;
        } else if ("plan_step_started".equals(eventType)) {
            Object idx = data.get("index");
            if (idx instanceof Number n) {
                currentPlanStep = n.intValue();
            }
        } else if ("plan_step_completed".equals(eventType)) {
            Object idx = data.get("index");
            if (idx instanceof Number n) {
                int index = n.intValue();
                currentPlanStep = index;
                ensurePlanStepCapacity(index + 1);
                Map<String, Object> stepResult = new LinkedHashMap<>();
                stepResult.put("result", data.getOrDefault("result", ""));
                stepResult.put("status", "completed");
                planStepResults.set(index, stepResult);
            }
        } else if ("browser_action".equals(eventType)) {
            browserActions.add(new LinkedHashMap<>(data));
        } else if ("warning".equals(eventType)) {
            String warning = String.valueOf(data.getOrDefault("message",
                    data.getOrDefault("delta", "")));
            if (!warning.isBlank()) {
                warnings.add(warning);
            }
        } else if ("tool_call_started".equals(eventType)) {
            // toolCalls（兼容）
            Map<String, Object> tc = new LinkedHashMap<>();
            // toolCallId is required for history replay to pair the persisted
            // assistant tool_call with its tool_response — providers reject any
            // sequence whose ids don't match. Always record it (empty string
            // when the upstream event didn't carry one, e.g. forced tool calls).
            tc.put("toolCallId", String.valueOf(data.getOrDefault("toolCallId", "")));
            tc.put("name", data.getOrDefault("toolName", ""));
            tc.put("arguments", data.getOrDefault("arguments", ""));
            tc.put("status", "running");
            toolCalls.add(tc);
            // segments: 关闭 running thinking/content，插入 tool_call
            finalizeRunningSegments("thinking", "content");
            var seg = newSegment("tool_call");
            seg.put("toolCallId", String.valueOf(data.getOrDefault("toolCallId", "")));
            seg.put("toolName", data.getOrDefault("toolName", ""));
            seg.put("toolArgs", data.getOrDefault("arguments", ""));
            segments.add(seg);
        } else if ("tool_direct_result".equals(eventType)) {
            // returnDirect tool — track the tool name so history replay can
            // render a "data returned directly by tool" badge. The actual
            // textual content reaches the user/persistence layer through the
            // regular content_delta path (FinalAnswerNode's FINAL_ANSWER →
            // StateGraphReActAgent → StreamDelta), so we intentionally do
            // NOT add a content-bearing segment here to avoid the user
            // seeing the same text twice.
            String toolName = String.valueOf(data.getOrDefault("toolName", ""));
            if (!toolName.isBlank() && !directToolNames.contains(toolName)) {
                directToolNames.add(toolName);
            }
        } else if ("tool_call_completed".equals(eventType)) {
            String toolName = String.valueOf(data.getOrDefault("toolName", ""));
            String toolCallId = String.valueOf(data.getOrDefault("toolCallId", ""));
            // toolCalls（兼容）— prefer toolCallId match so parallel calls of
            // the same tool don't collide on the running+toolName fallback.
            for (int i = toolCalls.size() - 1; i >= 0; i--) {
                Map<String, Object> tc = toolCalls.get(i);
                boolean matches = (!toolCallId.isEmpty()
                            && toolCallId.equals(String.valueOf(tc.getOrDefault("toolCallId", ""))))
                        || (toolCallId.isEmpty()
                            && "running".equals(tc.get("status"))
                            && toolName.equals(tc.get("name")));
                if (matches) {
                    tc.put("result", data.getOrDefault("result", ""));
                    tc.put("success", data.getOrDefault("success", true));
                    tc.put("status", "completed");
                    break;
                }
            }
            // segments: 标记对应 tool_call 完成
            for (int i = segments.size() - 1; i >= 0; i--) {
                var seg = segments.get(i);
                if (!"tool_call".equals(seg.get("type"))) continue;
                boolean matches = (!toolCallId.isEmpty()
                            && toolCallId.equals(String.valueOf(seg.getOrDefault("toolCallId", ""))))
                        || (toolCallId.isEmpty()
                            && "running".equals(seg.get("status"))
                            && toolName.equals(seg.get("toolName")));
                if (matches) {
                    seg.put("status", "completed");
                    seg.put("toolResult", data.getOrDefault("result", ""));
                    seg.put("toolSuccess", data.getOrDefault("success", true));
                    break;
                }
            }
            // Extract generated-file links from the tool result so the
            // run-overview rail can surface artifacts without re-scanning
            // segments on the frontend.
            extractGeneratedFiles(String.valueOf(data.getOrDefault("result", "")), toolName);
        }
    }

    /** Scan a tool result for markdown links pointing at generated-file
     *  download URLs and collect them into {@link #generatedFiles}.
     *  De-duplicates by URL so a link echoed in later tool results doesn't
     *  produce duplicate entries in the run-overview rail. */
    private void extractGeneratedFiles(String result, String toolName) {
        if (result == null || result.isBlank()) return;
        Matcher m = GENERATED_FILE_LINK_PATTERN.matcher(result);
        while (m.find()) {
            String url = m.group(2);
            boolean dup = generatedFiles.stream()
                    .anyMatch(f -> url.equals(String.valueOf(f.get("url"))));
            if (dup) continue;
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("filename", m.group(1));
            file.put("url", url);
            file.put("toolName", toolName);
            generatedFiles.add(file);
        }
    }

    private void ensurePlanStepCapacity(int size) {
        while (planStepResults.size() < size) {
            planStepResults.add(null);
        }
    }

    // ==================== Segment helpers ====================

    private Map<String, Object> newSegment(String type) {
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("id", type.substring(0, 2) + "-" + segCounter++);
        seg.put("type", type);
        seg.put("status", "running");
        return seg;
    }

    private Map<String, Object> findLastRunning(String type) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            var seg = segments.get(i);
            if (type.equals(seg.get("type")) && "running".equals(seg.get("status"))) return seg;
        }
        return null;
    }

    private void finalizeRunningSegments(String... types) {
        var typeSet = Set.of(types);
        for (var seg : segments) {
            if ("running".equals(seg.get("status")) && typeSet.contains(seg.get("type"))) {
                seg.put("status", "completed");
            }
        }
    }

    // ==================== 原有访问器 ====================

    public String getContent() { return content.toString().trim(); }
    public String getThinking() { return thinking.toString().trim(); }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getCacheReadTokens() { return cacheReadTokens; }
    public int getCacheWriteTokens() { return cacheWriteTokens; }
    public int getReasoningTokens() { return reasoningTokens; }
    public String getRuntimeModelName() { return runtimeModelName; }
    public String getRuntimeProviderId() { return runtimeProviderId; }
    public String getCurrentPhase() { return currentPhase; }
    public String getFinishReason() { return finishReason; }
    public boolean segmentsEmpty() { return segments.isEmpty(); }

    public synchronized List<MessageContentPart> toAssistantParts() {
        List<MessageContentPart> parts = new ArrayList<>();
        if (!getContent().isBlank()) {
            MessageContentPart textPart = new MessageContentPart();
            textPart.setType("text");
            textPart.setText(getContent());
            parts.add(textPart);
        }
        if (!getThinking().isBlank()) {
            MessageContentPart thinkingPart = new MessageContentPart();
            thinkingPart.setType("thinking");
            thinkingPart.setText(getThinking());
            parts.add(thinkingPart);
        }
        for (Map<String, Object> tc : toolCalls) {
            try {
                parts.add(MessageContentPart.toolCall(objectMapper.writeValueAsString(tc)));
            } catch (Exception e) {
                log.warn("Failed to serialize tool call: {}", e.getMessage());
            }
        }
        return parts;
    }

    private void finalizeToolCalls() {
        for (Map<String, Object> tc : toolCalls) {
            if ("running".equals(tc.get("status"))) tc.put("status", "completed");
        }
    }

    /**
     * 生成 metadata JSON：包含 toolCalls + segments。
     * toolCalls 保留兼容旧 UI，segments 是按事件顺序的完整时间线。
     */
    public synchronized String toMetadataJson() {
        finalizeToolCalls();
        finalizeRunningSegments("thinking", "content", "tool_call");
        SegmentSupersedeDetector.markSuperseded(segments);
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (!toolCalls.isEmpty()) {
                metadata.put("toolCalls", toolCalls);
            }
            if (!segments.isEmpty()) {
                metadata.put("segments", segments);
            }
            if (!currentPhase.isBlank()) {
                metadata.put("currentPhase", currentPhase);
            }
            if (planId != null || !planSteps.isEmpty() || currentPlanStep != null) {
                Map<String, Object> plan = new LinkedHashMap<>();
                if (planId != null) plan.put("planId", planId);
                if (!planSteps.isEmpty()) plan.put("steps", planSteps);
                if (currentPlanStep != null) plan.put("currentStep", currentPlanStep);
                if (planStepResults.stream().anyMatch(Objects::nonNull)) {
                    plan.put("stepResults", planStepResults);
                }
                metadata.put("plan", plan);
            }
            if (pendingApproval != null && !pendingApproval.isEmpty()) {
                metadata.put("pendingApproval", pendingApproval);
            }
            if (!browserActions.isEmpty()) {
                metadata.put("browserActions", browserActions);
            }
            if (!warnings.isEmpty()) {
                metadata.put("warnings", warnings);
            }
            if (!directToolNames.isEmpty()) {
                // Only the tool names go into metadata — the full content
                // already lives in mate_message.content (assembled by
                // FinalAnswerNode). UI uses this to badge historical
                // messages as "data returned directly by tool".
                metadata.put("directToolNames", directToolNames);
            }
            if (!generatedFiles.isEmpty()) {
                metadata.put("generatedFiles", generatedFiles);
            }
            if (!finishReason.isEmpty()) {
                // Surface graph FinishReason so MemorySummarizationGate and
                // any other downstream consumer can branch on a structured
                // status (e.g. skip INCOMPLETE / STOPPED / ERROR_FALLBACK
                // turns from long-term memory promotion) instead of doing
                // brittle text matching on the assistant content.
                metadata.put("finishReason", finishReason);
            }
            if (feedbackEvent != null && !feedbackEvent.isEmpty()) {
                // Persist the recovery-affordance payload so the
                // retry/regenerate/report card survives page reload.
                // Stored as-is (errorType, errorMessage, actions,
                // timestamp) — frontend MessageBubble reads
                // metadata.feedbackEvent and renders one button per
                // entry in `actions`.
                metadata.put("feedbackEvent", feedbackEvent);
            }
            if (routingDecision != null && !routingDecision.isEmpty()) {
                metadata.put("routing", routingDecision);
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata: {}", e.getMessage());
            return "{}";
        }
    }
}
