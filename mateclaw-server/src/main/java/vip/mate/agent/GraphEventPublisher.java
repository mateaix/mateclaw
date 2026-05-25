package vip.mate.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.util.List;
import java.util.Map;

/**
 * Graph 事件发布工具
 * <p>
 * 所有方法都是 static，不做状态管理。
 * 节点内部收集 List&lt;GraphEvent&gt;，最终写入 PENDING_EVENTS。
 * StateGraph*Agent 从 NodeOutput 中读取这些事件。
 *
 * @author MateClaw Team
 */
public final class GraphEventPublisher {

    private GraphEventPublisher() {}

    // ===== 事件类型常量 =====
    public static final String EVENT_PHASE = "phase";
    public static final String EVENT_TOOL_START = "tool_call_started";
    public static final String EVENT_TOOL_COMPLETE = "tool_call_completed";
    public static final String EVENT_PLAN_CREATED = "plan_created";
    public static final String EVENT_STEP_STARTED = "plan_step_started";
    public static final String EVENT_STEP_COMPLETED = "plan_step_completed";
    public static final String EVENT_TOOL_APPROVAL_REQUESTED = "tool_approval_requested";
    /** RFC-06 D-6: lightweight performance summary emitted per-phase. */
    public static final String EVENT_PERF_SUMMARY = "perf_summary";
    /**
     * RFC-052: a tool with returnDirect=true completed; its full result is
     * carried in the payload and is intended to be rendered as part of the
     * assistant message (renderAs=assistant_message), bypassing the LLM.
     */
    public static final String EVENT_TOOL_DIRECT_RESULT = "tool_direct_result";

    /**
     * Terminal {@link vip.mate.agent.graph.state.FinishReason} for the turn,
     * emitted at FinalAnswerNode so channel-side accumulators can persist it
     * into message metadata. Downstream filters (e.g. memory promotion gate)
     * branch on this structured value instead of doing brittle text matching
     * on the assistant content.
     */
    public static final String EVENT_FINISH_REASON = "finish_reason";

    /**
     * User-facing recovery affordances offered after a turn ends in a
     * non-transient error. Carries the error type + message + a
     * data-driven list of actions ({@code retry}, {@code regenerate},
     * {@code report}) so the frontend can render the right buttons
     * without hard-coding which categories deserve which actions.
     *
     * <p>Sibling to {@link #EVENT_FINISH_REASON} (which only carries the
     * machine-readable reason). The two are kept separate so legacy
     * consumers of {@code finish_reason} don't have to learn a new
     * payload shape — and so a future graph branch (e.g. evidence-
     * insufficient → "rerun with the listed files attached") can emit
     * feedback affordances without abusing the finish_reason channel.
     */
    public static final String EVENT_FEEDBACK = "feedback_event";

    /**
     * Multimodal sidecar routing decision for the current turn. Emitted once
     * per turn before the graph starts streaming; the channel-side accumulator
     * stores it under {@code metadata.routing} so the chat UI can show which
     * sidecar (if any) was invoked. Underscore-prefixed name keeps it out of
     * IM channel rebroadcast (see {@code ChannelMessageRouter}).
     */
    public static final String EVENT_ROUTING_DECISION = "_routing_decision";

    /**
     * 事件记录
     */
    public record GraphEvent(String type, Map<String, Object> data, long timestamp) {}

    // ===== 静态工厂方法 =====

    public static GraphEvent phase(String phase, Map<String, Object> extra) {
        long ts = System.currentTimeMillis();
        Map<String, Object> data = new java.util.HashMap<>(extra);
        data.put("phase", phase);
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_PHASE, Map.copyOf(data), ts);
    }

    public static GraphEvent toolStart(String toolName, String arguments) {
        return toolStart(null, toolName, arguments);
    }

    /**
     * Emit a tool_call_started event with the LLM-provided tool_call.id so the
     * frontend can match start/complete pairs precisely. Without the id, the
     * UI uses toolName + status="running" + findLast() to pair completes back
     * to the original card; when the LLM fires multiple calls of the same tool
     * (e.g. several execute_shell_command in a row) the matching collapses to
     * "the most recent running" and earlier cards get stranded with a
     * permanent spinner. Pass the id whenever it's available; null is OK for
     * legacy callers.
     */
    public static GraphEvent toolStart(String toolCallId, String toolName, String arguments) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_TOOL_START, Map.of(
                "toolCallId", toolCallId != null ? toolCallId : "",
                "toolName", toolName,
                "arguments", arguments != null ? arguments : "",
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent toolComplete(String toolName, String result, boolean success) {
        return toolComplete(null, toolName, result, success);
    }

    public static GraphEvent toolComplete(String toolCallId, String toolName, String result, boolean success) {
        long ts = System.currentTimeMillis();
        // Carry the full tool result; transport-layer chunking lives in
        // ChatStreamTracker.broadcastChunked, which splits oversize payloads
        // into ordered tool_result_chunk events when they exceed the 8 KB
        // single-event budget. The previous unconditional 500-char truncation
        // here destroyed data that the front-end could otherwise render in full.
        return new GraphEvent(EVENT_TOOL_COMPLETE, Map.of(
                "toolCallId", toolCallId != null ? toolCallId : "",
                "toolName", toolName,
                "result", result != null ? result : "",
                "success", success,
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent planCreated(Long planId, List<String> steps) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_PLAN_CREATED, Map.of(
                "planId", planId,
                "steps", steps,
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent stepStarted(int index, String title) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_STEP_STARTED, Map.of(
                "index", index,
                "title", title != null ? title : "",
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent stepCompleted(int index, String result) {
        long ts = System.currentTimeMillis();
        // Full step result; broadcastChunked splits at the transport layer
        // when the payload exceeds the per-event size budget.
        return new GraphEvent(EVENT_STEP_COMPLETED, Map.of(
                "index", index,
                "result", result != null ? result : "",
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent toolApprovalRequested(String pendingId, String toolName,
                                                    String arguments, String reason) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_TOOL_APPROVAL_REQUESTED, Map.of(
                "pendingId", pendingId,
                "toolName", toolName != null ? toolName : "",
                "arguments", arguments != null ? arguments : "",
                "reason", reason != null ? reason : "",
                "timestamp", ts
        ), ts);
    }

    /**
     * 增强版审批事件（包含 findings、severity、summary）
     */
    public static GraphEvent toolApprovalRequested(String pendingId, String toolName,
                                                    String arguments, String reason,
                                                    String summary, String maxSeverity,
                                                    List<Map<String, Object>> findings) {
        long ts = System.currentTimeMillis();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pendingId", pendingId);
        data.put("toolName", toolName != null ? toolName : "");
        data.put("arguments", arguments != null ? arguments : "");
        data.put("reason", reason != null ? reason : "");
        data.put("summary", summary);
        data.put("maxSeverity", maxSeverity);
        data.put("findings", findings != null ? findings : List.of());
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_TOOL_APPROVAL_REQUESTED, Map.copyOf(data), ts);
    }

    /**
     * RFC-06 D-6: emit a lightweight performance summary for a phase.
     * Consumers (dashboard, audit, _usage_final) can aggregate these
     * to reconstruct per-turn latency profiles without full tracing.
     *
     * @param phase           e.g. "triage", "reasoning", "tool_execution"
     * @param metrics         arbitrary key-value pairs (e.g. "retry_count", "backoff_wait_ms")
     */
    /**
     * RFC-052: emit a tool result that was produced by a returnDirect tool.
     * The full text is carried verbatim and the {@code renderAs="assistant_message"}
     * hint instructs the SSE consumer (front-end / accumulator) to fold the
     * payload into the assistant bubble rather than into a tool card.
     */
    public static GraphEvent toolDirectResult(String toolCallId, String toolName, String fullResult) {
        long ts = System.currentTimeMillis();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("toolCallId", toolCallId != null ? toolCallId : "");
        data.put("toolName", toolName != null ? toolName : "");
        data.put("result", fullResult != null ? fullResult : "");
        data.put("renderAs", "assistant_message");
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_TOOL_DIRECT_RESULT, Map.copyOf(data), ts);
    }

    public static GraphEvent perfSummary(String phase, Map<String, Object> metrics) {
        long ts = System.currentTimeMillis();
        Map<String, Object> data = new java.util.HashMap<>(metrics);
        data.put("phase", phase);
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_PERF_SUMMARY, Map.copyOf(data), ts);
    }

    /**
     * Terminal {@code finish_reason} event. Emitted from FinalAnswerNode so it
     * rides through the same PENDING_EVENTS → StreamDelta pipeline that
     * channel-side accumulators consume — a sibling SSE-only broadcast would
     * bypass {@code ChatController.StreamAccumulator.accept(...)} and fail to
     * persist the reason into message metadata.
     *
     * @param reason {@link vip.mate.agent.graph.state.FinishReason#getValue()}
     *               (e.g. {@code "incomplete"}, {@code "stopped"},
     *               {@code "evidence_insufficient"}, {@code "normal"}).
     */
    public static GraphEvent finishReason(String reason) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_FINISH_REASON, Map.of(
                "reason", reason != null ? reason : "",
                "timestamp", ts
        ), ts);
    }

    /**
     * Emit a recovery-affordance event for the frontend. {@code errorType}
     * mirrors the {@code NodeStreamingChatHelper.ErrorType} value (e.g.
     * {@code AUTH_ERROR}, {@code BILLING}, {@code MODEL_NOT_FOUND}, or
     * the generic {@code UNKNOWN}); {@code errorMessage} is the
     * user-friendly text already displayed in the bubble; {@code actions}
     * is the ordered list of buttons to render. Default offering is the
     * standard {@code retry / regenerate / report} triad — call sites
     * can narrow this if a category has limitations (e.g. AUTH_ERROR
     * shouldn't offer "retry" until the key is fixed).
     */
    public static GraphEvent feedback(String errorType, String errorMessage,
                                       java.util.List<String> actions) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_FEEDBACK, Map.of(
                "errorType", errorType != null ? errorType : "",
                "errorMessage", errorMessage != null ? errorMessage : "",
                "actions", actions != null && !actions.isEmpty()
                        ? actions
                        : java.util.List.of("retry", "regenerate", "report"),
                "timestamp", ts
        ), ts);
    }

    // ===== 提取方法 =====

    /**
     * 从 NodeOutput 中提取 PENDING_EVENTS
     */
    @SuppressWarnings("unchecked")
    public static List<GraphEvent> extractEvents(NodeOutput output) {
        if (output == null || output.state() == null) {
            return List.of();
        }
        return output.state().<List<GraphEvent>>value(MateClawStateKeys.PENDING_EVENTS)
                .orElse(List.of());
    }

    /**
     * Pass-through; preserved for source/binary compatibility with older callers.
     * Truncation at the SSE transport layer is now handled by {@code
     * ChatStreamTracker.broadcastChunked} which splits oversize payloads into
     * ordered chunk events instead of dropping bytes. Logs a one-time
     * deprecation hint when invoked.
     *
     * @deprecated callers should pass full payloads and let the transport layer
     *     decide whether to chunk.
     */
    @Deprecated
    private static String truncateResult(String result) {
        warnTruncateDeprecation();
        return result;
    }

    /**
     * Pass-through, kept for source compatibility with code that built broadcast
     * payloads directly. Same deprecation reason as {@link #truncateResult}.
     *
     * @deprecated callers should pass full payloads.
     */
    @Deprecated
    public static String truncateForBroadcast(String text) {
        warnTruncateDeprecation();
        return text;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean TRUNCATE_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void warnTruncateDeprecation() {
        if (TRUNCATE_WARNED.compareAndSet(false, true)) {
            org.slf4j.LoggerFactory.getLogger(GraphEventPublisher.class)
                    .warn("GraphEventPublisher.truncateResult/truncateForBroadcast are deprecated " +
                            "no-op pass-throughs; payloads are no longer truncated here. " +
                            "Move callers to send the full string and rely on " +
                            "ChatStreamTracker.broadcastChunked for transport-level chunking.");
        }
    }

    // ===== Iteration lifecycle events =====

    public static final String EVENT_ITERATION_START = "iteration_start";
    public static final String EVENT_ITERATION_END   = "iteration_end";

    /**
     * Marks the entry of an iteration boundary so consumers can group later
     * tool / content / thinking events under a single logical step. The
     * {@code scope} field distinguishes the parent agent ("parent") from a
     * delegated sub-agent ("subagent"); when scope is "subagent" the
     * {@code subagentId} payload field is populated by the producer.
     */
    public static GraphEvent iterationStart(int index, String reason, String scope, String subagentId) {
        long ts = System.currentTimeMillis();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("index", index);
        data.put("reason", reason != null ? reason : "");
        data.put("scope", scope != null ? scope : "parent");
        if (subagentId != null && !subagentId.isEmpty()) {
            data.put("subagentId", subagentId);
        }
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_ITERATION_START, Map.copyOf(data), ts);
    }

    /**
     * Closes the matching {@link #iterationStart} boundary. {@code contentChars}
     * and {@code thinkingChars} let consumers render a compact "this turn
     * produced X content / Y thinking" header without re-aggregating the
     * underlying delta events.
     */
    public static GraphEvent iterationEnd(int index, String scope, String subagentId,
                                          int contentChars, int thinkingChars) {
        long ts = System.currentTimeMillis();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("index", index);
        data.put("scope", scope != null ? scope : "parent");
        if (subagentId != null && !subagentId.isEmpty()) {
            data.put("subagentId", subagentId);
        }
        data.put("contentChars", contentChars);
        data.put("thinkingChars", thinkingChars);
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_ITERATION_END, Map.copyOf(data), ts);
    }
}
