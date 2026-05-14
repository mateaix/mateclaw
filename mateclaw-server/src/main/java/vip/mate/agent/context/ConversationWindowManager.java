package vip.mate.agent.context;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.stereotype.Component;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.workspace.conversation.ConversationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史上下文窗口管理器（Hermes 风格升级版）
 * <p>
 * 四阶段压缩策略：
 * <ol>
 *   <li>Soft Trim — 裁剪旧工具结果为 head+tail</li>
 *   <li>Hard Clear — 替换所有旧工具结果为占位符</li>
 *   <li>Pre-Prune — 喂给摘要 LLM 前清理工具输出（减少摘要输入 token）</li>
 *   <li>LLM 结构化摘要 — Goal/Progress/Decisions/Files/NextSteps 模板，支持迭代更新</li>
 * </ol>
 * <p>
 * 关键特性：
 * <ul>
 *   <li>迭代摘要更新：多轮压缩时将旧摘要 + 新轮次合并，信息不丢失</li>
 *   <li>动态 Token 预算：基于模型上下文长度计算尾部保护和摘要预算</li>
 *   <li>压缩冷却机制：摘要失败后 10 分钟内不重试，防止雪崩</li>
 *   <li>MemoryProvider 钩子：压缩前通知记忆 provider 提取关键信息</li>
 * </ul>
 * <p>
 * 安全设计：摘要内容作为 UserMessage 注入（非 SystemMessage），
 * 避免历史用户输入被提升为系统级指令。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationWindowManager {

    // ==================== Prompt 模板 ====================

    /** 首次压缩：结构化摘要系统提示 */
    private static final String STRUCTURED_SUMMARY_SYSTEM = PromptLoader.loadPrompt("context/structured-summary-system");
    /** 首次压缩：用户提示模板 */
    private static final String STRUCTURED_SUMMARY_USER = PromptLoader.loadPrompt("context/structured-summary-user");
    /** 迭代更新：合并旧摘要 + 新轮次 */
    private static final String STRUCTURED_SUMMARY_UPDATE = PromptLoader.loadPrompt("context/structured-summary-update");

    /** 摘要注入前缀 (package-private for test assertions) */
    static final String SUMMARY_PREFIX =
            "[上下文压缩] 更早的对话轮次已被压缩为摘要以节省上下文空间。" +
            "以下摘要描述了已完成的工作，当前会话状态可能已反映这些变更。" +
            "请基于摘要和当前状态继续，避免重复已完成的工作：\n\n";

    /**
     * Marker prefix used by the first-user anchor. Lets compaction skip
     * previously-injected anchors when looking for the "real" first user
     * message in a subsequent round.
     *
     * <p>Package-private so unit tests can assert on the marker.
     */
    static final String ANCHOR_PREFIX = "[Original goal]\n";

    // ==================== 序列化截断参数 ====================

    private static final int CONTENT_MAX = 6000;
    private static final int CONTENT_HEAD = 4000;
    private static final int CONTENT_TAIL = 1500;

    /**
     * Minimum body size at which the duplicate-output placeholder is preferred
     * over keeping the verbatim copy. Below this size the placeholder text
     * (~80 chars) is comparable to the body itself, so deduplication only
     * complicates the prompt without saving meaningful tokens. Above this
     * size the dedup placeholder is a real win.
     */
    private static final int DEDUP_MIN_CHARS = 500;

    /**
     * Tool names whose results must never be compacted into a one-line
     * summary. Sub-agent delegations are irreplaceable: the child runs an
     * independent LLM session that the parent cannot reproduce, so dropping
     * earlier batches forces the parent to re-dispatch the same children to
     * recover what was lost. Every other tool (read_file, shell, search,
     * memory) can be re-invoked cheaply if the parent decides it needs
     * the data again.
     */
    private static final java.util.Set<String> PRUNE_EXEMPT_TOOLS = java.util.Set.of(
            "delegateToAgent",
            "delegateParallel"
    );

    // ==================== 冷却机制 ====================

    /** 摘要失败后的冷却时间（毫秒）：10 分钟 */
    private static final long SUMMARY_COOLDOWN_MS = 600_000;

    // ==================== 依赖 ====================

    private final ConversationWindowProperties properties;
    private final MemoryManager memoryManager;
    private final ConversationService conversationService;

    /**
     * Optional spill store, injected via setter so unit tests and the two
     * existing 3-arg constructor callers in tests stay source-compatible.
     * When {@code null}, prune falls back to "keep originals verbatim" — no
     * lossy summary rewrite is ever applied. Spring autowires this when
     * {@link ToolResultStorage} is on the context.
     */
    private ToolResultStorage toolResultStorage;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setToolResultStorage(ToolResultStorage toolResultStorage) {
        this.toolResultStorage = toolResultStorage;
    }

    /**
     * Optional stream tracker for broadcasting {@code compact_status}
     * SSE events. Wired via setter so unit tests can leave it {@code null}
     * without dragging in the channel layer. When present, every
     * compaction emits start/skipped/summarize/done events so the
     * frontend can render a boundary card and a status line in real
     * time.
     */
    private vip.mate.channel.web.ChatStreamTracker streamTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStreamTracker(vip.mate.channel.web.ChatStreamTracker streamTracker) {
        this.streamTracker = streamTracker;
    }

    // ==================== 状态 ====================

    /** 摘要缓存：key = "conversationId:oldMessageCount" */
    private final ConcurrentHashMap<String, CachedSummary> summaryCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    /** 迭代摘要：上一次压缩生成的摘要文本（per conversation） */
    private final ConcurrentHashMap<String, String> previousSummaries = new ConcurrentHashMap<>();

    /** 每个会话的压缩次数 */
    private final ConcurrentHashMap<String, Integer> compressionCounts = new ConcurrentHashMap<>();

    /** 每个会话的摘要冷却截止时间 */
    private final ConcurrentHashMap<String, Long> summaryCooldownUntil = new ConcurrentHashMap<>();

    // ==================== 主入口 ====================

    /**
     * 将会话历史裁剪到上下文窗口内。
     *
     * @param messages          已转换的 Spring AI 消息列表（不含当前用户消息）
     * @param systemPrompt      系统提示词文本
     * @param currentUserMessage 当前用户输入（纳入窗口预算计算，但不拼入返回结果）
     * @param maxInputTokens    模型最大输入 token（0 或 null 使用全局默认）
     * @param chatModel         用于生成摘要的 ChatModel
     * @param conversationId    会话 ID（用于缓存和迭代摘要）
     * @param agentId           Agent ID（用于 MemoryProvider 钩子）
     * @return 裁剪后的消息列表
     */
    public List<Message> fitToWindow(List<Message> messages, String systemPrompt,
                                     String currentUserMessage,
                                     Integer maxInputTokens, ChatModel chatModel,
                                     String conversationId, Long agentId) {
        return fitToWindow(messages, systemPrompt, currentUserMessage,
                maxInputTokens, chatModel, conversationId, agentId, null, null);
    }

    /**
     * Same as the 7-arg overload but additionally accounts for the tool
     * definitions sent on every LLM call. Without {@code toolCallbacks},
     * the budget calculation underestimates the actual request size by the
     * full size of the tools schema (often several thousand tokens for
     * agents bound to multiple MCP servers), making compression fire too
     * late and producing HTTP 400 once the request hits the model.
     */
    public List<Message> fitToWindow(List<Message> messages, String systemPrompt,
                                     String currentUserMessage,
                                     Integer maxInputTokens, ChatModel chatModel,
                                     String conversationId, Long agentId,
                                     java.util.Collection<ToolCallback> toolCallbacks) {
        return fitToWindow(messages, systemPrompt, currentUserMessage,
                maxInputTokens, chatModel, conversationId, agentId, toolCallbacks, null);
    }

    /**
     * Most comprehensive overload — adds {@code workspaceBasePath} so the
     * pre-pass that prunes old tool results can route oversized bodies to
     * the agent's workspace spill directory via {@link ToolResultStorage}.
     *
     * <p>When {@code workspaceBasePath} is {@code null}, spill files land in
     * the configured base dir, or the JVM tmpdir as last resort (see
     * {@link ToolResultStorage#resolveBaseDir(String)}). Workspace-aware
     * callers should always pass the path so historical spill files stay
     * grouped with the workspace that produced them.
     */
    public List<Message> fitToWindow(List<Message> messages, String systemPrompt,
                                     String currentUserMessage,
                                     Integer maxInputTokens, ChatModel chatModel,
                                     String conversationId, Long agentId,
                                     java.util.Collection<ToolCallback> toolCallbacks,
                                     String workspaceBasePath) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        long spillsAtEntry = (toolResultStorage != null) ? toolResultStorage.getSpillCount() : 0L;

        messages = pruneOldToolResultsForModelInput(messages, conversationId, workspaceBasePath);

        int effectiveMax = (maxInputTokens != null && maxInputTokens > 0)
                ? maxInputTokens : properties.getDefaultMaxInputTokens();
        int triggerThreshold = (int) (effectiveMax * properties.getCompactTriggerRatio());

        int systemTokens = TokenEstimator.estimateTokens(systemPrompt);
        int currentMsgTokens = TokenEstimator.estimateTokens(currentUserMessage) + TokenEstimator.PER_MESSAGE_OVERHEAD;
        int historyTokens = TokenEstimator.estimateTokens(messages);
        int toolsTokens = TokenEstimator.estimateToolsTokens(toolCallbacks);
        int totalTokens = systemTokens + currentMsgTokens + historyTokens + toolsTokens;

        if (totalTokens <= triggerThreshold) {
            return messages;
        }

        log.info("[ConversationWindow] 超阈值: {} tokens (system={}, current={}, history={}, tools={}) > {} 触发阈值 (max={}), conv={}",
                totalTokens, systemTokens, currentMsgTokens, historyTokens, toolsTokens,
                triggerThreshold, effectiveMax, conversationId);

        evictExpiredEntries();

        // 可用于历史的 token 预算 = max - system - currentMsg - tools - 安全余量
        int reservedTokens = systemTokens + currentMsgTokens + toolsTokens + (int) (effectiveMax * 0.05);
        // 预留 reserve 硬封顶到 effectiveMax 的 50%。
        // 小上下文模型（Ollama 16K、本地 8K）下，systemTokens + currentMsgTokens 很容易
        // 接近或超过 effectiveMax，不封顶会让 historyBudget 变负数导致死循环压缩
        // （压缩目标比压缩前还大 → 压缩后又触发压缩）。
        int reservedCap = Math.max(1024, effectiveMax / 2);
        if (reservedTokens > reservedCap) {
            log.warn("[ConversationWindow] 预留 token {} 超过上下文窗口 50% {}，封顶至 {}",
                    reservedTokens, effectiveMax, reservedCap);
            reservedTokens = reservedCap;
        }
        int historyBudget = effectiveMax - reservedTokens;

        // 尾部保护 token 预算：阈值的 20%（与 Hermes 一致）
        int tailTokenBudget = (int) (triggerThreshold * 0.20);

        return compactMessages(messages, historyBudget, tailTokenBudget, chatModel,
                conversationId, agentId, totalTokens, spillsAtEntry);
    }

    /**
     * 向后兼容：不传 agentId 的旧签名（agentId = null，不触发 Memory 钩子）
     */
    public List<Message> fitToWindow(List<Message> messages, String systemPrompt,
                                     String currentUserMessage,
                                     Integer maxInputTokens, ChatModel chatModel,
                                     String conversationId) {
        return fitToWindow(messages, systemPrompt, currentUserMessage,
                maxInputTokens, chatModel, conversationId, null);
    }

    // ==================== 核心压缩逻辑 ====================

    /** Broadcast a single compact_status event; silent no-op when no tracker is wired. */
    private void broadcastCompactStatus(String conversationId, String status, Map<String, Object> extra) {
        if (streamTracker == null || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("status", status);
            payload.put("timestamp", System.currentTimeMillis());
            if (extra != null) payload.putAll(extra);
            streamTracker.broadcastObject(conversationId, "compact_status", payload);
        } catch (Exception e) {
            log.debug("[ConversationWindow] broadcast compact_status failed: {}", e.getMessage());
        }
    }

    private List<Message> compactMessages(List<Message> messages, int historyBudget,
                                          int tailTokenBudget, ChatModel chatModel,
                                          String conversationId, Long agentId,
                                          int preTokens, long spillsAtEntry) {
        broadcastCompactStatus(conversationId, "start", Map.of(
                "preTokens", preTokens,
                "messagesIn", messages.size(),
                "trigger", "token_threshold"
        ));

        // 动态计算尾部保护边界（替代固定 preserveRecentPairs）
        int headEnd = 0; // 头部保护：暂不保护（system prompt 已在外部计算）
        int tailStart = findTailBoundary(messages, headEnd, tailTokenBudget);

        if (tailStart <= headEnd) {
            log.debug("[ConversationWindow] 消息数不足以拆分，跳过压缩");
            broadcastCompactStatus(conversationId, "skipped",
                    Map.of("reason", "insufficient_messages"));
            return messages;
        }

        // Pair safety: never split an AssistantMessage's tool_calls from its
        // matching ToolResponseMessages. The cut may walk forward (i.e. the
        // tail grows) until every call/response cluster lives on one side of
        // the boundary. If no safe cut survives the walk, skip compaction —
        // a broken pair would 400 every OpenAI-compatible provider, which is
        // strictly worse than letting context cross the budget by one extra
        // turn.
        int pairSafeCut = enforcePairSafeBoundary(messages, headEnd, tailStart);
        if (pairSafeCut <= headEnd) {
            broadcastCompactStatus(conversationId, "skipped",
                    Map.of("reason", "pair_boundary_collapsed"));
            return messages;
        }
        if (pairSafeCut != tailStart) {
            broadcastCompactStatus(conversationId, "pair_safe", Map.of(
                    "movedFrom", tailStart, "movedTo", pairSafeCut));
        }
        tailStart = pairSafeCut;

        List<Message> oldMessages = new ArrayList<>(messages.subList(headEnd, tailStart));
        List<Message> recentMessages = messages.subList(tailStart, messages.size());

        // ═══ Phase 1: Soft Trim — 裁剪旧工具结果 ═══
        int softTrimmed = softTrimToolResults(oldMessages);
        if (softTrimmed > 0) {
            int afterTrimTokens = TokenEstimator.estimateTokens(oldMessages) + TokenEstimator.estimateTokens(recentMessages);
            log.info("[ConversationWindow] Phase 1 Soft trim: {} tool results trimmed, tokens={}, budget={}",
                    softTrimmed, afterTrimTokens, historyBudget);
            if (afterTrimTokens <= historyBudget) {
                List<Message> result = new ArrayList<>(oldMessages);
                result.addAll(recentMessages);
                return result;
            }
        }

        // ═══ Phase 2: Hard Clear — 替换所有旧工具结果为占位符 ═══
        int hardCleared = hardClearToolResults(oldMessages);
        if (hardCleared > 0) {
            int afterClearTokens = TokenEstimator.estimateTokens(oldMessages) + TokenEstimator.estimateTokens(recentMessages);
            log.info("[ConversationWindow] Phase 2 Hard clear: {} replaced, tokens={}, budget={}",
                    hardCleared, afterClearTokens, historyBudget);
            if (afterClearTokens <= historyBudget) {
                List<Message> result = new ArrayList<>(oldMessages);
                result.addAll(recentMessages);
                return result;
            }
        }

        // ═══ Phase 2.5: MemoryProvider 钩子 — 压缩前提取关键信息 ═══
        String memoryExtraContext = "";
        if (agentId != null && memoryManager != null) {
            try {
                String preserved = memoryManager.onPreCompress(agentId, oldMessages);
                if (preserved != null && !preserved.isBlank()) {
                    memoryExtraContext = preserved;
                    log.debug("[ConversationWindow] MemoryProvider onPreCompress contributed {} chars", preserved.length());
                }
            } catch (Exception e) {
                log.debug("[ConversationWindow] onPreCompress hook failed: {}", e.getMessage());
            }
        }

        // ═══ Phase 3: Pre-Prune + LLM 结构化摘要 ═══

        // Pre-prune：在喂给摘要 LLM 前清理旧消息中的工具输出
        List<Message> forSummary = new ArrayList<>(oldMessages);
        int prePruned = prePruneForSummary(forSummary);
        if (prePruned > 0) {
            log.info("[ConversationWindow] Phase 3 Pre-prune: {} tool results cleared before summarization", prePruned);
        }

        // 计算动态摘要预算
        int summaryBudget = computeSummaryBudget(forSummary);

        broadcastCompactStatus(conversationId, "summarize", Map.of(
                "messagesToSummarize", oldMessages.size(),
                "summaryBudget", summaryBudget
        ));

        // 检查缓存
        String cacheKey = conversationId + ":" + oldMessages.size();
        CachedSummary cached = summaryCache.get(cacheKey);
        String summary;
        boolean fromCache = false;

        if (cached != null && !cached.isExpired(CACHE_TTL_MS)) {
            summary = cached.summary();
            fromCache = true;
            log.debug("[ConversationWindow] 命中摘要缓存, conv={}", conversationId);
        } else {
            summary = generateSummary(forSummary, chatModel, conversationId, summaryBudget, memoryExtraContext);
            if (summary != null) {
                summaryCache.put(cacheKey, new CachedSummary(summary, System.currentTimeMillis()));
                int count = compressionCounts.merge(conversationId, 1, Integer::sum);
                log.info("[ConversationWindow] 生成结构化摘要 ({} 字符, 第 {} 次压缩), 压缩 {} 条旧消息, conv={}",
                        summary.length(), count, oldMessages.size(), conversationId);
            }
        }

        // 组装结果
        List<Message> result = new ArrayList<>();
        boolean anchored = false;
        if (summary != null && !summary.isBlank()) {
            result.add(new UserMessage(SUMMARY_PREFIX + summary));

            // Anchor the original user goal so a long task that paged through
            // dozens of turns can still see what was originally asked. Always
            // as a UserMessage — promoting historical user input to a
            // SystemMessage would be a privilege-escalation risk.
            Message anchor = buildFirstUserAnchor(oldMessages);
            if (anchor != null) {
                result.add(anchor);
                anchored = true;
            }
        } else if (!oldMessages.isEmpty()) {
            log.warn("[ConversationWindow] 摘要生成失败，降级为保留最近 4 条旧消息, conv={}", conversationId);
            int fallbackKeep = Math.min(4, oldMessages.size());
            result.addAll(oldMessages.subList(oldMessages.size() - fallbackKeep, oldMessages.size()));
            broadcastCompactStatus(conversationId, "failed", Map.of(
                    "reason", "summary_generation_failed",
                    "fallbackKept", fallbackKeep
            ));
        }
        result.addAll(recentMessages);

        // 压缩后校验
        int resultTokens = TokenEstimator.estimateTokens(result);
        if (resultTokens > historyBudget && result.size() > 2) {
            log.warn("[ConversationWindow] 压缩后仍超预算: {} > {}, 执行二次裁剪", resultTokens, historyBudget);
            result = trimToFit(result, historyBudget);
            resultTokens = TokenEstimator.estimateTokens(result);
        }

        // Persist the boundary + announce completion only when the summary
        // actually wrote a row. Failed-summary fallback already broadcast
        // its own event above.
        if (summary != null && !summary.isBlank() && conversationService != null && !fromCache) {
            long spillsThisTurn = (toolResultStorage != null)
                    ? Math.max(0L, toolResultStorage.getSpillCount() - spillsAtEntry)
                    : 0L;
            Map<String, Object> boundaryMetadata = new java.util.LinkedHashMap<>();
            boundaryMetadata.put("trigger", "token_threshold");
            boundaryMetadata.put("preTokens", preTokens);
            boundaryMetadata.put("postTokens", resultTokens);
            boundaryMetadata.put("messagesSummarized", oldMessages.size());
            boundaryMetadata.put("tailKept", recentMessages.size());
            boundaryMetadata.put("toolResultsSpilled", spillsThisTurn);
            boundaryMetadata.put("anchored", anchored);
            Long summaryId = null;
            try {
                summaryId = conversationService.saveCompressionSummaryReturningId(
                        conversationId, SUMMARY_PREFIX + summary, oldMessages.size(),
                        boundaryMetadata);
            } catch (Exception e) {
                log.warn("[ConversationWindow] Failed to persist compression boundary: {}", e.getMessage());
            }
            if (summaryId != null) {
                // Mirror the DB row's metadata: the SSE consumer needs the id
                // to deep-link the boundary card without having to refetch.
                boundaryMetadata.put("summaryId", summaryId);
            }
            broadcastCompactStatus(conversationId, "done", boundaryMetadata);
        } else if (summary != null && !summary.isBlank() && fromCache) {
            // Cached summary path — no new DB row, but emit done so the
            // frontend status bar still updates.
            broadcastCompactStatus(conversationId, "done", Map.of(
                    "preTokens", preTokens,
                    "postTokens", resultTokens,
                    "messagesSummarized", oldMessages.size(),
                    "tailKept", recentMessages.size(),
                    "fromCache", true
            ));
        }

        return result;
    }

    // ==================== 动态 Token 预算 ====================

    /**
     * 基于 token 预算动态计算尾部保护边界（替代固定 preserveRecentPairs）。
     * 从消息列表末尾向前累加 token，直到耗尽预算或达到最小消息数。
     */
    private int findTailBoundary(List<Message> messages, int headEnd, int tailTokenBudget) {
        int n = messages.size();
        if (n <= headEnd + 1) return headEnd;

        int minTail = Math.min(properties.getProtectLastMinMessages(), n - headEnd - 1);
        // 兼容旧配置：如果 protectLastMinMessages 未设置但 preserveRecentPairs 有值
        int pairsBased = properties.getPreserveRecentPairs() * 2;
        if (pairsBased > minTail) {
            minTail = Math.min(pairsBased, n - headEnd - 1);
        }

        int softCeiling = (int) (tailTokenBudget * 1.5);
        int accumulated = 0;
        int cutIdx = n;

        for (int i = n - 1; i >= headEnd; i--) {
            int msgTokens = TokenEstimator.estimateTokens(messages.get(i));
            if (accumulated + msgTokens > softCeiling && (n - i) >= minTail) {
                break;
            }
            accumulated += msgTokens;
            cutIdx = i;
        }

        // 确保至少保留 minTail 条
        int fallbackCut = n - minTail;
        if (cutIdx > fallbackCut) {
            cutIdx = fallbackCut;
        }

        return Math.max(cutIdx, headEnd + 1);
    }

    /**
     * Adjust the candidate boundary so an {@link AssistantMessage}'s
     * {@code toolCalls} are never separated from their matching
     * {@link ToolResponseMessage}s.
     *
     * <p>Walks forward, collecting every {@code tool_call_id}'s assistant
     * index and the indices of its matching responses. Whenever an
     * assistant in the prefix has at least one response in the tail, the
     * cut moves backward to that assistant — pulling the whole cluster
     * into the tail. The walk repeats until convergence because moving
     * the cut can expose pairs that were previously fully in the tail.
     *
     * <p>The method preserves pair integrity above any other concern. If
     * the cut collapses all the way to {@code headEnd}, callers must
     * interpret the return as "skip compaction this turn" — splitting a
     * pair would produce HTTP 400 on every OpenAI-compatible provider,
     * which is a worse failure mode than letting context grow by one turn.
     *
     * <p>An orphan {@code ToolResponseMessage} (id matching no
     * assistant in scope) does not trigger movement; the upstream code
     * paths should never produce one, and logging at WARN gives us a
     * breadcrumb if they ever do.
     *
     * @return adjusted cut index, or {@code headEnd} when no pair-safe
     *         cut larger than {@code headEnd} can be produced.
     */
    // Package-private so unit tests in the same package can drive it directly
    // without standing up a ChatModel + the rest of the compactMessages pipeline.
    int enforcePairSafeBoundary(List<Message> messages, int headEnd, int tailStart) {
        if (tailStart <= headEnd || tailStart >= messages.size()) {
            return tailStart;
        }
        int cut = tailStart;
        int safety = messages.size() + 1; // hard guard against pathological loops
        while (safety-- > 0) {
            // Map: tool_call_id -> earliest assistant index that issued it.
            java.util.Map<String, Integer> assistantIdxById = new java.util.HashMap<>();
            // Map: tool_call_id -> max response index closing it.
            java.util.Map<String, Integer> latestResponseIdxById = new java.util.HashMap<>();

            for (int i = headEnd; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        String tid = tc.id();
                        if (tid == null || tid.isEmpty()) continue;
                        // Keep the first occurrence so the cut "snaps" to the
                        // earliest assistant for any duplicated ids; the same
                        // id should never repeat anyway.
                        assistantIdxById.putIfAbsent(tid, i);
                    }
                } else if (m instanceof ToolResponseMessage trm) {
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        String tid = r.id();
                        if (tid == null || tid.isEmpty()) continue;
                        latestResponseIdxById.merge(tid, i, Math::max);
                    }
                }
            }

            // Find the earliest in-prefix assistant whose pair is split.
            int earliestSplitAssistant = Integer.MAX_VALUE;
            for (var e : assistantIdxById.entrySet()) {
                String id = e.getKey();
                int aIdx = e.getValue();
                Integer rIdx = latestResponseIdxById.get(id);
                if (rIdx == null) {
                    // Assistant issued a call but no response — orphan call,
                    // would already break the provider. Not a pair-split, ignore.
                    continue;
                }
                if (aIdx < cut && rIdx >= cut && aIdx < earliestSplitAssistant) {
                    earliestSplitAssistant = aIdx;
                }
                if (aIdx >= cut && rIdx < cut) {
                    log.warn("[ConversationWindow] Orphan tool response in prefix without preceding assistant in tail (id={}); leaving boundary alone",
                            id);
                }
            }

            if (earliestSplitAssistant == Integer.MAX_VALUE) {
                break; // converged: no splits remain
            }
            cut = earliestSplitAssistant;
        }

        if (cut <= headEnd) {
            log.info("[ConversationWindow] Pair-safe boundary collapsed to {} for conv: skipping compaction this turn to avoid splitting a tool_call ↔ tool_response pair",
                    headEnd);
            return headEnd;
        }

        int prefixSize = cut - headEnd;
        int minPrefix = Math.max(0, properties.getPairSafeMinPrefixToCompact());
        if (prefixSize < minPrefix) {
            log.info("[ConversationWindow] Pair-safe boundary left {} prefix message(s) (< minPrefix={}); skipping compaction",
                    prefixSize, minPrefix);
            return headEnd;
        }

        if (cut != tailStart) {
            log.info("[ConversationWindow] Pair-safe boundary moved {} -> {} to keep tool_call ↔ tool_response pairs intact",
                    tailStart, cut);
        }
        return cut;
    }

    /**
     * Build an anchor message replaying the first <em>real</em> user input
     * found in the compressed prefix. "Real" here excludes prior
     * compaction artifacts ({@link #SUMMARY_PREFIX} / {@link #ANCHOR_PREFIX}
     * messages from earlier rounds), because anchoring the previous
     * summary defeats the purpose — the model would just see "[Original
     * goal] [上下文压缩] …" pointing at compressor output, not at the user's
     * actual request.
     *
     * <p>Sizing rules:
     * <ul>
     *   <li>≤ {@code firstUserAnchorMaxTokens}: keep the original text verbatim.</li>
     *   <li>≤ 3× the budget: head+tail truncate to the budget so most of
     *       the prompt-cache benefit survives.</li>
     *   <li>&gt; 3× the budget: degrade to a 200-char pointer line so we
     *       don't blow prompt cache or the summary budget on a single
     *       message that was probably a pasted spec the model can re-read
     *       from the workspace anyway.</li>
     * </ul>
     *
     * <p>Always returns a {@link UserMessage}. {@code null} when anchoring
     * is disabled, no real first user exists in the prefix, or the body is
     * blank.
     *
     * <p>Package-private for direct unit testing — the surrounding
     * {@link #compactMessages} path needs a ChatModel and the whole
     * structured-summary pipeline, which the anchor logic does not.
     */
    Message buildFirstUserAnchor(List<Message> oldMessages) {
        if (!properties.isFirstUserAnchorEnabled()) {
            return null;
        }
        UserMessage firstUser = null;
        for (Message m : oldMessages) {
            if (!(m instanceof UserMessage um)) continue;
            String text = um.getText();
            if (text == null) continue;
            // Skip synthetic prior-round artifacts.
            if (text.startsWith(SUMMARY_PREFIX) || text.startsWith(ANCHOR_PREFIX)) {
                continue;
            }
            firstUser = um;
            break;
        }
        if (firstUser == null) return null;

        String text = firstUser.getText();
        if (text == null || text.isBlank()) return null;

        int maxAnchorTokens = Math.max(40, properties.getFirstUserAnchorMaxTokens());
        int textTokens = TokenEstimator.estimateTokens(text);

        if (textTokens <= maxAnchorTokens) {
            return new UserMessage(ANCHOR_PREFIX + text);
        }

        // > 3× budget: cheap pointer line so we don't pay token tax for a
        // gigantic pasted spec. The model still knows the original goal
        // existed without seeing the full body.
        if (textTokens > maxAnchorTokens * 3L) {
            int pointerChars = Math.min(text.length(), 200);
            String pointer = text.substring(0, pointerChars).stripTrailing()
                    + (text.length() > pointerChars ? "..." : "");
            log.info("[ConversationWindow] First-user anchor downgraded to pointer ({} tokens > 3× budget {})",
                    textTokens, maxAnchorTokens);
            return new UserMessage(ANCHOR_PREFIX + pointer);
        }

        // Within 3× — head+tail truncate to the budget. The 2 chars/token
        // ratio is a deliberate over-estimate so the anchor never inflates
        // past the configured budget on ASCII-heavy input.
        int budgetChars = Math.max(160, maxAnchorTokens * 2);
        if (budgetChars >= text.length()) {
            return new UserMessage(ANCHOR_PREFIX + text);
        }
        int headLen = (int) (budgetChars * 0.6);
        int tailLen = Math.max(40, budgetChars - headLen - 40);
        if (headLen + tailLen >= text.length()) {
            return new UserMessage(ANCHOR_PREFIX + text);
        }
        String truncated = text.substring(0, headLen)
                + "\n...[" + (text.length() - headLen - tailLen) + " chars truncated]...\n"
                + text.substring(text.length() - tailLen);
        log.info("[ConversationWindow] First-user anchor head+tail truncated ({} -> ~{} chars)",
                text.length(), truncated.length());
        return new UserMessage(ANCHOR_PREFIX + truncated);
    }

    /**
     * 计算摘要字数预算：被压缩内容 token 的 20%，不低于 500、不超过 3000。
     */
    private int computeSummaryBudget(List<Message> turnsToSummarize) {
        int contentTokens = TokenEstimator.estimateTokens(turnsToSummarize);
        int budget = (int) (contentTokens * properties.getSummaryBudgetRatio());
        return Math.max(properties.getSummaryBudgetFloor(),
                Math.min(budget, properties.getSummaryBudgetCeiling()));
    }

    // ==================== 工具结果处理 ====================

    /**
     * Backwards-compatible overload — older tool results that are oversized
     * stay verbatim because no {@link ToolResultStorage} target is in
     * scope. New call sites should use the 3-arg overload with explicit
     * {@code conversationId} and {@code workspaceBasePath} so oversized
     * bodies can be spilled to disk and recovered via {@code read_file}.
     */
    public List<Message> pruneOldToolResultsForModelInput(List<Message> messages) {
        return pruneOldToolResultsForModelInput(messages, null, null);
    }

    /**
     * Walk the messages newest-to-oldest, keeping the latest tool response
     * verbatim and applying space-saving rewrites to older ones:
     *
     * <ol>
     *   <li>Bodies already starting with {@link ToolResultStorage#SPILL_MARKER_PREFIX}
     *       were spilled at tool-execution time — pass through untouched.</li>
     *   <li>If a body matches an identical body already seen in a newer turn,
     *       replace it with a short "duplicate tool output omitted" placeholder
     *       (only above {@link #DEDUP_MIN_CHARS} so we don't bloat tiny acks).</li>
     *   <li>Otherwise, when a {@link ToolResultStorage} is wired and a
     *       conversation id is available, try
     *       {@link ToolResultStorage#persistIfOversized} to spill the raw
     *       bytes to disk and replace the inline body with a preview + path
     *       so the model can read_file the original on demand.</li>
     *   <li>If none of the above apply, leave the body verbatim. Bodies
     *       under the spill threshold or running without a storage hook are
     *       preserved exactly — the lossy "summarized for model context"
     *       single-liner that used to fire here destroyed enough context
     *       on long tasks to be the wrong default.</li>
     * </ol>
     *
     * <p>The {@link #PRUNE_EXEMPT_TOOLS} set still bypasses everything:
     * sub-agent delegations are not replayable, so their full transcript
     * stays in context.
     *
     * @param messages          full conversation in chronological order
     * @param conversationId    used to scope spill files; {@code null} disables spill
     * @param workspaceBasePath used to locate the spill directory; {@code null}
     *                          falls back through the storage's resolveBaseDir chain
     */
    public List<Message> pruneOldToolResultsForModelInput(List<Message> messages,
                                                          String conversationId,
                                                          String workspaceBasePath) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        int latestToolResponseIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                latestToolResponseIndex = i;
                break;
            }
        }
        if (latestToolResponseIndex <= 0) {
            return messages;
        }

        boolean canSpill = toolResultStorage != null
                && conversationId != null && !conversationId.isEmpty();

        List<Message> pruned = new ArrayList<>(messages);
        java.util.Set<String> seenLargeOutputs = new java.util.HashSet<>();
        int changed = 0;
        int spilled = 0;
        for (int i = pruned.size() - 1; i >= 0; i--) {
            if (!(pruned.get(i) instanceof ToolResponseMessage trm)) {
                continue;
            }
            boolean keepFull = i == latestToolResponseIndex;
            List<ToolResponseMessage.ToolResponse> newResponses = new ArrayList<>();
            boolean messageChanged = false;
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                String data = r.responseData();
                String name = r.name();
                boolean exempt = name != null && PRUNE_EXEMPT_TOOLS.contains(name);
                boolean alreadySpilled = data != null
                        && data.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX);

                // Pass through: the latest response, exempt tools, empty bodies,
                // already-spilled previews — none should be rewritten.
                if (keepFull || exempt || data == null || data.isEmpty() || alreadySpilled) {
                    newResponses.add(r);
                    if (data != null && data.length() > DEDUP_MIN_CHARS) {
                        seenLargeOutputs.add(data);
                    }
                    continue;
                }

                // Dedup: identical body seen in a later turn already.
                if (data.length() > DEDUP_MIN_CHARS && seenLargeOutputs.contains(data)) {
                    String replacement = "[" + name
                            + "] duplicate tool output omitted; same content appeared later.";
                    newResponses.add(new ToolResponseMessage.ToolResponse(r.id(), name, replacement));
                    messageChanged = true;
                    continue;
                }

                // Spill on demand: route oversized bodies to disk so the model
                // can read_file them rather than losing them to a lossy summary.
                if (canSpill) {
                    String candidate = toolResultStorage.persistIfOversized(
                            data, name, r.id(), conversationId, workspaceBasePath);
                    if (candidate != null
                            && candidate.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX)) {
                        newResponses.add(new ToolResponseMessage.ToolResponse(r.id(), name, candidate));
                        seenLargeOutputs.add(data);
                        messageChanged = true;
                        spilled++;
                        continue;
                    }
                    // returned unchanged: under threshold, excluded tool, or write failed.
                    // Fall through to "keep verbatim".
                }

                // Default: keep the body verbatim. Better to send a few extra
                // tokens than to silently destroy data the model might need.
                newResponses.add(r);
                if (data.length() > DEDUP_MIN_CHARS) {
                    seenLargeOutputs.add(data);
                }
            }
            if (messageChanged) {
                pruned.set(i, ToolResponseMessage.builder().responses(newResponses).build());
                changed++;
            }
        }
        if (changed > 0) {
            log.info("[ConversationWindow] Pruned {} older tool response message(s) ({} spilled to disk) before model request",
                    changed, spilled);
        }
        return changed > 0 ? pruned : messages;
    }

    /**
     * Spill-marker responses already point at an on-disk full copy via
     * {@code path=...} in their body. Trimming, replacing, or pre-pruning
     * them would destroy the very pointer the model needs to recover the
     * original output with {@code read_file} — which is the whole reason
     * we spilled in the first place. All three compaction phases consult
     * this guard before touching a response.
     */
    static boolean isSpillMarker(ToolResponseMessage.ToolResponse r) {
        return r != null
                && r.responseData() != null
                && r.responseData().startsWith(ToolResultStorage.SPILL_MARKER_PREFIX);
    }

    /**
     * Phase 1 - Soft trim：对工具结果做 head+tail 裁剪（保留首尾各 200 字符）。
     * <p>Spill-marker responses are left untouched so their on-disk pointer
     * survives intact across compaction.
     */
    int softTrimToolResults(List<Message> messages) {
        int trimmed = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> newResponses = new ArrayList<>();
                boolean changed = false;
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (isSpillMarker(r)) {
                        // Pointer + preview already; trimming would lose the path.
                        newResponses.add(r);
                        continue;
                    }
                    String data = r.responseData();
                    if (data != null && data.length() > 500) {
                        String head = data.substring(0, 200);
                        String tail = data.substring(data.length() - 200);
                        newResponses.add(new ToolResponseMessage.ToolResponse(
                                r.id(), r.name(), head + "\n...[trimmed " + data.length() + " chars]...\n" + tail));
                        changed = true;
                    } else {
                        newResponses.add(r);
                    }
                }
                if (changed) {
                    messages.set(i, ToolResponseMessage.builder().responses(newResponses).build());
                    trimmed++;
                }
            }
        }
        return trimmed;
    }

    /**
     * Phase 2 - Hard clear：将所有旧工具结果替换为占位符。
     * <p>Spill-marker responses are left untouched so the on-disk pointer
     * survives — a placeholder here would force the model to abandon a
     * tool output it could otherwise recover via {@code read_file}.
     */
    int hardClearToolResults(List<Message> messages) {
        int cleared = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage trm) {
                boolean changed = false;
                List<ToolResponseMessage.ToolResponse> replaced = new ArrayList<>(trm.getResponses().size());
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    if (isSpillMarker(r)) {
                        replaced.add(r);
                        continue;
                    }
                    replaced.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(), "[tool result removed]"));
                    changed = true;
                }
                if (changed) {
                    messages.set(i, ToolResponseMessage.builder().responses(replaced).build());
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /**
     * Phase 3 Pre-prune：在 LLM 摘要前，将工具输出替换为占位符（减少摘要输入 token）。
     * <p>Spill-marker responses are left untouched so the summary input
     * still has the on-disk path the model might cite back in its summary.
     */
    int prePruneForSummary(List<Message> messages) {
        int pruned = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage trm) {
                boolean hasSubstantial = trm.getResponses().stream()
                        .anyMatch(r -> !isSpillMarker(r)
                                && r.responseData() != null
                                && r.responseData().length() > 200);
                if (hasSubstantial) {
                    List<ToolResponseMessage.ToolResponse> placeholders = new ArrayList<>(trm.getResponses().size());
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        if (isSpillMarker(r)) {
                            placeholders.add(r);
                            continue;
                        }
                        placeholders.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(),
                                "[旧工具输出已清理以节省上下文空间]"));
                    }
                    messages.set(i, ToolResponseMessage.builder().responses(placeholders).build());
                    pruned++;
                }
            }
        }
        return pruned;
    }

    // ==================== LLM 摘要生成（结构化 + 迭代更新） ====================

    /**
     * 生成结构化摘要。支持首次压缩和迭代更新两种模式。
     * 包含冷却机制：LLM 调用失败后 10 分钟内不重试。
     */
    private String generateSummary(List<Message> oldMessages, ChatModel chatModel,
                                   String conversationId, int summaryBudget,
                                   String memoryExtraContext) {
        // 冷却检查
        if (isInSummaryCooldown(conversationId)) {
            log.info("[ConversationWindow] 摘要在冷却中，跳过 LLM 调用, conv={}", conversationId);
            return null;
        }

        try {
            String conversationText = serializeForSummary(oldMessages);

            // 如果 MemoryProvider 有额外上下文，追加到对话文本中
            if (memoryExtraContext != null && !memoryExtraContext.isBlank()) {
                conversationText += "\n\n[Memory Provider 补充上下文]\n" + memoryExtraContext;
            }

            String previousSummary = previousSummaries.get(conversationId);
            String systemPrompt;
            String userPrompt;

            // System prompt always carries the budget directive; both branches
            // must replace the placeholder. The previous code applied the
            // replace only on the first-compression branch, so iterative-mode
            // calls leaked the literal "{summary_budget}" string to the LLM.
            systemPrompt = STRUCTURED_SUMMARY_SYSTEM
                    .replace("{summary_budget}", String.valueOf(summaryBudget));
            if (previousSummary != null) {
                // Iterative update: previous summary + new turns.
                userPrompt = STRUCTURED_SUMMARY_UPDATE
                        .replace("{previous_summary}", previousSummary)
                        .replace("{conversation}", conversationText);
                log.debug("[ConversationWindow] 使用迭代更新模式（第 {} 次压缩）, conv={}",
                        compressionCounts.getOrDefault(conversationId, 0) + 1, conversationId);
            } else {
                userPrompt = STRUCTURED_SUMMARY_USER
                        .replace("{conversation}", conversationText);
                log.debug("[ConversationWindow] 使用首次压缩模式, conv={}", conversationId);
            }

            List<Message> promptMessages = new ArrayList<>();
            promptMessages.add(new SystemMessage(systemPrompt));
            promptMessages.add(new UserMessage(userPrompt));

            ChatOptions options = DashScopeChatOptions.builder()
                    .withMaxToken(properties.getSummaryMaxTokens())
                    .build();

            ChatResponse response = chatModel.call(new Prompt(promptMessages, options));
            if (response != null && response.getResult() != null
                    && response.getResult().getOutput() != null) {
                String summary = response.getResult().getOutput().getText();
                if (summary != null && !summary.isBlank()) {
                    // 成功：保存摘要供下次迭代更新，清除冷却
                    previousSummaries.put(conversationId, summary);
                    clearSummaryCooldown(conversationId);
                    return summary;
                }
            }
            log.warn("[ConversationWindow] LLM 摘要返回空结果, conv={}", conversationId);
            setSummaryCooldown(conversationId);
            return null;

        } catch (Exception e) {
            log.warn("[ConversationWindow] LLM 摘要生成失败（进入 {} 秒冷却）: {}, conv={}",
                    SUMMARY_COOLDOWN_MS / 1000, e.getMessage(), conversationId);
            setSummaryCooldown(conversationId);
            return null;
        }
    }

    // ==================== 消息序列化（智能截断） ====================

    /**
     * 将消息列表序列化为摘要 LLM 可消化的文本格式。
     * 长内容做 head+tail 截断，比简单截断保留更多信息。
     */
    private String serializeForSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = switch (msg) {
                case UserMessage ignored -> "[USER]";
                case SystemMessage ignored -> "[SYSTEM]";
                case AssistantMessage ignored -> "[ASSISTANT]";
                case ToolResponseMessage ignored -> "[TOOL RESULT]";
                default -> "[OTHER]";
            };

            String text = msg.getText();
            if (text != null && text.length() > CONTENT_MAX) {
                text = text.substring(0, CONTENT_HEAD)
                        + "\n...[截断 " + text.length() + " 字符]...\n"
                        + text.substring(text.length() - CONTENT_TAIL);
            }

            sb.append(role).append(": ").append(text != null ? text : "").append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 二次裁剪：从前往后移除消息直到 token 预算满足。
     */
    private List<Message> trimToFit(List<Message> messages, int budget) {
        int startIndex = 0;
        int totalTokens = TokenEstimator.estimateTokens(messages);

        while (totalTokens > budget && startIndex < messages.size() - 2) {
            totalTokens -= TokenEstimator.estimateTokens(messages.get(startIndex));
            startIndex++;
        }

        if (startIndex > 0) {
            log.info("[ConversationWindow] 二次裁剪移除 {} 条消息, 最终 {} tokens", startIndex, totalTokens);
            return new ArrayList<>(messages.subList(startIndex, messages.size()));
        }
        return messages;
    }

    // ==================== PTL 紧急压缩 ====================

    /**
     * PTL (Prompt Too Long) 恢复用的紧急压缩。
     * 不调用 LLM 摘要，直接丢弃较旧消息，只保留最近 4 条。
     */
    public List<Message> compactForRetry(List<Message> messages) {
        if (messages == null || messages.size() <= 2) {
            return null;
        }

        int preserveCount = Math.min(4, messages.size());
        int splitPoint = messages.size() - preserveCount;

        if (splitPoint <= 0) {
            return null;
        }

        List<Message> recentMessages = new ArrayList<>(messages.subList(splitPoint, messages.size()));
        log.info("[ConversationWindow] PTL 紧急压缩: {} -> {} 条消息 (丢弃 {} 条旧消息)",
                messages.size(), recentMessages.size(), splitPoint);
        return recentMessages;
    }

    // ==================== 冷却机制 ====================

    private boolean isInSummaryCooldown(String conversationId) {
        Long until = summaryCooldownUntil.get(conversationId);
        return until != null && System.currentTimeMillis() < until;
    }

    private void setSummaryCooldown(String conversationId) {
        summaryCooldownUntil.put(conversationId, System.currentTimeMillis() + SUMMARY_COOLDOWN_MS);
    }

    private void clearSummaryCooldown(String conversationId) {
        summaryCooldownUntil.remove(conversationId);
    }

    // ==================== 缓存管理 ====================

    private void evictExpiredEntries() {
        summaryCache.entrySet().removeIf(entry -> entry.getValue().isExpired(CACHE_TTL_MS));
    }

    record CachedSummary(String summary, long createdAt) {
        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - createdAt > ttlMs;
        }
    }
}
