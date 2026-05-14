package vip.mate.agent.graph.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.graph.state.SourceEvidenceLedger;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.tool.guard.ToolExecutionGuardHelper;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.tool.guard.service.ToolGuardService;

import java.util.*;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 统一工具执行器（共享于 ActionNode 和 StepExecutionNode）
 * <p>
 * 两阶段执行模型：
 * <ol>
 *   <li><b>Phase 1 — 顺序 Guard + 分段</b>：按原始顺序逐个做 JSON 校验 → ToolGuard → barrier 判定 → callback 查找 + concurrencySafe 分类</li>
 *   <li><b>Phase 2 — 分段并发执行</b>：barrier 之前的 safe 工具并行执行，unsafe 工具独占执行，结果按原始顺序返回</li>
 * </ol>
 * <p>
 * 审批有前序语义：如果第 N 个工具需要审批，第 N+1、N+2 个工具不会执行。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ToolExecutionExecutor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // JDK 21 virtual threads: no blocking stall for I/O-bound tools.
    // Each tool invocation gets its own lightweight carrier thread.
    // Named threads (matching HookDispatcher convention) for log traceability.
    private static final ExecutorService TOOL_EXECUTOR =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("tool-executor-", 0).factory());

    /**
     * Legacy hardcoded unsafe set, kept as a fallback when no
     * {@link vip.mate.tool.ToolConcurrencyRegistry} is wired in (legacy tests,
     * backwards-compatible constructors). New code should annotate the tool
     * method with {@link vip.mate.tool.ConcurrencyUnsafe} instead of editing
     * this list.
     */
    /**
     * Defense against runaway single-response tool floods.
     *
     * <p>Some models (StreamLake's kat-coder-pro-v1 has been observed
     * emitting 50+ in one shot) return huge {@code tool_calls}
     * batches in a single response. Without a cap, every call executes, which
     * can saturate downstream provider QPS, multiply approval rows, and burn
     * tokens. The cap is independent of {@code MAX_ITERATIONS} (which limits
     * the loop count, not the per-response batch).
     *
     * <p>16 covers virtually every legitimate parallel-search / batch-edit
     * scenario; truncated calls receive a synthetic {@link ToolResponseMessage}
     * in the same turn so the LLM can re-issue the most-important ones in
     * its next response rather than hanging on missing tool replies.
     */
    static final int MAX_TOOL_CALLS_PER_RESPONSE = 16;

    private static final Set<String> DEFAULT_UNSAFE_TOOLS = Set.of(
            "browser_use", "BrowserUseTool", "write_file", "edit_file"
    );

    /**
     * Inline hard-truncate cap for a single tool result. Acts as the fallback
     * when raw-first spill cannot run (storage disabled, tool excluded, body
     * already at-or-below the spill threshold, or disk write failed).
     *
     * <p>Per-tool-result handling chain:
     * <pre>
     *   raw tool result (full bytes)
     *     → spillRawOrTruncate(...)
     *         ├─ persistIfOversized(...) tries to write the raw body to disk
     *         │     when size &gt; perResultThresholdChars and tool is not
     *         │     in the spill exclusion list. Returns a SPILL_MARKER preview
     *         │     on success, or the original string otherwise.
     *         └─ if no SPILL_MARKER on the return, truncateToolResult(...)
     *               caps inline to MAX_TOOL_RESULT_CHARS so a multi-MB raw
     *               body never enters the model prompt.
     *     → enforceTurnBudget(..., perTurnBudgetChars=32000)   // per-turn aggregate
     * </pre>
     * Spill must see the RAW result so the full output is preserved on disk
     * and the model can call {@code read_file} on the spill path. Truncating
     * before spilling would write a pre-shortened blob to disk, defeating the
     * "ground truth on disk" guarantee. {@link ToolResultProperties} controls
     * the thresholds; this constant stays in code because it is the safety
     * net for the failure case and should not vary by deployment.
     */
    private static final int MAX_TOOL_RESULT_CHARS = 8000;

    /**
     * Raw-first spill: try to write the full result to disk via the spill
     * store; only fall back to inline hard-truncate when no spill marker
     * comes back. Caller distinguishes spill success from "returned
     * unchanged" by checking {@link ToolResultStorage#SPILL_MARKER_PREFIX}
     * on the returned string — otherwise an IO failure or under-threshold
     * body would slip through indistinguishable from a successful spill,
     * and a multi-MB raw body could end up in the model prompt.
     *
     * <p>Package-private + static so the spill/truncate decision is unit
     * testable in isolation from the rest of the executor.
     *
     * @param storage          spill store; {@code null} skips the spill attempt
     * @param maxTruncateChars fallback inline hard cap
     * @param result           raw tool output (may be {@code null})
     * @param toolName         used in the spill preview header
     * @param toolUseId        unique within the conversation; becomes the file name
     * @param conversationId   spill files are scoped per conversation; blank/null falls back to "unknown"
     * @param workspaceBasePath where the spill directory lives when set
     * @return the SPILL_MARKER preview when spill succeeded, otherwise the
     *         original string (when ≤ threshold) or the inline-truncated string.
     */
    static String spillRawOrTruncate(ToolResultStorage storage, int maxTruncateChars,
                                     String result, String toolName, String toolUseId,
                                     String conversationId, String workspaceBasePath) {
        if (result == null) return null;
        if (storage != null) {
            String safeConv = conversationId != null && !conversationId.isEmpty()
                    ? conversationId : "unknown";
            String candidate = storage.persistIfOversized(
                    result, toolName, toolUseId, safeConv, workspaceBasePath);
            if (candidate != null && candidate.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX)) {
                return candidate;
            }
        }
        return truncateToolResult(result, maxTruncateChars);
    }

    /** 尾部错误模式检测 */
    private static final java.util.regex.Pattern ERROR_TAIL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b(error|exception|traceback|failed|fatal|panic|stack.?trace|errno)\\b");

    /**
     * RFC-052 §2.4: placeholder written into {@code ToolResponseMessage.content}
     * for returnDirect tools. Intentionally English, short, and free of any
     * tool-specific data so it is safe to enter prompt cache and gives the LLM
     * a clear signal that the tool ran (vs failed).
     */
    static final String DIRECT_TOOL_PLACEHOLDER =
            "[Tool result returned directly to user. Content withheld from model context per tool policy.]";

    /**
     * 智能截断工具结果：检测尾部是否含错误信息，动态调整 head/tail 比例。
     * 错误信息在尾部时保留 80% tail，确保 agent 能看到错误原因。
     */
    static String truncateToolResult(String result, int maxChars) {
        if (result == null || result.length() <= maxChars) return result;
        int rawLen = result.length();
        // 检测尾部 2000 字符是否含错误模式
        String tailRegion = result.substring(Math.max(0, rawLen - 2000));
        boolean errorDetected = ERROR_TAIL_PATTERN.matcher(tailRegion).find();
        double headRatio = errorDetected ? 0.2 : 0.4;
        if (errorDetected) {
            log.info("[ToolExecutor] Error pattern detected in tail, preserving 80% tail (headRatio=0.2)");
        }
        int headLen = (int) (maxChars * headRatio);
        int tailLen = maxChars - headLen - 80;
        if (tailLen <= 0) tailLen = maxChars / 2;
        log.info("[ToolExecutor] Truncated tool result from {} to {} chars (headRatio={})",
                rawLen, maxChars, headRatio);
        return result.substring(0, headLen)
                + "\n\n... [结果已截断，原始 " + rawLen + " 字符，保留首尾关键片段] ...\n\n"
                + result.substring(rawLen - tailLen);
    }

    private final Map<String, ToolCallback> toolCallbackMap;
    /**
     * Maps a normalized tool name (lowercase snake_case, with `_tool`/`_function`
     * suffixes stripped) to the canonical name registered in {@link #toolCallbackMap}.
     * Lets us resolve names the LLM sometimes mangles (e.g. {@code WebSearch},
     * {@code web_search_tool}, {@code Read_File}) back to the registered tool
     * before guard / lookup / event reporting run, so guard rules keyed on the
     * canonical name aren't silently bypassed.
     */
    private final Map<String, String> normalizedNameLookup;
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private final ToolGuardService toolGuardService;
    private final ToolGuard toolGuard; // legacy fallback
    private final ApprovalWorkflowService approvalService;
    private final ChatStreamTracker streamTracker;
    private final vip.mate.config.ToolTimeoutProperties toolTimeoutProperties;
    /** RFC-008 Phase 3 spill store; nullable so legacy constructors keep working. */
    private final ToolResultStorage resultStorage;
    /** RFC-008 Phase 4 metadata-driven concurrency classifier; nullable for legacy constructors. */
    private final vip.mate.tool.ToolConcurrencyRegistry concurrencyRegistry;
    /**
     * Issue #46: when {@code toolCallbackMap} misses a name that the LLM
     * called, we check whether it matches an active skill so we can
     * return a precise hint instead of bare "Tool not found". Nullable —
     * legacy constructors and tests may leave this unset, in which case
     * the safety net falls through to the original error string.
     */
    private vip.mate.skill.runtime.SkillRuntimeService skillRuntimeService;

    public void setSkillRuntimeService(vip.mate.skill.runtime.SkillRuntimeService s) {
        this.skillRuntimeService = s;
    }

    /**
     * Optional audit sink. When set, child-agent denied-tool attempts are
     * recorded so admins can see what children are trying that gets blocked.
     * Left optional because legacy constructors and tests run without an
     * audit pipeline.
     */
    private vip.mate.audit.service.AuditEventService auditEventService;

    public void setAuditEventService(vip.mate.audit.service.AuditEventService s) {
        this.auditEventService = s;
    }

    /**
     * Per-turn deduplication key set for child-agent denial audit. Without
     * this, a child that retries the same denied tool many times in one
     * turn would write one audit row per call. Cleared at the start of
     * every {@code execute(...)} so it does not retain entries across turns.
     */
    private final ThreadLocal<Set<String>> auditedDenials =
            ThreadLocal.withInitial(HashSet::new);

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, null, null, null);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
                                  vip.mate.config.ToolTimeoutProperties toolTimeoutProperties) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, toolTimeoutProperties, null, null);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
                                  vip.mate.config.ToolTimeoutProperties toolTimeoutProperties,
                                  ToolResultStorage resultStorage) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, toolTimeoutProperties, resultStorage, null);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
                                  vip.mate.config.ToolTimeoutProperties toolTimeoutProperties,
                                  ToolResultStorage resultStorage,
                                  vip.mate.tool.ToolConcurrencyRegistry concurrencyRegistry) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, toolTimeoutProperties, resultStorage, concurrencyRegistry);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuard toolGuard,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker) {
        this.toolCallbackMap = toolSet.callbackByName();
        this.normalizedNameLookup = buildNormalizedLookup(this.toolCallbackMap.keySet());
        this.toolGuardService = null;
        this.toolGuard = toolGuard;
        this.approvalService = approvalService;
        this.streamTracker = streamTracker;
        this.toolTimeoutProperties = null;
        this.resultStorage = null;
        this.concurrencyRegistry = null;
    }

    private ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                   ToolGuard toolGuard, ApprovalWorkflowService approvalService,
                                   ChatStreamTracker streamTracker,
                                   vip.mate.config.ToolTimeoutProperties toolTimeoutProperties,
                                   ToolResultStorage resultStorage,
                                   vip.mate.tool.ToolConcurrencyRegistry concurrencyRegistry) {
        this.toolCallbackMap = toolSet.callbackByName();
        this.normalizedNameLookup = buildNormalizedLookup(this.toolCallbackMap.keySet());
        this.toolGuardService = toolGuardService;
        this.toolGuard = toolGuard;
        this.approvalService = approvalService;
        this.streamTracker = streamTracker;
        this.toolTimeoutProperties = toolTimeoutProperties;
        this.resultStorage = resultStorage;
        this.concurrencyRegistry = concurrencyRegistry;
    }

    private long getToolTimeoutMs(String toolName) {
        if (toolTimeoutProperties != null) {
            return toolTimeoutProperties.getTimeoutSeconds(toolName) * 1000L;
        }
        return 5 * 60 * 1000L; // default 5 min
    }

    /**
     * 执行工具调用列表
     *
     * @param toolCalls      LLM 请求的工具调用列表
     * @param conversationId 会话 ID
     * @param agentId        Agent ID
     * @param isReplay       是否为审批通过后的重放模式（跳过 ToolGuard）
     * @return 执行结果
     */
    public ToolExecutionResult execute(List<AssistantMessage.ToolCall> toolCalls,
                                        String conversationId, String agentId,
                                        boolean isReplay) {
        return execute(toolCalls, conversationId, agentId, isReplay, "");
    }

    public ToolExecutionResult execute(List<AssistantMessage.ToolCall> toolCalls,
                                        String conversationId, String agentId,
                                        boolean isReplay, String requesterId) {
        return execute(toolCalls, conversationId, agentId, isReplay, requesterId, null);
    }

    public ToolExecutionResult execute(List<AssistantMessage.ToolCall> toolCalls,
                                        String conversationId, String agentId,
                                        boolean isReplay, String requesterId,
                                        String workspaceBasePath) {
        return execute(toolCalls, conversationId, agentId, isReplay, requesterId,
                workspaceBasePath, ChatOrigin.EMPTY);
    }

    /**
     * RFC-063r §2.5: preferred overload — accepts a {@link ChatOrigin} that the
     * top-level agent has enriched with agentId/workspace/channel context.
     * Builds a Spring AI {@link ToolContext} per tool invocation so
     * {@code @Tool} methods can read the origin via
     * {@code ChatOrigin.from(toolContext)}.
     *
     * <p>During the PR-1 transition the legacy {@link ToolExecutionContext}
     * ThreadLocal is also populated, so existing tools that read from it keep
     * working unchanged. After all 8 callsites migrate, the ThreadLocal can be
     * removed.
     *
     * <p><b>Thread safety</b>: this executor instance is shared across all
     * concurrent invocations of a single agent (one executor per agent, per
     * {@code AgentGraphBuilder.build}). Origin / requester / workspace are
     * therefore <em>method-local</em> — they live as parameters all the way
     * down into {@link PreparedToolCall} and never touch instance state. An
     * earlier draft used {@code volatile} fields here; concurrent users hitting
     * the same agent (Web + IM at once) raced on those fields and the channel
     * binding was occasionally cross-contaminated. Do not reintroduce the
     * fields — pass via parameters.
     */
    public ToolExecutionResult execute(List<AssistantMessage.ToolCall> toolCalls,
                                        String conversationId, String agentId,
                                        boolean isReplay, String requesterId,
                                        String workspaceBasePath,
                                        ChatOrigin origin) {
        ChatOrigin safeOrigin = origin != null ? origin : ChatOrigin.EMPTY;
        // Reset per-turn audit dedupe state. A retried denied tool inside the
        // same turn writes a single audit row; the set is repopulated by the
        // denial branch below.
        auditedDenials.get().clear();
        List<ToolResponseMessage.ToolResponse> allResponses = new ArrayList<>();
        List<GraphEventPublisher.GraphEvent> events = Collections.synchronizedList(new ArrayList<>());
        // RFC-052: accumulate full-text outputs from returnDirect tools so the
        // graph can route to FinalAnswerNode without re-entering the LLM.
        List<DirectToolOutput> directOutputs = Collections.synchronizedList(new ArrayList<>());

        // RFC-03 Lane A2 — cap per-response tool_calls before doing any other
        // work. Truncated calls get synthetic responses appended right away so
        // the LLM sees the cap on its next turn instead of hanging on missing
        // ToolResponseMessages. See MAX_TOOL_CALLS_PER_RESPONSE javadoc.
        CappedToolCalls capped = capToolCalls(toolCalls, MAX_TOOL_CALLS_PER_RESPONSE);
        if (capped.wasTruncated) {
            int requested = toolCalls.size();
            log.warn("[ToolExecutor] Model returned {} tool_calls in one response; truncating to {} — see RFC-03 A2",
                    requested, MAX_TOOL_CALLS_PER_RESPONSE);
            events.add(GraphEventPublisher.phase("toolflood", Map.of(
                    "requested", requested,
                    "executed", MAX_TOOL_CALLS_PER_RESPONSE,
                    "dropped", requested - MAX_TOOL_CALLS_PER_RESPONSE)));
            allResponses.addAll(capped.truncatedResponses);
        }
        List<AssistantMessage.ToolCall> effectiveCalls = capped.effective;

        events.add(GraphEventPublisher.phase("action", Map.of("toolCount", effectiveCalls.size())));

        // Shared collector for raw-stage SourceEvidenceLedger entries. Every
        // PreparedToolCall built below points at this same AtomicReference;
        // executeSingleTool does an atomic accumulateAndGet(merge) right
        // after the raw tool result is in hand and BEFORE spill/truncate
        // shrinks it. ActionNode then reads the final ledger off
        // ToolExecutionResult.rawEvidenceLedger() instead of rebuilding it
        // from the spill-compacted responses (which routinely lose the
        // exact lines that mention the cited filenames — see #4b38f04f
        // production trace, where "ObservationNode.java" only appeared in
        // a 30 KB grep result that got head/tail-cut to 4 KB).
        java.util.concurrent.atomic.AtomicReference<SourceEvidenceLedger> rawEvidenceRef =
                new java.util.concurrent.atomic.AtomicReference<>(SourceEvidenceLedger.empty());

        // ═══ Phase 1: 顺序 Guard + 分段 ═══
        List<PreparedToolCall> preparedCalls = new ArrayList<>();
        ApprovalBarrier barrier = null;

        for (int i = 0; i < effectiveCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = effectiveCalls.get(i);
            // Resolve LLM-emitted name to canonical BEFORE guard / lookup so a
            // mangled name (Read_File, web_search_tool, BrowserUseTool) can't
            // bypass guard rules keyed on the canonical name.
            String toolName = resolveToolName(toolCall.name());
            String arguments = toolCall.arguments();

            events.add(GraphEventPublisher.toolStart(toolCall.id(), toolName, arguments));

            // 0. 子会话工具拦截：委派上下文中的子 Agent 禁止调用特定工具
            if (vip.mate.tool.builtin.DelegationContext.currentDepth() > 0) {
                java.util.Set<String> denied = vip.mate.tool.builtin.DelegationContext.childDeniedTools();
                if (denied.contains(toolName)) {
                    String msg = "[安全限制] 子 Agent 不允许使用工具: " + toolName;
                    log.info("[ToolExecutor] Child agent blocked from using tool: {}", toolName);
                    // Audit per (toolName, conversationId) tuple at most once
                    // per turn so a child retrying the same denied tool many
                    // times does not spam the audit table.
                    if (auditEventService != null && auditedDenials.get().add(toolName)) {
                        try {
                            String detail = "{\"toolName\":\"" + toolName + "\",\"conversationId\":\""
                                    + (conversationId != null ? conversationId : "")
                                    + "\",\"agentId\":\"" + (agentId != null ? agentId : "")
                                    + "\"}";
                            auditEventService.record("subagent.tool.denied", "tool",
                                    toolName, toolName, detail);
                        } catch (Exception auditEx) {
                            log.debug("[ToolExecutor] Audit write failed for denied tool {}: {}",
                                    toolName, auditEx.getMessage());
                        }
                    }
                    events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, msg, false));
                    allResponses.add(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, msg));
                    continue;
                }
            }

            // 1. JSON 校验
            if (arguments != null && !arguments.isBlank()) {
                try {
                    OBJECT_MAPPER.readTree(arguments);
                } catch (Exception jsonEx) {
                    log.warn("[ToolExecutor] Tool {} arguments invalid/truncated JSON (len={}): {}",
                            toolName, arguments.length(), jsonEx.getMessage());
                    String truncationError = normalizeToolExecutionError(jsonEx);
                    events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, truncationError, false));
                    allResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, truncationError));
                    continue;
                }
            }

            // 2. ToolGuard 安全检查（replay 模式跳过）
            if (!isReplay) {
                GuardDecision decision = evaluateGuard(toolCall, toolName, arguments,
                        conversationId, agentId, toolCalls, i, events, requesterId);

                if (decision.blocked) {
                    allResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, decision.response));
                    continue;
                }
                if (decision.needsApproval) {
                    // Barrier: 当前工具创建审批，后续工具不执行
                    allResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, decision.response));
                    // 标记后续工具为等待审批
                    for (int j = i + 1; j < effectiveCalls.size(); j++) {
                        AssistantMessage.ToolCall remaining = effectiveCalls.get(j);
                        allResponses.add(new ToolResponseMessage.ToolResponse(
                                remaining.id(), remaining.name(),
                                "[⏳ 等待审批] 前序工具等待审批中，本工具暂缓执行。"));
                    }
                    barrier = new ApprovalBarrier(decision.pendingId, toolName);
                    break;
                }
            } else {
                log.info("[ToolExecutor] Replay mode: skipping guard for pre-approved tool {}", toolName);
            }

            // 3. Callback 查找（跳过 provider 内置工具，如 Kimi 的 $web_search）
            if (toolName.startsWith("$")) {
                log.info("[ToolExecutor] Skipping provider builtin tool: {}", toolName);
                allResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolName, "Provider builtin tool executed server-side"));
                continue;
            }
            ToolCallback callback = toolCallbackMap.get(toolName);
            if (callback == null) {
                SkillRedirect redirect = tryAutoRedirectSkillCall(toolName, arguments, safeOrigin);
                if (redirect != null) {
                    // Auto-redirect succeeds with success=true on the SSE event so the
                    // model treats the SKILL.md content as the answer to a different,
                    // valid question (rather than as another failed call to recover from).
                    events.add(GraphEventPublisher.toolComplete(
                            toolCall.id(), toolName, redirect.response(), true));
                    allResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, redirect.response()));
                    continue;
                }
                String msg = skillAwareNotFoundMessage(toolName);
                log.warn("[ToolExecutor] {}", msg);
                events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, msg, false));
                allResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolName, msg));
                continue;
            }

            // 4. 分类: concurrencySafe
            boolean safe = isConcurrencySafe(toolName);
            preparedCalls.add(new PreparedToolCall(toolCall, callback, arguments, safe, allResponses.size(),
                    conversationId, requesterId, workspaceBasePath, safeOrigin, rawEvidenceRef));
            // 占位，Phase 2 填充
            allResponses.add(null);
        }

        // ═══ Phase 2: 分段并发执行 ═══
        if (!preparedCalls.isEmpty()) {
            executePreparedCalls(preparedCalls, allResponses, events, directOutputs);
        }

        // Defensive: drop null placeholders (should never appear in practice).
        allResponses.removeIf(Objects::isNull);

        // RFC-008 Phase 3 Layer 3: enforce per-turn aggregate budget across all
        // tool responses for this assistant turn. Spills the largest non-spilled
        // response in turn until the cumulative size fits the budget.
        if (resultStorage != null && !allResponses.isEmpty()) {
            allResponses = new ArrayList<>(resultStorage.enforceTurnBudget(
                    allResponses, conversationId, workspaceBasePath));
        }

        boolean hasApprovalPending = barrier != null;
        return new ToolExecutionResult(allResponses, events, hasApprovalPending,
                barrier != null ? barrier.pendingId : null,
                barrier != null ? barrier.toolName : null,
                List.copyOf(directOutputs),
                rawEvidenceRef.get());
    }

    /**
     * Execute a pre-approved tool call (used by StepExecutionNode's replay path
     * after a user approves a previously-blocked invocation).
     *
     * @param conversationId required for per-conversation spill scoping; when
     *     blank, spill files would land in a shared {@code unknown/} directory
     *     and break per-conversation cleanup.
     * @param workspaceBasePath optional; when blank, spill falls back to tmp.
     */
    public ToolResponseMessage.ToolResponse executePreApproved(
            AssistantMessage.ToolCall toolCall, String storedArguments,
            List<GraphEventPublisher.GraphEvent> events,
            String conversationId, String workspaceBasePath) {
        return executePreApproved(toolCall, storedArguments, events, conversationId,
                workspaceBasePath, null);
    }

    /**
     * RFC-052 PR-2: returnDirect-aware variant. When the pre-approved tool
     * declares {@code returnDirect=true}, the result is captured into
     * {@code directOutputs} (verbatim), the SSE consumer gets a
     * {@code tool_direct_result} event, and the {@link ToolResponseMessage}
     * carries the placeholder so any subsequent LLM call can never see the
     * full payload.
     *
     * @param directOutputs nullable; pass-through for callers that don't track
     *     direct outputs (kept for legacy compatibility).
     */
    public ToolResponseMessage.ToolResponse executePreApproved(
            AssistantMessage.ToolCall toolCall, String storedArguments,
            List<GraphEventPublisher.GraphEvent> events,
            String conversationId, String workspaceBasePath,
            List<DirectToolOutput> directOutputs) {
        String toolName = resolveToolName(toolCall.name());
        String callArguments = storedArguments != null ? storedArguments : toolCall.arguments();

        ToolCallback callback = toolCallbackMap.get(toolName);
        if (callback == null) {
            // Same auto-redirect for pre-approved replays — a stale skill-as-tool
            // approval shouldn't dead-end the conversation either.
            ChatOrigin replayOriginForRedirect = ChatOrigin.EMPTY
                    .withConversationId(conversationId)
                    .withWorkspace(null, workspaceBasePath);
            SkillRedirect redirect = tryAutoRedirectSkillCall(toolName, callArguments, replayOriginForRedirect);
            if (redirect != null) {
                events.add(GraphEventPublisher.toolComplete(
                        toolCall.id(), toolName, redirect.response(), true));
                return new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolName, redirect.response());
            }
            String msg = skillAwareNotFoundMessage(toolName);
            log.warn("[ToolExecutor] Pre-approved {}", msg);
            events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, msg, false));
            return new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, msg);
        }

        try {
            log.info("[ToolExecutor] Executing pre-approved tool: {}", toolName);
            // RFC-063r §2.5: forward ToolContext so the pre-approved tool can
            // still observe the originating ChatOrigin (channel/workspace).
            // Origin is method-local (see thread-safety note on execute());
            // the legacy ThreadLocal that used to carry it across executePreApproved
            // calls was a cross-conversation footgun and has been removed.
            ChatOrigin replayOrigin = ChatOrigin.EMPTY
                    .withConversationId(conversationId)
                    .withWorkspace(null, workspaceBasePath);
            String result = callback.call(callArguments, replayOrigin.toToolContext());
            int rawLen = result != null ? result.length() : 0;

            // RFC-052: pre-approved tool may itself be returnDirect — in that
            // case its result must take the direct path (no spill, no LLM).
            // Without this branch, an approved direct tool would leak its full
            // content into the next LLM round-trip via the ToolResponseMessage.
            if (isReturnDirect(callback)) {
                String fullResult = result != null ? result : "";
                log.info("[ToolExecutor] Pre-approved tool {} is returnDirect; bypassing " +
                        "spill/truncate, broadcasting tool_direct_result ({} chars)", toolName, rawLen);
                if (directOutputs != null) {
                    directOutputs.add(new DirectToolOutput(
                            toolCall.id(), toolName, fullResult, System.currentTimeMillis()));
                }
                events.add(GraphEventPublisher.toolDirectResult(
                        toolCall.id(), toolName, fullResult));
                return new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolName, DIRECT_TOOL_PLACEHOLDER);
            }

            // Raw-first spill, inline truncate as fallback. Symmetric with the
            // non-replay path in executeSingleTool. The caller-supplied
            // conversationId scopes spill files into the per-conversation
            // directory layout. See spillRawOrTruncate javadoc for why the
            // order matters.
            result = spillRawOrTruncate(resultStorage, MAX_TOOL_RESULT_CHARS,
                    result, toolName, toolCall.id(), conversationId, workspaceBasePath);
            log.info("[ToolExecutor] Pre-approved tool {} returned {} chars{}", toolName, rawLen,
                    result != null && result.length() < rawLen ? " (now " + result.length() + " after spill/truncate)" : "");
            events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, result, true));
            return new ToolResponseMessage.ToolResponse(
                    toolCall.id(), toolName, result != null ? result : "");
        } catch (Exception e) {
            log.error("[ToolExecutor] Pre-approved tool {} failed: {}", toolName, e.getMessage());
            String safeError = isReturnDirect(callback)
                    ? "Tool execution failed (details withheld per returnDirect policy)"
                    : "Tool execution failed: " + e.getMessage();
            events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, safeError, false));
            return new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, safeError);
        }
    }

    /**
     * Backwards-compatible overload — replay spills land in a synthetic
     * {@code unknown/} conversation bucket. New callers must use the
     * {@link #executePreApproved(AssistantMessage.ToolCall, String, List, String, String)}
     * variant so spill files are correctly scoped per conversation.
     *
     * @deprecated use the 5-arg overload with explicit {@code conversationId}
     */
    @Deprecated
    public ToolResponseMessage.ToolResponse executePreApproved(
            AssistantMessage.ToolCall toolCall, String storedArguments,
            List<GraphEventPublisher.GraphEvent> events) {
        // Workspace base path is no longer carried as instance state — legacy
        // callers that don't supply one get unrestricted file access (matches
        // pre-RFC behavior when WorkspacePathGuard.basePath was null).
        return executePreApproved(toolCall, storedArguments, events, null, null);
    }

    // ==================== Phase 2: 并发执行 ====================

    private void executePreparedCalls(List<PreparedToolCall> preparedCalls,
                                       List<ToolResponseMessage.ToolResponse> allResponses,
                                       List<GraphEventPublisher.GraphEvent> events,
                                       List<DirectToolOutput> directOutputs) {
        long execStartMs = System.currentTimeMillis();
        if (!preparedCalls.isEmpty() && streamTracker != null) {
            String conversationId = preparedCalls.get(0).conversationId;
            String phase = classifyBatchPhase(preparedCalls);
            streamTracker.updatePhase(conversationId, phase);
            streamTracker.broadcastObject(conversationId, "phase", GraphEventPublisher.phase(phase, Map.of(
                    "toolCount", preparedCalls.size()
            )).data());
        }
        // 分组: 连续的 safe 工具可以并行，遇到 unsafe 工具则先等待所有 safe 完成再独占执行
        List<List<PreparedToolCall>> batches = buildExecutionBatches(preparedCalls);

        for (List<PreparedToolCall> batch : batches) {
            if (batch.size() == 1) {
                // 单个工具（safe 或 unsafe），直接执行
                PreparedToolCall pc = batch.get(0);
                ToolResponseMessage.ToolResponse response = executeSingleTool(pc, events, directOutputs);
                allResponses.set(pc.resultIndex, response);
            } else {
                // 多个 safe 工具，并行执行
                executeParallelBatch(batch, allResponses, events, directOutputs);
            }
        }

        // D-6: emit tool execution perf summary
        long toolExecMs = System.currentTimeMillis() - execStartMs;
        events.add(GraphEventPublisher.perfSummary("tool_execution", Map.of(
                "tool_exec_ms", toolExecMs,
                "tool_count", preparedCalls.size(),
                "batch_count", batches.size()
        )));
    }

    /**
     * 将 prepared calls 分成执行批次：
     * - 连续的 safe 工具组成一个并行批次
     * - unsafe 工具单独成为一个批次
     */
    private List<List<PreparedToolCall>> buildExecutionBatches(List<PreparedToolCall> preparedCalls) {
        List<List<PreparedToolCall>> batches = new ArrayList<>();
        List<PreparedToolCall> currentSafeBatch = new ArrayList<>();

        for (PreparedToolCall pc : preparedCalls) {
            if (pc.concurrencySafe) {
                currentSafeBatch.add(pc);
            } else {
                // Flush pending safe batch
                if (!currentSafeBatch.isEmpty()) {
                    batches.add(new ArrayList<>(currentSafeBatch));
                    currentSafeBatch.clear();
                }
                // Unsafe tool as solo batch
                batches.add(List.of(pc));
            }
        }
        // Flush remaining safe batch
        if (!currentSafeBatch.isEmpty()) {
            batches.add(currentSafeBatch);
        }
        return batches;
    }

    private void executeParallelBatch(List<PreparedToolCall> batch,
                                       List<ToolResponseMessage.ToolResponse> allResponses,
                                       List<GraphEventPublisher.GraphEvent> events,
                                       List<DirectToolOutput> directOutputs) {
        log.info("[ToolExecutor] Executing {} safe tools in parallel: {}",
                batch.size(), batch.stream().map(pc -> pc.toolCall.name()).toList());
        long batchStartMs = System.currentTimeMillis();

        Map<Integer, CompletableFuture<ToolResponseMessage.ToolResponse>> futures = new LinkedHashMap<>();
        for (PreparedToolCall pc : batch) {
            CompletableFuture<ToolResponseMessage.ToolResponse> future =
                    CompletableFuture.supplyAsync(() -> executeSingleTool(pc, events, directOutputs), TOOL_EXECUTOR);
            futures.put(pc.resultIndex, future);
        }

        // 等待所有并行工具完成，按原始顺序填入结果
        for (var entry : futures.entrySet()) {
            try {
                // 按工具名查找配置的超时时间
                PreparedToolCall matchedPc = batch.stream()
                        .filter(p -> p.resultIndex == entry.getKey()).findFirst().orElse(null);
                long timeoutMs = getToolTimeoutMs(matchedPc != null ? matchedPc.toolCall.name() : null);
                ToolResponseMessage.ToolResponse response = entry.getValue().get(timeoutMs, TimeUnit.MILLISECONDS);
                allResponses.set(entry.getKey(), response);
            } catch (Exception e) {
                // 超时或异常 — 填入错误响应
                PreparedToolCall pc = batch.stream()
                        .filter(p -> p.resultIndex == entry.getKey())
                        .findFirst().orElse(null);
                String toolName = pc != null ? pc.toolCall.name() : "unknown";
                String toolId = pc != null ? pc.toolCall.id() : "";
                log.error("[ToolExecutor] Parallel tool {} failed: {}", toolName, e.getMessage());
                allResponses.set(entry.getKey(), new ToolResponseMessage.ToolResponse(
                        toolId, toolName, normalizeToolExecutionError(
                        e instanceof ExecutionException ? (Exception) e.getCause() : (Exception) e)));
            }
        }
        log.info("[ToolExecutor] Parallel batch completed: {} tools in {}ms",
                batch.size(), System.currentTimeMillis() - batchStartMs);
    }

    private ToolResponseMessage.ToolResponse executeSingleTool(PreparedToolCall pc,
                                                                List<GraphEventPublisher.GraphEvent> events,
                                                                List<DirectToolOutput> directOutputs) {
        String toolName = pc.toolCall.name();
        try {
            if (streamTracker != null) {
                streamTracker.updateRunningTool(pc.conversationId, toolName);
                streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_START,
                        GraphEventPublisher.toolStart(pc.toolCall.id(), toolName, pc.arguments).data());
            }
            log.info("[ToolExecutor] Executing tool: {} with args: {}",
                    toolName, pc.arguments != null && pc.arguments.length() > 200
                            ? pc.arguments.substring(0, 200) + "..." : pc.arguments);

            // RFC-063r §2.5 / PR-1 transition window: populate BOTH the explicit
            // Spring AI ToolContext (preferred — read via ChatOrigin.from(ctx))
            // AND the legacy ToolExecutionContext ThreadLocal so tools that have
            // not yet migrated to ToolContext keep working unchanged.
            ToolExecutionContext.set(pc.conversationId, pc.requesterId, pc.workspaceBasePath);
            String result;
            try {
                ChatOrigin runtimeOrigin = pc.origin != null ? pc.origin : ChatOrigin.EMPTY;
                runtimeOrigin = runtimeOrigin
                        .withConversationId(pc.conversationId)
                        .withWorkspace(runtimeOrigin.workspaceId(), pc.workspaceBasePath);
                ToolContext toolContext = runtimeOrigin.toToolContext();
                result = pc.callback.call(pc.arguments, toolContext);
            } finally {
                ToolExecutionContext.clear();
            }

            int rawLen = result != null ? result.length() : 0;
            // RFC-052: returnDirect tools bypass spill / truncation / LLM context.
            // Their full text goes to the user verbatim and is never persisted to
            // a workspace cache file (spill could leak sensitive data).
            if (isReturnDirect(pc.callback)) {
                String fullResult = result != null ? result : "";
                log.info("[ToolExecutor] Tool {} is returnDirect; bypassing spill/truncate, " +
                        "broadcasting tool_direct_result ({} chars)", toolName, rawLen);
                directOutputs.add(new DirectToolOutput(
                        pc.toolCall.id(), toolName, fullResult, System.currentTimeMillis()));
                GraphEventPublisher.GraphEvent directEvent =
                        GraphEventPublisher.toolDirectResult(pc.toolCall.id(), toolName, fullResult);
                events.add(directEvent);
                if (streamTracker != null) {
                    streamTracker.broadcastObject(pc.conversationId,
                            GraphEventPublisher.EVENT_TOOL_DIRECT_RESULT, directEvent.data());
                    streamTracker.updateRunningTool(pc.conversationId, null);
                }
                // Placeholder keeps the tool_call_id ↔ tool_response pairing valid
                // for OpenAI-compatible providers, while withholding the data from
                // any subsequent LLM round (the graph won't take a next round —
                // see ObservationDispatcher RETURN_DIRECT_TRIGGERED branch).
                return new ToolResponseMessage.ToolResponse(
                        pc.toolCall.id(), toolName, DIRECT_TOOL_PLACEHOLDER);
            }

            // Capture SourceEvidenceLedger from the RAW result, before truncate/
            // spill compacts it. Building the ledger from the post-compact
            // response (the old ActionNode behaviour) loses any file path that
            // happens to fall outside the head/tail kept by truncateToolResult.
            // Wrapping the raw string in a synthetic ToolResponseMessage.ToolResponse
            // lets us reuse SourceEvidenceLedger.fromToolResponses verbatim — no
            // new parsing path to keep in sync.
            if (result != null && pc.rawEvidenceCollector != null) {
                SourceEvidenceLedger rawDelta = SourceEvidenceLedger.fromToolResponses(
                        java.util.List.of(new ToolResponseMessage.ToolResponse(
                                pc.toolCall.id(), toolName, result)));
                if (rawDelta.hasEvidence()) {
                    pc.rawEvidenceCollector.accumulateAndGet(rawDelta, SourceEvidenceLedger::merge);
                }
            }

            // Raw-first spill: write the full output to disk and replace
            // with preview + path so the model can call read_file for the
            // ground truth. Fall back to inline truncate only when spilling
            // is disabled, the tool is on the exclusion list, the body is
            // already under the spill threshold, or the disk write fails.
            // Truncating before spilling would persist a pre-shortened body
            // to disk and silently lose data the model could otherwise
            // recover.
            result = spillRawOrTruncate(resultStorage, MAX_TOOL_RESULT_CHARS,
                    result, toolName, pc.toolCall.id(), pc.conversationId, pc.workspaceBasePath);
            log.info("[ToolExecutor] Tool {} returned {} chars{}", toolName, rawLen,
                    result != null && result.length() < rawLen ? " (now " + result.length() + " after spill/truncate)" : "");
            events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true));
            if (streamTracker != null) {
                streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
                        GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true).data());
                streamTracker.updateRunningTool(pc.conversationId, null);
            }
            return new ToolResponseMessage.ToolResponse(
                    pc.toolCall.id(), toolName, result != null ? result : "");
        } catch (Exception e) {
            log.error("[ToolExecutor] Tool {} execution failed: {}", toolName, e.getMessage(), e);
            // RFC-052: for returnDirect tools, even the error message is
            // suspect — exception text may carry stack traces, SQL fragments,
            // or other sensitive substrings that should not enter LLM context.
            // Emit a generic placeholder instead. Full error still goes to logs
            // for operator diagnosis.
            String reportedError = isReturnDirect(pc.callback)
                    ? "Tool execution failed (details withheld per returnDirect policy)"
                    : normalizeToolExecutionError(e);
            events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false));
            if (streamTracker != null) {
                streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
                        GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false).data());
                streamTracker.updateRunningTool(pc.conversationId, null);
            }
            return new ToolResponseMessage.ToolResponse(
                    pc.toolCall.id(), toolName, reportedError);
        }
    }

    // ==================== Guard 评估 ====================

    private GuardDecision evaluateGuard(AssistantMessage.ToolCall toolCall, String toolName, String arguments,
                                         String conversationId, String agentId,
                                         List<AssistantMessage.ToolCall> allToolCalls, int currentIndex,
                                         List<GraphEventPublisher.GraphEvent> events, String requesterId) {
        ToolInvocationContext guardCtx = ToolInvocationContext.of(toolName, arguments, conversationId, agentId);

        if (toolGuardService != null) {
            GuardEvaluation evaluation = toolGuardService.evaluate(guardCtx);

            if (evaluation.shouldBlock()) {
                log.warn("[ToolExecutor] Tool call BLOCKED: tool={}, summary={}", toolName, evaluation.summary());
                events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, evaluation.summary(), false));
                return GuardDecision.blocked(
                        "[安全拦截] " + evaluation.summary() + "。请使用更安全的替代方案。");
            }

            if (evaluation.shouldRequireApproval()) {
                List<AssistantMessage.ToolCall> remaining = allToolCalls.subList(currentIndex + 1, allToolCalls.size());
                String approvalResponse = ToolExecutionGuardHelper.handleToolApproval(
                        toolCall, toolName, arguments, evaluation,
                        conversationId, agentId, requesterId, approvalService, streamTracker,
                        events, remaining);
                // Extract pendingId from response (format: "[APPROVAL_PENDING] tool=xxx awaiting user decision")
                return GuardDecision.needsApproval(approvalResponse, extractPendingId(approvalResponse));
            }
        } else if (toolGuard != null) {
            ToolGuardResult guardResult = toolGuard.check(toolName, arguments);

            if (guardResult.isBlocked()) {
                log.warn("[ToolExecutor] Tool call BLOCKED by ToolGuard: tool={}, reason={}", toolName, guardResult.reason());
                events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, guardResult.reason(), false));
                return GuardDecision.blocked(
                        "[安全拦截] " + guardResult.reason() + "。请使用更安全的替代方案。");
            }

            if (guardResult.needsApproval()) {
                List<AssistantMessage.ToolCall> remaining = allToolCalls.subList(currentIndex + 1, allToolCalls.size());
                String approvalResponse = ToolExecutionGuardHelper.handleToolApprovalLegacy(
                        toolCall, toolName, arguments, guardResult,
                        conversationId, agentId, requesterId, approvalService, streamTracker,
                        events, remaining);
                return GuardDecision.needsApproval(approvalResponse, extractPendingId(approvalResponse));
            }
        }

        return GuardDecision.allowed();
    }

    // ==================== 辅助方法 ====================

    /**
     * RFC-052: a tool is "direct" when its {@link ToolCallback#getToolMetadata()}
     * reports {@code returnDirect=true}. {@code @Tool(returnDirect=true)} maps
     * here automatically; MCP tools rely on the
     * {@code ReturnDirectMcpToolCallback} decorator to override the metadata
     * (the upstream {@code SyncMcpToolCallback} returns the framework default
     * of {@code false}).
     */
    private static boolean isReturnDirect(ToolCallback callback) {
        try {
            return callback.getToolMetadata() != null
                    && callback.getToolMetadata().returnDirect();
        } catch (Exception e) {
            log.debug("[ToolExecutor] Failed to read returnDirect metadata: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Returns true when the tool can run in parallel with other safe tools.
     * Consults the registry first (annotation-driven, populated at startup);
     * falls back to the legacy hardcoded set for callers built without a
     * registry (legacy constructor / unit tests).
     */
    private boolean isConcurrencySafe(String toolName) {
        if (concurrencyRegistry != null && concurrencyRegistry.isUnsafe(toolName)) {
            return false;
        }
        return !DEFAULT_UNSAFE_TOOLS.contains(toolName);
    }

    private String classifyBatchPhase(List<PreparedToolCall> preparedCalls) {
        boolean memoryOnly = preparedCalls.stream().allMatch(pc ->
                "read_workspace_memory_file".equals(pc.toolCall.name())
                        || "list_workspace_memory_files".equals(pc.toolCall.name()));
        return memoryOnly ? "reading_memory" : "executing_tool";
    }

    private String normalizeToolExecutionError(Exception e) {
        String message = e != null && e.getMessage() != null ? e.getMessage() : "Unknown error";
        String lower = message.toLowerCase(Locale.ROOT);

        if (lower.contains("conversion from json")
                || lower.contains("unexpected end-of-input")
                || lower.contains("unexpected character escape sequence")
                || lower.contains("json parse error")
                || lower.contains("malformed json")) {
            // Truncated tool_call args — typically from max_tokens being hit while
            // streaming a large `content` field (e.g. renderDocx with 7000+ char
            // markdown body). The fix MUST come from the model: re-emit the same
            // tool call with smaller content per call, OR split the work across
            // multiple sequential tool calls. We tell the LLM directly so the
            // next reasoning iteration knows what to do — without this, models
            // tend to fall back to narrating the result as final_answer text.
            return "Tool execution failed: your tool_call arguments JSON was truncated mid-stream "
                    + "(very likely you hit max_tokens while emitting a long content field). "
                    + "Action required: re-call the SAME tool now in your next response, but "
                    + "(1) make the content field shorter, OR (2) split the work into multiple "
                    + "sequential tool calls (e.g. write the doc in 2-3 chunks via separate calls). "
                    + "Do NOT describe the result as text — you must call the tool again to actually "
                    + "produce the output.";
        }

        if (lower.contains("access denied") && lower.contains("path outside allowed directories")) {
            // 提取目标路径和允许路径
            return "Tool execution failed: target path is outside the allowed workspace directory.";
        }

        return "Tool execution failed: " + message;
    }

    /**
     * 从 approval response 中提取 pendingId（best-effort）
     */
    private String extractPendingId(String approvalResponse) {
        // handleToolApproval 内部已经创建了 pending，这里只做标记
        return approvalResponse;
    }

    /**
     * Issue #46 — when a tool callback miss happens, check whether the
     * unrecognized name actually matches an active skill. If it does, return
     * a precise hint telling the LLM the right invocation pattern instead
     * of bare "Tool not found: X". Without this, an LLM that called e.g.
     * {@code RedisOps} as a tool gets no recovery signal and either gives
     * up or falls back to shell guessing.
     *
     * <p>Case-insensitive match because LLMs sometimes change the case of
     * skill names mid-conversation.
     */
    /**
     * Resolve the LLM-emitted tool name to a registered canonical name.
     * Tries exact match first (the hot path); on miss, normalizes the input
     * (camelCase→snake_case, lowercase, strip {@code _tool}/{@code _function}
     * suffix) and looks up the canonical equivalent. Returns the original
     * string when no match is found, so the caller's downstream "tool not
     * found" path still fires.
     */
    String resolveToolName(String requested) {
        if (requested == null || requested.isBlank()) {
            return requested;
        }
        if (toolCallbackMap.containsKey(requested)) {
            return requested;
        }
        String normalized = normalizeToolName(requested);
        String canonical = normalizedNameLookup.get(normalized);
        if (canonical != null) {
            log.info("[ToolExecutor] Tool name normalized: '{}' -> '{}' (via '{}')",
                    requested, canonical, normalized);
            return canonical;
        }
        return requested;
    }

    static String normalizeToolName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String snake = CAMEL_BOUNDARY.matcher(name).replaceAll("$1_$2");
        String collapsed = snake.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-.]+", "_")
                .replaceAll("_+", "_");
        if (collapsed.endsWith("_tool")) {
            collapsed = collapsed.substring(0, collapsed.length() - 5);
        } else if (collapsed.endsWith("_function")) {
            collapsed = collapsed.substring(0, collapsed.length() - 9);
        }
        return collapsed.replaceAll("^_+|_+$", "");
    }

    private static Map<String, String> buildNormalizedLookup(Set<String> canonicalNames) {
        Map<String, String> result = new HashMap<>(canonicalNames.size() * 2);
        for (String name : canonicalNames) {
            String norm = normalizeToolName(name);
            if (norm.isEmpty()) {
                continue;
            }
            String previous = result.putIfAbsent(norm, name);
            if (previous != null && !previous.equals(name)) {
                log.warn("[ToolExecutor] Two registered tools normalize to the same key '{}': "
                        + "'{}' and '{}' — only '{}' will resolve from mangled LLM emissions",
                        norm, previous, name, previous);
            }
        }
        return Map.copyOf(result);
    }

    private String skillAwareNotFoundMessage(String toolName) {
        if (skillRuntimeService != null && toolName != null && !toolName.isBlank()) {
            try {
                boolean isSkill = skillRuntimeService.getActiveSkills().stream()
                        .anyMatch(s -> s.getName() != null && s.getName().equalsIgnoreCase(toolName));
                if (isSkill) {
                    return String.format(
                            "'%s' is a Skill, not a Tool — calling it as a tool fails. "
                            + "To use it, FIRST call readSkillFile(skillName=\"%s\", filePath=\"SKILL.md\") "
                            + "to read its instructions, THEN follow what SKILL.md tells you "
                            + "(typically runSkillScript with a scripts/<file> path).",
                            toolName, toolName);
                }
            } catch (Exception e) {
                // Don't let a hint-side failure mask the original error.
                log.debug("[ToolExecutor] skill-aware hint check failed: {}", e.getMessage());
            }
        }
        return "Tool not found: " + toolName;
    }

    /**
     * Holder for an auto-redirect outcome: the SKILL.md content (wrapped
     * with a one-line nudge) that we substitute as the tool response when
     * the LLM mistakenly calls a skill name as if it were a tool.
     *
     * <p>{@code success=true} on the substituted response so the model
     * doesn't read it as "tool failed, try harder" — semantically we
     * answered a different question than the one it asked, and we want
     * the model to follow the redirect rather than thrash.
     */
    private record SkillRedirect(String response) {}

    /**
     * When the LLM calls a skill name as if it were a tool, transparently
     * fetch its SKILL.md and return that as the tool response. Smaller
     * models (qwen-turbo et al.) often can't act on a "not a tool — go
     * read X first" hint; they keep emitting the same wrong call until the
     * iteration cap. With auto-redirect, the model receives runnable
     * instructions on the very first attempt and can copy the runSkillScript
     * shape from SKILL.md verbatim.
     *
     * <p>Returns {@code null} if {@code toolName} isn't a registered skill,
     * if {@code readSkillFile} isn't available in this agent's tool set, or
     * if the redirect call itself errored — the caller then falls through
     * to the usual {@code skillAwareNotFoundMessage} hint.
     */
    private SkillRedirect tryAutoRedirectSkillCall(String toolName, String originalArgs, ChatOrigin origin) {
        if (skillRuntimeService == null || toolName == null || toolName.isBlank()) return null;
        try {
            boolean isSkill = skillRuntimeService.getActiveSkills().stream()
                    .anyMatch(s -> s.getName() != null && s.getName().equalsIgnoreCase(toolName));
            if (!isSkill) return null;
        } catch (Exception e) {
            log.debug("[ToolExecutor] auto-redirect skill lookup failed: {}", e.getMessage());
            return null;
        }

        ToolCallback readSkillFile = toolCallbackMap.get("readSkillFile");
        if (readSkillFile == null) {
            log.debug("[ToolExecutor] readSkillFile not bound to this agent — cannot auto-redirect '{}'", toolName);
            return null;
        }

        String redirectArgs = "{\"skillName\":\""
                + jsonStringEscape(toolName)
                + "\",\"filePath\":\"SKILL.md\"}";
        String skillMd;
        try {
            ToolContext ctx = (origin != null ? origin : ChatOrigin.EMPTY).toToolContext();
            skillMd = readSkillFile.call(redirectArgs, ctx);
        } catch (Exception e) {
            log.warn("[ToolExecutor] Auto-redirect readSkillFile failed for '{}': {}", toolName, e.getMessage());
            return null;
        }

        log.info("[ToolExecutor] Auto-redirected skill-as-tool call '{}' → readSkillFile (returned {} chars)",
                toolName, skillMd != null ? skillMd.length() : 0);

        String safeArgs = originalArgs == null || originalArgs.isBlank() ? "{}" : originalArgs;
        String response = String.format(
                "[auto-redirect] You called '%s' as a tool, but it's a Skill (documentation package). "
                + "Its SKILL.md is loaded below — read the script invocation example, then call "
                + "`runSkillScript(skillName=\"%s\", scriptPath=\"scripts/<file from SKILL.md>\", args=[...])` "
                + "to actually run it. Your original payload was: %s%n%n---%n%s",
                toolName, toolName, safeArgs, skillMd == null ? "" : skillMd);
        return new SkillRedirect(response);
    }

    /** Minimal JSON string escaping for the synthetic readSkillFile arg payload. */
    private static String jsonStringEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 内部数据类 ====================

    private record PreparedToolCall(
            AssistantMessage.ToolCall toolCall,
            ToolCallback callback,
            String arguments,
            boolean concurrencySafe,
            int resultIndex,
            String conversationId,
            String requesterId,
            String workspaceBasePath,
            ChatOrigin origin,
            /**
             * Shared reference (one per execute() invocation) where each
             * concurrent {@code executeSingleTool} merges a {@link SourceEvidenceLedger}
             * built from the **raw** tool result, before the spill/truncate
             * pipeline (Layer 2/Layer 1) shrinks the response that finally
             * reaches the LLM.
             *
             * <p>Why the raw stage: a 30 KB grep output may carry the only
             * mention of {@code ObservationNode.java}; once {@code truncateToolResult}
             * head/tail-compacts to 4 KB that line is typically dropped. If the
             * ledger is built from the compacted response (the old behaviour
             * in {@code ActionNode}), the answer's later citation of
             * {@code ObservationNode} gets flagged as evidence-insufficient
             * even though the model did see the file in its tool result.
             *
             * <p>Atomic merge via {@code AtomicReference.accumulateAndGet}
             * because parallel batches run on {@code TOOL_EXECUTOR}.
             */
            java.util.concurrent.atomic.AtomicReference<SourceEvidenceLedger> rawEvidenceCollector
    ) {}

    private record ApprovalBarrier(String pendingId, String toolName) {}

    private static final class GuardDecision {
        final boolean blocked;
        final boolean needsApproval;
        final String response;
        final String pendingId;

        private GuardDecision(boolean blocked, boolean needsApproval, String response, String pendingId) {
            this.blocked = blocked;
            this.needsApproval = needsApproval;
            this.response = response;
            this.pendingId = pendingId;
        }

        static GuardDecision allowed() { return new GuardDecision(false, false, null, null); }
        static GuardDecision blocked(String response) { return new GuardDecision(true, false, response, null); }
        static GuardDecision needsApproval(String response, String pendingId) {
            return new GuardDecision(false, true, response, pendingId);
        }
    }

    /**
     * 工具执行结果
     */
    public record ToolExecutionResult(
            /** 所有工具的响应（按原始顺序） */
            List<ToolResponseMessage.ToolResponse> responses,
            /** 执行过程中的事件 */
            List<GraphEventPublisher.GraphEvent> events,
            /** 是否有待审批的工具 */
            boolean awaitingApproval,
            /** 审批 pending ID（如果 awaitingApproval=true） */
            String pendingId,
            /** 触发审批 barrier 的工具名（如果 awaitingApproval=true） */
            String barrierToolName,
            /**
             * RFC-052: full-text outputs from any returnDirect tools that ran
             * in this batch. Non-empty list ⇒ graph must short-circuit to
             * FinalAnswerNode without re-entering the LLM.
             */
            List<DirectToolOutput> directOutputs,
            /**
             * Source evidence accumulated from the RAW (pre-spill, pre-truncate)
             * tool results in this batch. ActionNode merges this into the
             * graph-level ledger instead of re-parsing the spill-compacted
             * {@link #responses} — that compaction routinely drops the line
             * mentioning a path the model later cites, which would surface
             * as a false-positive evidence_insufficient.
             */
            SourceEvidenceLedger rawEvidenceLedger
    ) {
        /** Backwards-compatible constructor for callers that don't track direct outputs. */
        public ToolExecutionResult(List<ToolResponseMessage.ToolResponse> responses,
                                    List<GraphEventPublisher.GraphEvent> events,
                                    boolean awaitingApproval,
                                    String pendingId,
                                    String barrierToolName) {
            this(responses, events, awaitingApproval, pendingId, barrierToolName,
                    List.of(), SourceEvidenceLedger.empty());
        }

        /** Bridge for callers that pass directOutputs but predate the raw-ledger field. */
        public ToolExecutionResult(List<ToolResponseMessage.ToolResponse> responses,
                                    List<GraphEventPublisher.GraphEvent> events,
                                    boolean awaitingApproval,
                                    String pendingId,
                                    String barrierToolName,
                                    List<DirectToolOutput> directOutputs) {
            this(responses, events, awaitingApproval, pendingId, barrierToolName,
                    directOutputs, SourceEvidenceLedger.empty());
        }

        public boolean hasDirectOutputs() {
            return directOutputs != null && !directOutputs.isEmpty();
        }
    }

    /**
     * RFC-03 Lane A2 — outcome of {@link #capToolCalls(List, int)}. Holds the
     * (possibly trimmed) effective list, any synthesized truncation responses
     * that should be appended verbatim to the result, and a boolean flag so
     * the caller can decide whether to emit an audit event.
     */
    record CappedToolCalls(
            List<AssistantMessage.ToolCall> effective,
            List<ToolResponseMessage.ToolResponse> truncatedResponses,
            boolean wasTruncated
    ) {}

    /**
     * RFC-03 Lane A2 — pure helper used at the top of {@link #execute}.
     *
     * <p>Returns the input untouched when {@code calls.size() <= maxPerResponse}.
     * Otherwise:
     * <ul>
     *   <li>{@link CappedToolCalls#effective} = first {@code maxPerResponse} calls.</li>
     *   <li>{@link CappedToolCalls#truncatedResponses} = one synthetic
     *       {@link ToolResponseMessage.ToolResponse} per dropped call,
     *       so the LLM gets a paired tool response on its next turn instead
     *       of hanging on missing replies (some providers reject the request
     *       entirely if any tool_call lacks a response).</li>
     *   <li>{@link CappedToolCalls#wasTruncated} = true.</li>
     * </ul>
     *
     * <p>Package-private so {@code ToolExecutionExecutorCapToolCallsTest}
     * can drive every branch without booting a Spring context.
     */
    static CappedToolCalls capToolCalls(List<AssistantMessage.ToolCall> calls, int maxPerResponse) {
        if (calls == null || calls.size() <= maxPerResponse) {
            return new CappedToolCalls(
                    calls == null ? List.of() : calls,
                    List.of(),
                    false);
        }
        List<ToolResponseMessage.ToolResponse> truncated = new ArrayList<>(calls.size() - maxPerResponse);
        for (int i = maxPerResponse; i < calls.size(); i++) {
            AssistantMessage.ToolCall dropped = calls.get(i);
            truncated.add(new ToolResponseMessage.ToolResponse(
                    dropped.id(),
                    dropped.name(),
                    "[truncated] tool_call dropped: model returned " + calls.size()
                            + " calls in one response but executor cap is " + maxPerResponse
                            + "; please reissue the most-important calls first"));
        }
        return new CappedToolCalls(
                calls.subList(0, maxPerResponse),
                truncated,
                true);
    }
}
