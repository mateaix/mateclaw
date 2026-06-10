package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.task.AsyncTaskService;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.workspace.conversation.ConversationService;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Built-in tool: Agent delegation (multi-agent collaboration).
 * <p>
 * Two modes:
 * <ul>
 *   <li>{@link #delegateToAgent} — single-task serial delegation</li>
 *   <li>{@link #delegateParallel} — parallel delegation to up to 3 child agents simultaneously</li>
 * </ul>
 * Each delegated agent runs in an isolated child conversation (parent-child relationship is
 * persisted). Progress is relayed to the parent session via SSE events in real time.
 * Child agents have a narrowed tool set — recursive delegation and agent-discovery tools are
 * blocked.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegateAgentTool {

    private static final int MAX_DELEGATION_DEPTH = 3;
    private static final int MAX_RESULT_LENGTH = 4000;
    /**
     * Cap on children dispatched in a single delegateParallel call. Set to 8
     * because real multi-role evaluations commonly cover 5-8 perspectives
     * (architecture / backend / frontend / security / cost / ops / contrarian /
     * progressive-alternative); a lower cap forces the parent to split into
     * batches, and once an older batch's tool result gets compacted by
     * ConversationWindowManager the parent can no longer reconstruct what each
     * child said and starts re-dispatching the same roles in a loop.
     */
    private static final int MAX_PARALLEL_CHILDREN = 8;

    /**
     * RFC-03 Lane C2 — caps for the parent-context prefix when
     * {@code inheritParentContext=true} is set on {@link #delegateToAgent}.
     *
     * <p>{@link #INHERITED_CONTEXT_MAX_MESSAGES} bounds the prefix length so a
     * 1000-turn parent doesn't dump 1000 turns into the child prompt; 10 is
     * empirically enough for "what were we just talking about" without
     * blowing past typical 8k system-prompt budgets. {@link #INHERITED_CONTEXT_PER_MESSAGE_CHARS}
     * truncates each individual message — useful when the parent has a
     * long tool result or pasted document.
     */
    static final int INHERITED_CONTEXT_MAX_MESSAGES = 10;
    static final int INHERITED_CONTEXT_PER_MESSAGE_CHARS = 1000;
    /**
     * Wall-clock budget for one delegateParallel batch — applies to all children
     * together, not per child (they run concurrently on virtual threads).
     *
     * <p>Configurable via {@code mateclaw.delegation.parallel-timeout-seconds};
     * default 300 s (5 minutes). Earlier defaults (60 s → 120 s) were
     * structurally too tight for thinking models: a single LLM turn against
     * Kimi / GLM / MiniMax routinely takes 90–290 s when the child must
     * produce multi-section structured output, so the parent gave up while the
     * children were still happily streaming. 300 s matches the per-prompt
     * ceiling used by ACP delegation and keeps headroom for one tool-call
     * round trip on top of a single LLM turn.
     */
    @Value("${mateclaw.delegation.parallel-timeout-seconds:300}")
    private int parallelTimeoutSeconds;

    /**
     * Default deny list for child agents. Names are matched against the
     * canonical tool names exposed by the runtime, so they MUST mirror the
     * actual {@code @Tool}-annotated method names.
     *
     * <p>Categories:
     * <ul>
     *   <li>Recursion guards (delegate*, listAvailableAgents) — prevent a
     *       child from spawning another child or enumerating sibling agents.</li>
     *   <li>Memory writers (remember, *_structured) — children must not
     *       persist into the parent's shared MEMORY.md / SOUL.md surface;
     *       the parent owns long-term memory.</li>
     * </ul>
     *
     * <p>{@code execute_shell_command} is intentionally NOT in the default
     * deny list because legitimate dev-tooling agents rely on shell access.
     * Operators that need a stricter posture can append it via
     * {@code mateclaw.delegation.child-denied-tools}.
     */
    static final Set<String> DEFAULT_CHILD_DENIED_TOOLS = Set.of(
            // Recursion guards.
            "delegateToAgent",
            "delegateParallel",
            "listAvailableAgents",
            // Memory writes from children would pollute the parent's shared
            // long-term memory surface.
            "remember",
            "remember_structured",
            "forget_structured",
            // RFC 48 — goal ownership is bound to the parent conversation.
            // A child mutating the parent's goal would let sub-agents
            // declare the parent's goal "completed" or replace its budget.
            "setGoal",
            "addGoalCriterion",
            "completeGoal",
            "getGoalStatus",
            // Employee authoring spawns persistent agents; a delegated child
            // doing so risks recursive team creation and privilege creep, so
            // it stays with the parent (same stance as delegate* recursion
            // guards above). The read-only capability catalog is fine to keep.
            "create_employee"
    );

    /** Executor for parallel delegation — one JDK 21 virtual thread per child agent. */
    private static final ExecutorService DELEGATION_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final AgentService agentService;
    private final AgentMapper agentMapper;
    private final ChatStreamTracker streamTracker;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final SubagentRegistry subagentRegistry;
    private final AuditEventService auditEventService;
    private final AsyncTaskService asyncTaskService;

    /** Max characters of the task description persisted in {@code request_json}.
     *  Anything longer is truncated — full task is still inside the running
     *  child's conversation context. */
    private static final int ASYNC_TASK_REQUEST_MAX_CHARS = 8000;

    /** Max label length carried inside {@code request_json} and surfaced on
     *  spawn-event payloads. Picked to fit a short UI badge without wrapping. */
    private static final int ASYNC_LABEL_MAX_CHARS = 32;

    /** Default {@code block=true} wait when caller omits {@code timeoutSeconds}. */
    private static final int TASK_OUTPUT_DEFAULT_TIMEOUT_S = 30;

    /** Upper bound on {@code block=true} wait. Picked to be longer than the
     *  typical ReAct turn latency yet short enough that the parent agent
     *  doesn't burn its own LLM budget blocked on a stalled child. */
    private static final int TASK_OUTPUT_MAX_TIMEOUT_S = 120;

    /** Polling interval inside {@code block=true} wait. */
    private static final long TASK_OUTPUT_POLL_INTERVAL_MS = 500L;

    /**
     * Operator-supplied deny-list extension. Configured via
     * {@code mateclaw.delegation.child-denied-tools} as a comma-separated
     * list. Empty by default — the {@link #DEFAULT_CHILD_DENIED_TOOLS} set
     * already covers the recursion + memory cases that matter for safety.
     */
    @Value("${mateclaw.delegation.child-denied-tools:}")
    private List<String> additionalDeniedTools;

    /**
     * Effective deny list = defaults ∪ operator additions. Computed on each
     * delegation entry rather than cached because Spring applies
     * {@code @Value} after construction and we want operator overrides to
     * take effect on the next delegation, not on the next restart.
     */
    Set<String> deniedToolsForChild() {
        if (additionalDeniedTools == null || additionalDeniedTools.isEmpty()) {
            return DEFAULT_CHILD_DENIED_TOOLS;
        }
        Set<String> merged = new HashSet<>(DEFAULT_CHILD_DENIED_TOOLS);
        for (String name : additionalDeniedTools) {
            if (name != null && !name.isBlank()) {
                merged.add(name.trim());
            }
        }
        return Set.copyOf(merged);
    }

    // ==================== Single-task delegation ====================

    @vip.mate.tool.ConcurrencyUnsafe("spawns a child agent session and writes to mate_conversation; serialize to keep session graph deterministic")
    @Tool(description = """
            Delegate a task to another Agent for multi-agent collaboration. \
            Target Agent executes in an independent session and returns its final reply. \
            Parent receives real-time progress updates during execution. \
            For multiple parallel tasks, use delegateParallel instead.""")
    public String delegateToAgent(
            @ToolParam(description = "Target Agent name (exact match)") String agentName,
            @ToolParam(description = "Task description with complete context information") String task,
            // RFC-03 Lane C2 — when true, the child agent receives the recent
            // N messages from the parent conversation as a context prefix.
            // Default false (the original isolated-child behavior). Set true
            // only when the task genuinely depends on parent's recent
            // exchanges; otherwise the cleaner isolated execution is faster
            // and avoids prompt bloat.
            @ToolParam(description = "Whether the child should see recent parent conversation messages as background context. Default false. Set true ONLY when the task requires conversational continuity (e.g. 'follow up on what we just discussed').", required = false)
            Boolean inheritParentContext,
            // RFC-063r §2.5 改动点 5: parent ChatOrigin (channel binding /
            // workspace) propagates into the delegated child so a sub-agent
            // creating a cron job still binds back to the originating channel.
            // Hidden from the LLM by JsonSchemaGenerator.
            @Nullable ToolContext ctx) {

        if (agentName == null || agentName.isBlank()) {
            return "[错误] 请指定目标 Agent 名称。" + availableAgentsHint();
        }
        if (task == null || task.isBlank()) {
            return "[错误] 请提供任务描述。";
        }

        int depth = DelegationContext.currentDepth();
        if (depth >= MAX_DELEGATION_DEPTH) {
            return "[错误] 委派层级已达上限（" + MAX_DELEGATION_DEPTH + " 层），请直接处理任务。";
        }

        AgentEntity target = findAgent(agentName);
        if (target == null) {
            return "[错误] 未找到名为「" + agentName + "」的已启用 Agent。" + availableAgentsHint();
        }

        String parentConversationId = resolveParentConversationId();
        // Root (human-facing) conversation at the top of the delegation tree.
        // At depth 0 the immediate parent IS the root; deeper layers carry it
        // forward via DelegationContext so events reach the stream the user sees.
        String rootConversationId = DelegationContext.rootConversationId();
        if (rootConversationId == null) rootConversationId = parentConversationId;
        String parentSubagentId = DelegationContext.currentSubagentId();
        int childDepth = depth + 1;

        // Spawn-pause: short-circuit before creating child state when either the
        // immediate parent or the root tree is paused, so no conversation rows /
        // relays / registry entries leak.
        if ((parentConversationId != null && subagentRegistry.isSpawnPaused(parentConversationId))
                || (rootConversationId != null && subagentRegistry.isSpawnPaused(rootConversationId))) {
            return "[错误] Spawning paused for this conversation; resume via /api/v1/subagents/spawn-pause";
        }

        String childConversationId = createChildConv(target, parentConversationId);

        // RFC-03 Lane C2: optionally prepend a parent-context prefix to the task.
        // Bounded at INHERITED_CONTEXT_MAX_MESSAGES messages * INHERITED_CONTEXT_PER_MESSAGE_CHARS
        // chars so a chatty parent doesn't blow past the child's context window.
        String taskWithContext = task;
        if (Boolean.TRUE.equals(inheritParentContext) && parentConversationId != null) {
            String prefix = buildInheritedContextPrefix(parentConversationId);
            if (!prefix.isEmpty()) {
                taskWithContext = prefix + "\n\n---\n\nYour task:\n" + task;
                log.info("Inheriting parent context: parentConv={}, prefixChars={}",
                        parentConversationId, prefix.length());
            }
        }

        log.info("Agent delegation: depth={}, target={}({}), childConv={}, parentConv={}",
                depth + 1, target.getName(), target.getId(), childConversationId, parentConversationId);

        // Register the live sub-agent first so its stable id rides on every
        // event. Disposable is null in the synchronous single-task path because
        // the executor blocks on AgentService#chat directly — there is no Flux
        // subscription to dispose. Interrupts here are best-effort (status flip).
        String subagentId = parentConversationId != null
                ? subagentRegistry.register(parentConversationId, childConversationId,
                        target.getId(), task, null, parentSubagentId, childDepth, rootConversationId)
                : null;

        // Broadcast to the ROOT conversation (not the immediate parent) so a
        // grandchild's progress reaches the stream the user is watching. Every
        // event carries subagentId/parentSubagentId/depth for tree rebuild.
        boolean hasRoot = rootConversationId != null && streamTracker.isRunning(rootConversationId);
        if (hasRoot) {
            Map<String, Object> startEvent = delegationPayload(subagentId, parentSubagentId, childDepth,
                    childConversationId, target.getName());
            startEvent.put("task", truncate(task, 200));
            streamTracker.broadcastObject(rootConversationId, "delegation_start", startEvent);
        }
        Runnable stopRelay = hasRoot
                ? registerBatchedRelay(childConversationId, rootConversationId, target.getName(),
                        subagentId, parentSubagentId, childDepth)
                : null;

        // Execute child agent — RFC-063r §2.5 改动点 5: inherit the parent
        // ChatOrigin and only swap the agentId, so channel binding /
        // workspace / requester all flow into the child.
        ChatOrigin parentOrigin = ChatOrigin.from(ctx);
        ChildResult result;
        try {
            result = runSingleChild(0, target, taskWithContext, parentConversationId, childConversationId,
                    parentOrigin, rootConversationId, subagentId, childDepth);
        } finally {
            // Cleanup relay + registry regardless of how the child returned
            // (success / exception / interruption) so we never leak entries.
            if (stopRelay != null) stopRelay.run();
            if (subagentId != null) {
                subagentRegistry.get(subagentId).ifPresent(rec -> {
                    if ("running".equals(rec.status().get())) {
                        rec.status().set("completed");
                    }
                });
                subagentRegistry.unregister(subagentId);
            }
        }
        if (hasRoot) {
            broadcastEnd(rootConversationId, childConversationId, target.getName(), result,
                    subagentId, parentSubagentId, childDepth);
        }

        return result.toToolResponse(target.getName());
    }

    // ==================== Parallel delegation ====================

    @vip.mate.tool.ConcurrencyUnsafe("internally fans out to its own thread pool; outer executor must not double-parallelize")
    @Tool(description = """
            Delegate multiple tasks to different Agents in parallel (max 3). \
            Each task runs concurrently in an independent child session. \
            Use this when you have multiple independent sub-tasks that can run simultaneously. \
            Input is a JSON array: [{"agentName":"Agent名称","task":"任务描述"}, ...]""")
    public String delegateParallel(
            @ToolParam(description = "JSON array of tasks: [{\"agentName\":\"X\",\"task\":\"Y\"}, ...]")
            String tasksJson,
            // RFC-063r §2.5 改动点 5: hidden from LLM, used to inherit ChatOrigin into children.
            @Nullable ToolContext ctx) {

        // 1. Parse task list
        List<Map<String, String>> tasks;
        try {
            tasks = objectMapper.readValue(tasksJson, new TypeReference<>() {});
        } catch (Exception e) {
            return "[错误] 无法解析任务 JSON：" + e.getMessage() + "\n格式: [{\"agentName\":\"X\",\"task\":\"Y\"}]";
        }

        if (tasks == null || tasks.isEmpty()) {
            return "[错误] 任务列表为空。";
        }
        if (tasks.size() > MAX_PARALLEL_CHILDREN) {
            return "[错误] 最多支持 " + MAX_PARALLEL_CHILDREN + " 个并行任务，当前 " + tasks.size() + " 个。";
        }

        int depth = DelegationContext.currentDepth();
        if (depth >= MAX_DELEGATION_DEPTH) {
            return "[错误] 委派层级已达上限（" + MAX_DELEGATION_DEPTH + " 层），请直接处理任务。";
        }

        String parentConversationId = resolveParentConversationId();
        String rootConversationId = DelegationContext.rootConversationId();
        if (rootConversationId == null) rootConversationId = parentConversationId;
        final String rootConvFinal = rootConversationId;
        final String parentSubagentId = DelegationContext.currentSubagentId();
        final int childDepth = depth + 1;

        // Spawn-pause: short-circuit before allocating any per-child state so
        // we don't leak conversation rows / relays / registry entries when an
        // operator paused this conversation's tree (immediate parent or root).
        if ((parentConversationId != null && subagentRegistry.isSpawnPaused(parentConversationId))
                || (rootConvFinal != null && subagentRegistry.isSpawnPaused(rootConvFinal))) {
            return "[错误] Spawning paused for this conversation; resume via /api/v1/subagents/spawn-pause";
        }

        boolean hasRoot = rootConvFinal != null && streamTracker.isRunning(rootConvFinal);

        // 2. Main thread: validate agents, create child conversations, register relays
        record PreparedChild(int index, AgentEntity agent, String task, String childConvId,
                             Runnable stopRelay, String subagentId) {}
        List<PreparedChild> prepared = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i++) {
            Map<String, String> t = tasks.get(i);
            String agentName = t.get("agentName");
            String task = t.get("task");

            if (agentName == null || agentName.isBlank() || task == null || task.isBlank()) {
                errors.add("[任务 " + (i + 1) + "] agentName 或 task 为空");
                continue;
            }

            AgentEntity agent = findAgent(agentName);
            if (agent == null) {
                errors.add("[任务 " + (i + 1) + "] 未找到 Agent「" + agentName + "」");
                continue;
            }

            String childConvId = createChildConv(agent, parentConversationId);
            String subagentId = parentConversationId != null
                    ? subagentRegistry.register(parentConversationId, childConvId,
                            agent.getId(), task, null, parentSubagentId, childDepth, rootConvFinal)
                    : null;
            Runnable stopRelay = hasRoot
                    ? registerBatchedRelay(childConvId, rootConvFinal, agent.getName(),
                            subagentId, parentSubagentId, childDepth)
                    : null;
            prepared.add(new PreparedChild(i, agent, task, childConvId, stopRelay, subagentId));
        }

        if (prepared.isEmpty()) {
            return "[错误] 所有任务校验失败：\n" + String.join("\n", errors);
        }

        log.info("Parallel delegation: {} tasks, parentConv={}", prepared.size(), parentConversationId);

        // 3. Broadcast delegation_start (parallel mode) to the root conversation
        if (hasRoot) {
            List<Map<String, Object>> childrenInfo = prepared.stream().map(p -> {
                Map<String, Object> m = delegationPayload(p.subagentId, parentSubagentId, childDepth,
                        p.childConvId, p.agent.getName());
                m.put("task", truncate(p.task, 100));
                return m;
            }).toList();
            streamTracker.broadcastObject(rootConvFinal, "delegation_start", Map.of(
                    "parallel", true,
                    "children", childrenInfo));
        }

        // 4. Fan out — execute children in parallel
        long startTime = System.currentTimeMillis();
        Map<Integer, CompletableFuture<ChildResult>> futures = new LinkedHashMap<>();

        // RFC-063r §2.5 改动点 5: capture parent origin once on this thread,
        // then hand it to each child future — the worker virtual threads
        // can't re-read the ToolContext (no parameter scope), so we close
        // over the captured origin.
        ChatOrigin parentOriginParallel = ChatOrigin.from(ctx);
        for (PreparedChild p : prepared) {
            CompletableFuture<ChildResult> future = CompletableFuture.supplyAsync(
                    () -> runSingleChild(p.index, p.agent, p.task, parentConversationId, p.childConvId,
                            parentOriginParallel, rootConvFinal, p.subagentId, childDepth),
                    DELEGATION_EXECUTOR);

            // Broadcast per-child completion as soon as each child finishes
            // — frontend can update that child's status without waiting for all children.
            // Guard: skip CancellationException (fired when the timeout loop calls cancel(true))
            // because the timeout result is already handled in the collection loop below and
            // emitting here first would race-replace the correct "timeout" error before delegation_end
            // has a chance to patch remaining running segments.
            if (hasRoot) {
                future.whenComplete((result, ex) -> {
                    if (ex instanceof java.util.concurrent.CancellationException) return;
                    if (!streamTracker.isRunning(rootConvFinal)) return;
                    ChildResult r = (result != null) ? result
                            : ChildResult.ofError(p.index, p.agent.getName(),
                                    ex != null ? ex.getMessage() : "Unknown error");
                    Map<String, Object> payload = delegationPayload(p.subagentId, parentSubagentId, childDepth,
                            p.childConvId, r.agentName);
                    payload.put("taskIndex", r.taskIndex);
                    payload.put("success", r.success);
                    payload.put("outcome", r.outcome);
                    payload.put("rawLength", r.rawLength);
                    payload.put("trimmedLength", r.trimmedLength);
                    payload.put("blank", r.isBlank());
                    payload.put("durationMs", r.durationMs);
                    payload.put("resultPreview", r.success
                            ? truncate(r.result, 400)
                            : (r.error != null ? r.error : "error"));
                    streamTracker.broadcastObject(rootConvFinal, "delegation_child_complete", payload);
                });
            }

            futures.put(p.index, future);
        }

        // 5. Wait for all children (with timeout)
        List<ChildResult> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(parallelTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Parallel delegation timed out ({}s), collecting completed results", parallelTimeoutSeconds);
        } catch (Exception e) {
            log.error("Parallel delegation error: {}", e.getMessage());
        }

        // Collect results — completed futures get their value; unfinished ones are cancelled and recorded as timeout
        for (var entry : futures.entrySet()) {
            int idx = entry.getKey();
            CompletableFuture<ChildResult> f = entry.getValue();
            PreparedChild p = prepared.stream().filter(pp -> pp.index == idx).findFirst().orElse(null);
            String agentName = p != null ? p.agent.getName() : "Unknown";

            if (f.isDone() && !f.isCompletedExceptionally()) {
                try {
                    results.add(f.get());
                } catch (Exception ex) {
                    results.add(ChildResult.ofError(idx, agentName, ex.getMessage()));
                }
            } else {
                // Signal the child's graph to bail out at its next checkpoint.
                // CompletableFuture.cancel alone only marks the future as
                // cancelled; without requestStop the underlying ReAct/Plan-Execute
                // loop keeps invoking LLMs and tools (observed: 8-minute orphan
                // child still writing files long after the parent gave up).
                if (p != null && p.childConvId != null) {
                    streamTracker.requestStop(p.childConvId);
                }
                f.cancel(true);
                // Use ofTimeout so outcome="timeout" is explicit and distinct from "error".
                results.add(ChildResult.ofTimeout(idx, agentName, parallelTimeoutSeconds));
            }
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        // 6. Stop all relays + drain registry entries. Both must run for every
        // prepared child regardless of whether the future succeeded, timed
        // out, or threw — otherwise the registry leaks one entry per stuck
        // child until the JVM restarts.
        for (PreparedChild p : prepared) {
            if (p.stopRelay != null) p.stopRelay.run();
            if (p.subagentId != null) {
                subagentRegistry.get(p.subagentId).ifPresent(rec -> {
                    if ("running".equals(rec.status().get())) {
                        rec.status().set("completed");
                    }
                });
                subagentRegistry.unregister(p.subagentId);
            }
        }

        // 7. Broadcast delegation_end with per-child structured summary
        if (hasRoot) {
            List<Map<String, Object>> childResults = results.stream().map(r -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("taskIndex", r.taskIndex);
                m.put("agentName", r.agentName);
                m.put("success", r.success);
                m.put("outcome", r.outcome);           // "success"|"blank_success"|"timeout"|"error"
                m.put("rawLength", r.rawLength);        // chars before truncation
                m.put("trimmedLength", r.trimmedLength);
                m.put("blank", r.isBlank());
                m.put("durationMs", r.durationMs);
                // childConversationId + subagentId for stable frontend tree lookup
                prepared.stream()
                        .filter(p -> p.index == r.taskIndex)
                        .findFirst()
                        .ifPresent(p -> {
                            m.put("childConversationId", p.childConvId);
                            if (p.subagentId != null) m.put("subagentId", p.subagentId);
                        });
                if (!r.success && r.error != null) m.put("error", r.error);
                return m;
            }).toList();
            streamTracker.broadcastObject(rootConvFinal, "delegation_end", Map.of(
                    "parallel", true,
                    "totalDurationMs", totalDurationMs,
                    "success", results.stream().allMatch(r -> r.success),
                    "completedCount", results.stream().filter(r -> r.success).count(),
                    "blankCount", results.stream().filter(ChildResult::isBlank).count(),
                    "totalCount", results.size(),
                    "childResults", childResults));
        }

        // 8. Build return text — structured so the parent LLM cannot misread current results
        //    using memory of past timeouts. The machine-readable header line is the source of truth.
        results.sort(Comparator.comparingInt(r -> r.taskIndex));
        long successCount = results.stream().filter(r -> r.success && !r.isBlank()).count();
        long blankCount = results.stream().filter(ChildResult::isBlank).count();
        long timeoutCount = results.stream().filter(r -> "timeout".equals(r.outcome)).count();
        long errorCount = results.stream().filter(r -> "error".equals(r.outcome)).count();

        StringBuilder sb = new StringBuilder();

        // Machine-readable summary line (highest priority, appears first).
        // Explicit blank/timeout/error counts prevent the parent agent from misreading a
        // successful run as a timeout even when historical memory says "this agent often times out".
        sb.append("[PARALLEL_DELEGATION_RESULT]")
          .append(" total=").append(results.size())
          .append(" success=").append(successCount)
          .append(" blank_success=").append(blankCount)
          .append(" timeout=").append(timeoutCount)
          .append(" error=").append(errorCount)
          .append(" durationMs=").append(totalDurationMs)
          .append("\n\n");

        // Important: this result is from the current execution. Any timeout entries in the
        // conversation history were from previous runs and must not be applied to this result.
        sb.append("⚠ 注意：本次结果基于当前执行，与历史对话中出现的超时记录无关。\n\n");

        if (!errors.isEmpty()) {
            sb.append("⚠️ 部分任务未执行（Agent 未找到或参数错误）：\n");
            errors.forEach(e -> sb.append("  ").append(e).append("\n"));
            sb.append("\n");
        }

        sb.append("## 各子任务执行结果\n\n");
        for (ChildResult r : results) {
            sb.append("### [任务 ").append(r.taskIndex + 1).append("] ").append(r.agentName).append("\n");
            // Per-row machine-readable status — impossible to confuse with a different outcome
            sb.append("outcome=").append(r.outcome)
              .append(" | contentLength=").append(r.trimmedLength).append("chars")
              .append(" | rawLength=").append(r.rawLength).append("chars")
              .append(" | duration=").append(r.durationMs / 1000).append("s")
              .append("\n\n");

            switch (r.outcome) {
                case "success" -> {
                    sb.append("✅ 执行成功，有实质内容（").append(r.trimmedLength).append(" 字符）\n\n");
                    sb.append(r.result);
                }
                case "blank_success" -> {
                    sb.append("⚠ 执行成功，但返回内容为空（rawLength=").append(r.rawLength)
                      .append("，trim 后 0 字符）。请勿将此误报为超时或失败——子 Agent 已正常完成，只是本次无输出。\n");
                }
                case "timeout" ->
                    sb.append("❌ 超时（").append(parallelTimeoutSeconds).append("s 内未返回）\n");
                default ->
                    sb.append("❌ 失败：").append(r.error).append("\n");
            }
            sb.append("\n");
        }
        return truncate(sb.toString(), MAX_RESULT_LENGTH * 2); // 并行结果允许更长
    }

    // ==================== Async (detached) delegation ====================

    @Tool(description = """
            Delegate a task to another agent asynchronously and return a task_id immediately. \
            Parent continues reasoning while child runs in background. \
            Use task_output(task_id) in a later turn to retrieve the result. \
            Best for long-running sub-tasks (research, file processing) where the parent has \
            other work to do in parallel. For quick tasks where you need the answer immediately, \
            use delegateToAgent instead.""")
    public String delegateAsync(
            @ToolParam(description = "Target Agent name (exact match)") String agentName,
            @ToolParam(description = "Task description with complete context information") String task,
            @ToolParam(description = "Optional short label (≤ 32 chars) for human tracking on the UI badge",
                    required = false) String label,
            @Nullable ToolContext ctx) {

        if (agentName == null || agentName.isBlank()) {
            return errorJson("agentName 不能为空");
        }
        if (task == null || task.isBlank()) {
            return errorJson("task 不能为空");
        }
        String safeLabel = label == null ? "" :
                (label.length() > ASYNC_LABEL_MAX_CHARS ? label.substring(0, ASYNC_LABEL_MAX_CHARS) : label);

        int depth = DelegationContext.currentDepth();
        if (depth >= MAX_DELEGATION_DEPTH) {
            return errorJson("Delegation depth exceeded (max " + MAX_DELEGATION_DEPTH + ")");
        }

        AgentEntity target = findAgent(agentName);
        if (target == null) {
            return errorJson("Agent not found: " + agentName);
        }

        String parentConversationId = resolveParentConversationId();
        if (parentConversationId == null || parentConversationId.isBlank()) {
            return errorJson("delegateAsync requires a parent conversation context");
        }
        String rootConversationId = DelegationContext.rootConversationId();
        if (rootConversationId == null) rootConversationId = parentConversationId;
        String parentSubagentId = DelegationContext.currentSubagentId();
        int childDepth = depth + 1;
        if (subagentRegistry.isSpawnPaused(parentConversationId)
                || subagentRegistry.isSpawnPaused(rootConversationId)) {
            return errorJson("Spawning paused for this conversation; resume via /api/v1/subagents/spawn-pause");
        }

        // Capture origin / user on the calling thread — the Callable runs on
        // AsyncTaskService.pollExecutor, where the ToolContext ThreadLocal is
        // not visible. The child's identity (agentId) is swapped in below;
        // channel / workspace / requester all propagate via the closure.
        ChatOrigin parentOrigin = ChatOrigin.from(ctx);
        String currentUser = parentOrigin != null && parentOrigin.requesterId() != null
                && !parentOrigin.requesterId().isBlank()
                ? parentOrigin.requesterId()
                : "system";

        String childConversationId = createChildConv(target, parentConversationId);

        // Register first so the subagentId + tree identity can be persisted into
        // the task payload; the registry is process-local, but the request_json
        // is the durable record that task_output authorizes against.
        String subagentId = subagentRegistry.register(parentConversationId, childConversationId,
                target.getId(), task, null, parentSubagentId, childDepth, rootConversationId);
        final String rootConvAsync = rootConversationId;

        String requestJson;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("parentConversationId", parentConversationId);
            payload.put("rootConversationId", rootConversationId);
            payload.put("childConversationId", childConversationId);
            payload.put("childAgentId", target.getId());
            payload.put("subagentId", subagentId);
            if (parentSubagentId != null) payload.put("parentSubagentId", parentSubagentId);
            payload.put("depth", childDepth);
            payload.put("task", truncate(task, ASYNC_TASK_REQUEST_MAX_CHARS));
            payload.put("label", safeLabel);
            requestJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            subagentRegistry.unregister(subagentId);
            return errorJson("Failed to serialize task payload: " + e.getMessage());
        }

        AsyncTaskEntity entity;
        try {
            entity = asyncTaskService.submitOneShot(
                    "agent_delegate",
                    parentConversationId,
                    null,
                    requestJson,
                    currentUser,
                    () -> {
                        try {
                            ChildResult childResult = runSingleChild(0, target, task,
                                    parentConversationId, childConversationId, parentOrigin,
                                    rootConvAsync, subagentId, childDepth);
                            return childResult.toToolResponse(target.getName());
                        } finally {
                            subagentRegistry.get(subagentId).ifPresent(rec -> {
                                if ("running".equals(rec.status().get())) {
                                    rec.status().set("completed");
                                }
                            });
                            subagentRegistry.unregister(subagentId);
                        }
                    });
        } catch (IllegalStateException e) {
            // Per-user concurrency cap hit inside AsyncTaskService#createTask.
            // Roll back the registry entry so it doesn't dangle.
            subagentRegistry.unregister(subagentId);
            return errorJson(e.getMessage());
        } catch (Exception e) {
            subagentRegistry.unregister(subagentId);
            log.error("delegateAsync submit failed: target={}, err={}", target.getName(), e.getMessage());
            return errorJson("Failed to spawn async task: " + e.getMessage());
        }

        log.info("Async delegation spawned: taskId={}, target={}({}), childConv={}, parentConv={}",
                entity.getTaskId(), target.getName(), target.getId(),
                childConversationId, parentConversationId);

        if (streamTracker.isRunning(rootConvAsync)) {
            Map<String, Object> spawnEvent = delegationPayload(subagentId, parentSubagentId, childDepth,
                    childConversationId, target.getName());
            spawnEvent.put("taskId", entity.getTaskId());
            spawnEvent.put("label", safeLabel);
            spawnEvent.put("task", truncate(task, 200));
            streamTracker.broadcastObject(rootConvAsync, "delegation_async_spawned", spawnEvent);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", entity.getTaskId());
        result.put("child_conversation_id", childConversationId);
        result.put("agent_name", target.getName());
        result.put("status", "running");
        result.put("hint", "Call task_output(task_id) in a later turn to retrieve the result.");
        if (!safeLabel.isEmpty()) {
            result.put("label", safeLabel);
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return errorJson("Failed to serialize response: " + e.getMessage());
        }
    }

    @Tool(description = """
            Retrieve the result of a previously spawned async sub-agent task. \
            Returns the final reply when completed, or a status indicator if still running. \
            Set block=true to wait up to timeout seconds for completion.""")
    public String taskOutput(
            @ToolParam(description = "task_id returned by delegateAsync") String taskId,
            @ToolParam(description = "Whether to block until done or timeout. Default false.",
                    required = false) Boolean block,
            @ToolParam(description = "Max seconds to wait when block=true. Default 30, max 120.",
                    required = false) Integer timeoutSeconds,
            @Nullable ToolContext ctx) {

        if (taskId == null || taskId.isBlank()) {
            return errorJson("taskId 不能为空");
        }
        String trimmedTaskId = taskId.trim();

        AsyncTaskEntity entity = asyncTaskService.findEntityByTaskId(trimmedTaskId);
        if (entity == null) {
            return errorJson("Task not found: " + trimmedTaskId);
        }
        if (!"agent_delegate".equals(entity.getTaskType())) {
            return errorJson("Task is not a delegate task: " + trimmedTaskId);
        }

        // Attribution gate — registry is live-only, so the persistent
        // request_json + created_by columns are the only authoritative
        // sources. Both must match the calling context; otherwise this is a
        // cross-user or cross-conversation lookup and must be denied even
        // for an already-succeeded task (otherwise a stranger can read the
        // result by guessing taskIds).
        //
        // Caveat on the user gate: when ChatOrigin.requesterId is empty,
        // delegateAsync stamps the task with the literal sentinel "system"
        // (mirrors the existing channel/cron-originated flow). All callers
        // that share that sentinel — e.g. two cron jobs in the same
        // workspace — therefore satisfy the user gate against each other.
        // The conversation gate above still narrows it to "the same parent
        // conversation as the spawn", which keeps the blast radius bounded;
        // a follow-up that surfaces a stable per-channel / per-cron caller
        // identity into ChatOrigin.requesterId would close this gap.
        String taskParentConv;
        String taskRootConv;
        try {
            JsonNode req = entity.getRequestJson() == null
                    ? null
                    : objectMapper.readTree(entity.getRequestJson());
            taskParentConv = req == null ? "" : req.path("parentConversationId").asText("");
            taskRootConv = req == null ? "" : req.path("rootConversationId").asText("");
        } catch (Exception e) {
            return errorJson("Failed to parse task payload: " + e.getMessage());
        }
        String currentParentConv = resolveParentConversationId();
        ChatOrigin origin = ChatOrigin.from(ctx);
        String currentUser = origin != null ? origin.requesterId() : null;

        // Authorize the caller against EITHER the immediate spawn conversation or
        // the root of its delegation tree. The latter lets a root agent poll a
        // task that one of its (sub)children spawned: the child stamped its own
        // conversation as parentConversationId, but rootConversationId points
        // back at the user-facing conversation the root agent runs in.
        boolean convOk = currentParentConv != null
                && ((!taskParentConv.isEmpty() && taskParentConv.equals(currentParentConv))
                    || (!taskRootConv.isEmpty() && taskRootConv.equals(currentParentConv)));
        if (!convOk) {
            return errorJson("Forbidden: task does not belong to current conversation");
        }
        if (entity.getCreatedBy() == null || currentUser == null
                || currentUser.isBlank()
                || !entity.getCreatedBy().equals(currentUser)) {
            return errorJson("Forbidden: task does not belong to current user");
        }

        String status = entity.getStatus();
        boolean isTerminal = "succeeded".equals(status) || "failed".equals(status);
        if (Boolean.TRUE.equals(block) && !isTerminal) {
            int waitSec = Math.min(TASK_OUTPUT_MAX_TIMEOUT_S,
                    Math.max(1, Optional.ofNullable(timeoutSeconds).orElse(TASK_OUTPUT_DEFAULT_TIMEOUT_S)));
            long deadline = System.currentTimeMillis() + waitSec * 1000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(TASK_OUTPUT_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                AsyncTaskEntity refreshed = asyncTaskService.findEntityByTaskId(trimmedTaskId);
                if (refreshed == null) break;
                entity = refreshed;
                status = entity.getStatus();
                if ("succeeded".equals(status) || "failed".equals(status)) break;
            }
        }

        if (streamTracker.isRunning(currentParentConv)) {
            streamTracker.broadcastObject(currentParentConv, "delegation_async_polled", Map.of(
                    "taskId", trimmedTaskId,
                    "status", status));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", trimmedTaskId);
        result.put("status", status);
        switch (status == null ? "" : status) {
            case "pending", "running" -> {
                result.put("progress", entity.getProgress());
                result.put("hint", "Try again later or call task_output with block=true.");
            }
            case "succeeded" -> {
                result.put("result", entity.getResultJson());
                result.put("duration_ms", durationMs(entity));
            }
            case "failed" -> {
                result.put("error", entity.getErrorMessage());
                result.put("duration_ms", durationMs(entity));
            }
            default -> result.put("error", "Unknown status: " + status);
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return errorJson("Failed to serialize response: " + e.getMessage());
        }
    }

    /** Build a one-line JSON error envelope for tool returns. Kept distinct
     *  from {@link #truncate} / plain-text errors used by sync delegate paths
     *  so the model sees a consistent shape for async results. */
    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "error", true,
                    "message", message != null ? message : ""));
        } catch (Exception e) {
            // Fallback — never throw from an error helper.
            return "{\"error\":true,\"message\":\"" + (message == null ? "" : message.replace("\"", "\\\"")) + "\"}";
        }
    }

    /** Walltime estimate using the create/update timestamps written by
     *  {@code AsyncTaskService}. Returns 0 when either timestamp is missing. */
    private static long durationMs(AsyncTaskEntity entity) {
        if (entity == null || entity.getCreateTime() == null || entity.getUpdateTime() == null) return 0L;
        return Duration.between(entity.getCreateTime(), entity.getUpdateTime()).toMillis();
    }

    // ==================== Child agent execution (shared by single and parallel paths) ====================

    /**
     * Runs a single child agent. Sets up {@link DelegationContext} independently per virtual thread
     * so that parallel children do not share ThreadLocal state.
     * <p>
     * Raw result length must be measured <em>before</em> calling {@code truncate()}, otherwise
     * {@link ChildResult#rawLength} and {@link ChildResult#trimmedLength} would always reflect the
     * truncated length, making "blank_success" detection unreliable.
     */
    private ChildResult runSingleChild(int taskIndex, AgentEntity target, String task,
                                        String parentConversationId, String childConversationId,
                                        ChatOrigin parentOrigin,
                                        String rootConversationId, String subagentId, int childDepth) {
        boolean relayChildEvents = parentConversationId != null && streamTracker.isRunning(parentConversationId);
        if (relayChildEvents) {
            streamTracker.register(childConversationId);
            streamTracker.incrementFlux(childConversationId);
        }
        // Carry root conversation + this child's subagentId into the context so
        // a grandchild broadcasts to the root stream and tags this as its parent.
        // Pass the real tree depth so the gate survives the executor-thread hop:
        // async/parallel children run with an empty ThreadLocal stack.
        DelegationContext.enter(parentConversationId, deniedToolsForChild(),
                rootConversationId, subagentId, childDepth);
        try {
            long startTime = System.currentTimeMillis();
            // RFC-063r §2.5 改动点 5: inherit parent origin, swap agentId
            // so child reads correct identity from ToolContext while keeping
            // channelId / channelTarget / workspace context intact.
            ChatOrigin childOrigin = (parentOrigin != null ? parentOrigin : ChatOrigin.EMPTY)
                    .withAgent(target.getId())
                    .withConversationId(childConversationId);
            String rawResult = agentService.chat(target.getId(), task, childConversationId, childOrigin);
            long durationMs = System.currentTimeMillis() - startTime;
            // Measure lengths before truncation so ChildResult carries accurate metadata.
            return ChildResult.ofSuccess(taskIndex, target.getName(), rawResult, durationMs,
                    MAX_RESULT_LENGTH);
        } catch (Exception e) {
            log.error("Child agent failed: taskIndex={}, agent={}, error={}",
                    taskIndex, target.getName(), e.getMessage());
            return ChildResult.ofError(taskIndex, target.getName(), e.getMessage());
        } finally {
            if (relayChildEvents) {
                streamTracker.complete(childConversationId);
            }
            DelegationContext.exit();
        }
    }

    /**
     * Result carrier for a single child agent execution.
     *
     * <p>{@code outcome} values:
     * <ul>
     *   <li>{@code "success"} — completed successfully with non-empty content (trimmedLength > 0)</li>
     *   <li>{@code "blank_success"} — completed successfully but returned empty content (trimmedLength == 0)</li>
     *   <li>{@code "timeout"} — did not complete within the parallel wait window</li>
     *   <li>{@code "error"} — threw an exception during execution</li>
     * </ul>
     *
     * <p>{@code rawLength} and {@code trimmedLength} are measured before truncation and reflect the
     * true content length.
     */
    private record ChildResult(
            int taskIndex, String agentName, boolean success,
            String result, String error, long durationMs,
            /** "success" | "blank_success" | "timeout" | "error" */
            String outcome,
            int rawLength, int trimmedLength) {

        /** Whether the child returned no usable content (blank_success). */
        boolean isBlank() { return "blank_success".equals(outcome); }

        /**
         * Factory for a successful child execution.
         * Measures lengths from the raw result before applying the truncation limit.
         */
        static ChildResult ofSuccess(int idx, String name, String rawResult, long ms, int maxLen) {
            String safe = rawResult != null ? rawResult : "";
            String trimmed = safe.trim();
            boolean blank = trimmed.isEmpty();
            return new ChildResult(
                    idx, name, true,
                    truncate(safe, maxLen),
                    null, ms,
                    blank ? "blank_success" : "success",
                    safe.length(), trimmed.length());
        }

        /**
         * Factory for a child that failed (exception or timeout).
         * Detects timeout by inspecting the error message so callers don't need to branch.
         */
        static ChildResult ofError(int idx, String name, String err) {
            String msg = err != null ? err : "Unknown error";
            boolean isTimeout = msg.contains("超时") || msg.toLowerCase().contains("timeout");
            return new ChildResult(idx, name, false, null, msg, 0,
                    isTimeout ? "timeout" : "error", 0, 0);
        }

        /** Factory for an explicit timeout (parallel window exceeded). */
        static ChildResult ofTimeout(int idx, String name, int timeoutSec) {
            String msg = "超时 (" + timeoutSec + "s)";
            return new ChildResult(idx, name, false, null, msg, (long) timeoutSec * 1000L,
                    "timeout", 0, 0);
        }

        // Legacy shims — kept for callers that pre-date the factory methods
        static ChildResult success(int idx, String name, String result, long ms) {
            // result may already be truncated at call site — lengths will be approximate
            String safe = result != null ? result : "";
            String trimmed = safe.trim();
            boolean blank = trimmed.isEmpty();
            return new ChildResult(idx, name, true, safe, null, ms,
                    blank ? "blank_success" : "success", safe.length(), trimmed.length());
        }
        static ChildResult error(int idx, String name, String err) {
            return ofError(idx, name, err);
        }

        String toToolResponse(String agentName) {
            if (success) return "[Agent「" + agentName + "」的回复]\n\n" + (result != null ? result : "");
            return "[错误] Agent「" + agentName + "」执行失败: " + error;
        }

        private static String truncate(String text, int maxLength) {
            if (text == null) return "";
            if (text.length() <= maxLength) return text;
            return text.substring(0, maxLength) + "\n... [截断，原文 " + text.length() + " 字符]";
        }
    }

    // ==================== Helper methods ====================

    @Tool(description = "List all available Agents (enabled), including name, type, and description.")
    public String listAvailableAgents() {
        List<AgentEntity> agents = agentMapper.selectList(
                new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getEnabled, true)
                        .orderByAsc(AgentEntity::getName));
        if (agents.isEmpty()) return "当前没有可用的 Agent。";
        StringBuilder sb = new StringBuilder("可用 Agent 列表：\n\n");
        for (AgentEntity agent : agents) {
            sb.append("- **").append(agent.getName()).append("**");
            sb.append(" (").append(agent.getAgentType()).append(")");
            if (agent.getDescription() != null && !agent.getDescription().isBlank()) {
                sb.append(" — ").append(agent.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private AgentEntity findAgent(String name) {
        return agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getName, name.trim())
                .eq(AgentEntity::getEnabled, true));
    }

    /**
     * RFC-03 Lane C2 — build the parent-context prefix injected into the
     * child's task when {@code inheritParentContext=true}.
     *
     * <p>Reads the latest {@link #INHERITED_CONTEXT_MAX_MESSAGES} messages
     * from the parent conversation, formats them as a labeled block, and
     * returns an empty string when no usable history exists. Errors are
     * swallowed (logged warn) — context inheritance is best-effort, not a
     * correctness gate; a missing prefix degrades to "child runs without
     * extra context", which is the original behavior.
     */
    private String buildInheritedContextPrefix(String parentConversationId) {
        try {
            List<vip.mate.workspace.conversation.model.MessageEntity> messages =
                    conversationService.listRecentMessages(
                            parentConversationId, INHERITED_CONTEXT_MAX_MESSAGES);
            return formatInheritedContext(messages, INHERITED_CONTEXT_PER_MESSAGE_CHARS);
        } catch (Exception e) {
            log.warn("Failed to build parent context prefix for child agent: parentConv={}, err={}",
                    parentConversationId, e.getMessage());
            return "";
        }
    }

    /**
     * RFC-03 Lane C2 — format a list of parent {@link vip.mate.workspace.conversation.model.MessageEntity}
     * as a labeled, role-tagged context block for injection into the child's
     * task prompt. Package-private + static so it can be unit-tested without
     * touching ConversationService or any mocks.
     *
     * <p>Output shape:
     * <pre>
     * --- Parent conversation recent context (N messages) ---
     * USER: ...
     * ASSISTANT: ...
     * ...
     * --- End of context ---
     * </pre>
     *
     * <p>Per-message content is truncated to {@code maxPerMessageChars} so a
     * single huge tool result in the parent doesn't blow up the child's
     * context window. Empty / null inputs return an empty string so the
     * caller can skip prefix injection cleanly.
     */
    static String formatInheritedContext(
            List<vip.mate.workspace.conversation.model.MessageEntity> messages,
            int maxPerMessageChars) {
        if (messages == null || messages.isEmpty()) return "";
        // Filter out system messages — those carry agent identity, not user dialogue,
        // and the child has its own system prompt.
        List<vip.mate.workspace.conversation.model.MessageEntity> dialogue = messages.stream()
                .filter(m -> m != null && m.getRole() != null
                        && !"system".equalsIgnoreCase(m.getRole()))
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .toList();
        if (dialogue.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(dialogue.size() * 200);
        sb.append("--- Parent conversation recent context (")
          .append(dialogue.size())
          .append(" message").append(dialogue.size() == 1 ? "" : "s")
          .append(") ---\n");
        for (vip.mate.workspace.conversation.model.MessageEntity m : dialogue) {
            String role = m.getRole().toUpperCase();
            String content = m.getContent();
            if (content.length() > maxPerMessageChars) {
                content = content.substring(0, maxPerMessageChars)
                        + "... [truncated, " + (content.length() - maxPerMessageChars) + " chars omitted]";
            }
            sb.append(role).append(": ").append(content).append("\n");
        }
        sb.append("--- End of context ---");
        return sb.toString();
    }

    private String createChildConv(AgentEntity target, String parentConversationId) {
        String childConvId = "child-" + UUID.randomUUID().toString().substring(0, 12);
        try {
            conversationService.createChildConversation(childConvId, target.getId(), "system",
                    target.getWorkspaceId() != null ? target.getWorkspaceId() : 1L, parentConversationId);
        } catch (Exception e) {
            log.warn("Failed to create child conversation: {}", e.getMessage());
        }
        return childConvId;
    }

    /** Child event types that are relayed to the root for the nested delegation timeline. */
    private static final Set<String> RELAYED_CHILD_EVENTS = Set.of(
            "tool_call_started", "tool_call_completed", "phase",
            "plan_created", "plan_step_started", "plan_step_completed");

    /** Tree identity attached to every relayed delegation event. */
    private record RelayIdentity(String childConvId, String childAgentName,
                                 String subagentId, String parentSubagentId, int depth) {}

    /**
     * Builds a delegation event payload carrying tree identity. A null
     * {@code parentSubagentId} (first-level child) is omitted rather than
     * inserted, since downstream consumers treat absence as "top of tree".
     */
    private Map<String, Object> delegationPayload(String subagentId, String parentSubagentId, int depth,
                                                  String childConvId, String childAgentName) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (subagentId != null) m.put("subagentId", subagentId);
        if (parentSubagentId != null) m.put("parentSubagentId", parentSubagentId);
        m.put("depth", depth);
        m.put("childConversationId", childConvId);
        m.put("childAgentName", childAgentName);
        return m;
    }

    /**
     * Registers a batched relay so a chatty child does not flood the transcript
     * with one tool-call event per LLM step. The streaming layer batches
     * {@code tool_call_started} / {@code tool_call_completed} into envelopes
     * (5 events / 500 ms) and flushes immediately on lifecycle events
     * ({@code subagent_*}, {@code error}, {@code phase}, etc.).
     *
     * <p>Both batched envelopes and pass-through events surface as
     * {@code delegation_progress} on the {@code rootConvId} stream (the
     * human-facing conversation), tagged with subagentId/parentSubagentId/depth
     * so the frontend can rebuild the multi-level spawn tree.
     */
    private Runnable registerBatchedRelay(String childConvId, String rootConvId, String childAgentName,
                                          String subagentId, String parentSubagentId, int depth) {
        RelayIdentity id = new RelayIdentity(childConvId, childAgentName, subagentId, parentSubagentId, depth);
        return streamTracker.addBatchedEventRelay(childConvId, rootConvId, 5, 500L,
                (eventName, jsonData) -> {
                    // (1) pass-through events arrive directly (plan/phase/error);
                    // (2) batched tool-calls arrive as a "delegation_batch"
                    // envelope. Unpack both into delegation_progress events so the
                    // frontend only handles a single event shape.
                    if ("delegation_batch".equals(eventName)) {
                        relayBatchEnvelope(jsonData, rootConvId, id);
                    } else if (RELAYED_CHILD_EVENTS.contains(eventName)) {
                        relayChildEvent(eventName, jsonData, rootConvId, id);
                    }
                });
    }

    /** Forward one child event to the root as a delegation_progress envelope. */
    private void relayChildEvent(String eventName, String jsonData, String rootConvId, RelayIdentity id) {
        try {
            Object parsedData;
            try {
                parsedData = objectMapper.readValue(jsonData, Object.class);
            } catch (Exception ignored) {
                parsedData = jsonData;
            }
            Map<String, Object> ev = delegationPayload(id.subagentId(), id.parentSubagentId(), id.depth(),
                    id.childConvId(), id.childAgentName());
            ev.put("originalEvent", eventName);
            ev.put("data", parsedData);
            streamTracker.broadcastObject(rootConvId, "delegation_progress", ev);
        } catch (Exception e) {
            log.debug("Child event relay error: {}", e.getMessage());
        }
    }

    /** Unpack a delegation_batch envelope and replay each entry as delegation_progress. */
    @SuppressWarnings("unchecked")
    private void relayBatchEnvelope(String envelopeJson, String rootConvId, RelayIdentity id) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(envelopeJson, Map.class);
            Object eventsObj = envelope.get("events");
            if (!(eventsObj instanceof List<?> events)) return;
            for (Object entryObj : events) {
                if (!(entryObj instanceof Map<?, ?> entry)) continue;
                Object name = entry.get("event");
                Object payload = entry.get("data");
                if (name == null) continue;
                if (!RELAYED_CHILD_EVENTS.contains(name.toString())) continue;
                String payloadJson = payload == null
                        ? "{}"
                        : (payload instanceof String s ? s : objectMapper.writeValueAsString(payload));
                relayChildEvent(name.toString(), payloadJson, rootConvId, id);
            }
        } catch (Exception e) {
            log.debug("Batch envelope relay error: {}", e.getMessage());
        }
    }

    private void broadcastEnd(String rootConvId, String childConvId, String agentName, ChildResult result,
                             String subagentId, String parentSubagentId, int depth) {
        Map<String, Object> ev = delegationPayload(subagentId, parentSubagentId, depth, childConvId, agentName);
        ev.put("success", result.success);
        ev.put("durationMs", result.durationMs);
        ev.put("resultPreview",
                result.success ? truncate(result.result, 200) : (result.error != null ? result.error : ""));
        streamTracker.broadcastObject(rootConvId, "delegation_end", ev);
    }

    private String resolveParentConversationId() {
        try {
            String ctxConvId = ToolExecutionContext.conversationId();
            if (ctxConvId != null && !ctxConvId.isBlank()) return ctxConvId;
        } catch (Exception ignored) {}
        return DelegationContext.parentConversationId();
    }

    private String availableAgentsHint() {
        List<AgentEntity> agents = agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getEnabled, true).select(AgentEntity::getName));
        if (agents.isEmpty()) return "";
        return "\n可用 Agent: " + agents.stream().map(AgentEntity::getName).collect(Collectors.joining("、"));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n... [截断，原文 " + text.length() + " 字符]";
    }
}
