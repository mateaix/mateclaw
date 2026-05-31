package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.context.LoopBudgetConfig;
import vip.mate.agent.context.LoopMessageBudgeter;
import vip.mate.agent.context.RuntimeContextInjector;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.agent.graph.state.FinishReason;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.graph.state.SourceEvidenceLedger;

import vip.mate.channel.web.ChatStreamTracker;

import java.util.*;
import java.util.concurrent.CancellationException;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 推理节点（ReAct Thought 阶段）
 * <p>
 * 调用 LLM 进行单次推理，判断是否需要工具调用。
 * 关键：通过 internalToolExecutionEnabled=false 禁用 ChatModel 内部工具循环，
 * 使 StateGraph 完全控制 ReAct 循环。
 * <p>
 * 支持 forced_tool_call 机制：当审批通过后的重放请求到达时，
 * 跳过 LLM 调用，直接发出预批准的工具调用。
 * <p>
 * 使用 {@link NodeStreamingChatHelper} 进行流式调用，实时推送 content/thinking 增量。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ReasoningNode implements NodeAction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static MateClawStateAccessor.OutputBuilder reasonOutput() {
        return MateClawStateAccessor.output();
    }

    /**
     * 单次 LLM 调用的默认最大输出 token 数，防止退化输出无限生成。
     * <p>
     * RFC-049 follow-up (2026-04-27): bumped 4096 → 16384. 4096 was hitting
     * the cap when models emit large generative tool_call args (e.g. renderDocx
     * with a multi-thousand-character markdown body) on top of thinking
     * content for reasoning_effort=high — the JSON args got truncated mid-
     * stream, the tool failed to parse, the docx was never generated. 16k is
     * the conservative ceiling that covers typical "write a long document"
     * tool calls without enabling true runaway loops (those are bounded by
     * iteration count, not per-call tokens).
     */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 16384;

    /**
     * Stateless singleton used to budget the per-iteration working message
     * list. Static-final because the budgeter holds no mutable state — the
     * choice keeps the existing ReasoningNode constructor surface unchanged
     * (it already carries 13 parameters across 5 overloads) and makes the
     * dependency obvious to anyone reading the class.
     */
    private static final LoopMessageBudgeter LOOP_BUDGETER = new LoopMessageBudgeter();

    /**
     * Fallback context window used when no provider-level value is wired in.
     * Calibrated to the same default {@code ConversationWindowProperties}
     * uses for its multi-turn budget so the two layers stay in sync. Models
     * with smaller windows still benefit — the budgeter triggers earlier on
     * raw message volume via {@code absoluteMaxMessages}.
     */
    private static final int DEFAULT_LOOP_CONTEXT_WINDOW_TOKENS = 128_000;

    /**
     * Conservative buffer added to the per-loop budget's reservedPrefixTokens
     * to cover non-history prompt segments that are appended <em>after</em>
     * the budget runs: the runtime-rendered skill catalog, runtime-context
     * snapshot, wiki injection, progress ledger snapshot, and assorted
     * marker SystemMessages. Underestimating here only delays the trigger
     * slightly; loop invariants (anchor preservation, tool-pair integrity)
     * are unaffected. Sized for a typical agent with 20–30 skills and
     * moderate wiki content.
     */
    private static final int LOOP_PREFIX_AUXILIARY_RESERVE_TOKENS = 4_000;

    /**
     * DashScope's native chat API caps {@code max_tokens} at 8192 and returns a
     * 400 {@code InvalidParameter} ("Range of max_tokens should be [1, 8192]")
     * for anything larger. The failover layer misclassifies that 400 as
     * "model not found" and silently switches to a different provider, so the
     * per-call ceiling must be clamped to this value for DashScope-backed
     * models — keeping {@link #DEFAULT_MAX_OUTPUT_TOKENS} for every other
     * provider that does accept the larger budget.
     */
    private static final int DASHSCOPE_MAX_OUTPUT_TOKENS = 8192;

    /**
     * Max times to re-prompt the model when it returns a completely empty turn
     * (no tool call, no content, no thinking) before accepting termination.
     * A blank turn is otherwise treated as a final answer and ends the run; on
     * long multi-step tasks that surfaces as the agent quitting mid-way.
     */
    private static final int MAX_EMPTY_COMPLETION_RETRIES = 2;

    /**
     * Number of newest tool-response messages kept verbatim in the model
     * input; older ones have their bodies collapsed to a one-line "old
     * output cleared" placeholder while keeping the toolCallId / tool name
     * so the assistant/tool pairing remains valid. The latest few results
     * are what the model is reasoning over right now — beyond that, the
     * content is history and re-call (or read_file on the spill path) is
     * cheaper than carrying every previous body forward across iterations.
     */
    private static final int KEEP_RECENT_TOOL_RESPONSES = 3;

    /** Continuation nudge appended to the prompt when the model returns an empty turn. */
    private static final String EMPTY_COMPLETION_NUDGE =
            "Your previous turn was empty. If the task is not yet complete, continue now "
            + "with the next concrete step — call a tool or write the next part. If every "
            + "required step is already done, output the final answer to the user now.";

    /**
     * A turn carrying no tool call, no content, and no thinking is not a usable
     * answer — it would route to the final-answer branch as an empty string and
     * terminate the run. Fatal / prompt-too-long / partial results are handled by
     * their own branches and must not be misread as "empty".
     */
    static boolean isEmptyCompletion(NodeStreamingChatHelper.StreamResult result) {
        if (result == null || result.hasToolCalls() || result.hasFatalError()
                || result.isPromptTooLong() || result.partial()) {
            return false;
        }
        boolean noContent = result.text() == null || result.text().isBlank();
        boolean noThinking = result.thinking() == null || result.thinking().isBlank();
        return noContent && noThinking;
    }

    /**
     * Tool-use enforcement clause appended to every ReasoningNode
     * system prompt. Treats narration ("I will now …") as a protocol violation
     * to prevent the recurring failure mode where a model says it will call a
     * tool but emits the description as final_answer text instead.
     */
    private static final String TOOL_USE_ENFORCEMENT = "\n\n"
            + "## 工具调用纪律（必读）\n\n"
            + "- 你**必须**直接调用工具来产生结果，不允许只用文字描述\"接下来要做什么\"。\n"
            + "- 当你说要执行某个动作（如生成文件、发送消息、调用接口、生成 docx），\n"
            + "  你**必须**在同一条回复里**立即发出对应的 tool_call**，不允许只写文字承诺。\n"
            + "- 禁止以\"现在 / 接下来 / 我将 / 直接生成 / 我直接\"+动作描述结束本轮回复——\n"
            + "  这种叙述会让系统误判任务已完成，**实际上工具没被调用**，结果文件不会产生。\n"
            + "- 如果上一次工具调用因 args JSON 截断（max_tokens 超限）失败，\n"
            + "  请重新调用同一工具但**缩小内容**，或拆成多次顺序调用，**不要改成纯文字回答**。\n"
            + "- 只在确实没有合适工具，或所有工具步骤都已完成、可以最终回答用户时，\n"
            + "  才输出无 tool_call 的纯文字回答。\n\n"
            + "## 进度跟踪（多步任务强制规则，不可绕过）\n\n"
            + "**触发条件**：用户的任务包含 ≥3 个可枚举子目标 — 比如\n"
            + "\"调研 10 个模型\"、\"逐节起草报告\"、\"批量生成 N 份文档\"、\n"
            + "\"依次调用 N 个 API\"、\"对每个文件执行同一操作\"等。\n\n"
            + "**必须做的事**：\n"
            + "1. **第一轮回复就用并行 tool_calls 批量注册全部子目标为 `pending`**\n"
            + "   一条回复里 N 个 `progress_update` 同时发出（不要串行）。\n"
            + "   例：要调研 10 个模型，第一轮就发 10 个 `progress_update(stepKey=\"model_xxx\", status=\"pending\")`。\n"
            + "2. **每开始一个子目标**前发 `progress_update(同 stepKey, status=\"in_progress\")`。\n"
            + "3. **每完成一个子目标**后立即发 `progress_update(同 stepKey, status=\"done\")`。\n"
            + "4. **无法继续**时发 `progress_update(同 stepKey, status=\"blocked\", note=\"具体原因\")`。\n\n"
            + "**为什么必须**：\n"
            + "- 系统在你**每一次推理前**注入一份 \"## 当前任务进度\" 快照。\n"
            + "  这是你**唯一可信**的\"已完成清单\"——比你记忆里的步骤更权威，因为上下文窗口\n"
            + "  会被裁剪，老的工具调用记录会消失，但 ledger 不会。\n"
            + "- 不维护 ledger 的后果（实测）：\n"
            + "  · 上下文裁剪后忘记自己做过的步骤，重复执行已完成项 → 浪费迭代预算\n"
            + "  · 漏做项目 → 任务不完整 → 撞 max_iterations 还没干完\n"
            + "  · ledger snapshot 永远显示初始状态，对你毫无帮助\n\n"
            + "**例外**：单一问题、简单问答、不可拆解的请求 — 不需要用。\n";

    private final ChatModel chatModel;
    private final List<ToolCallback> toolCallbacks;
    /**
     * Full agent tool set, used for the per-turn disclosure split. Null in the
     * legacy {@code (ChatModel, List)} path — that path falls back to
     * {@link #toolCallbacks} verbatim with no split.
     */
    private final AgentToolSet toolSet;
    /**
     * Splits tools into core + already-enabled extensions per
     * {@code ENABLED_EXTENSION_TOOLS}. Null disables the split (advertise the
     * full {@link #toolCallbacks}).
     */
    private final vip.mate.tool.disclosure.ToolDisclosureService toolDisclosureService;
    private final String reasoningEffort;
    /**
     * PR-1.2 (RFC-049 L1-B): Whether the bound model's {@code ModelFamily} accepts
     * {@code reasoning_effort}. Drives the capability gate in
     * {@link #resolveEffectiveReasoningEffort()} so that a front-end {@code ThinkingLevelHolder}
     * override is dropped on chat-type models that cannot honor it.
     */
    private final boolean supportsReasoningEffort;
    private final NodeStreamingChatHelper streamingHelper;
    private final ConversationWindowManager conversationWindowManager;
    private final ChatStreamTracker streamTracker;
    private final int maxOutputTokens;
    /** Wiki 相关性注入（可选，null 时跳过） */
    private final vip.mate.wiki.service.WikiContextService wikiContextService;
    /**
     * Renders the {@code ## Skills} catalog each turn so its ordering reacts to
     * skills loaded this run (load_skill pins). Null in legacy / test
     * constructors — when null, no catalog segment is appended.
     */
    private final vip.mate.skill.runtime.SkillCatalogRenderer skillCatalogRenderer;

    /**
     * Loads the per-conversation progress ledger each reasoning step so a
     * compact snapshot can be injected into {@code nonHistoryPrefix} —
     * surviving message-window trims so the agent never loses track of
     * "what is already done" on long multi-step tasks. Null in legacy /
     * test constructors; when null the snapshot block is suppressed and
     * the prompt is identical to pre-feature behavior.
     */
    private final vip.mate.agent.progress.ProgressLedgerService progressLedgerService;

    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker) {
        this(chatModel, toolSet, reasoningEffort, streamingHelper, conversationWindowManager,
                streamTracker, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker, int maxOutputTokens) {
        this(chatModel, toolSet, reasoningEffort, streamingHelper, conversationWindowManager,
                streamTracker, maxOutputTokens, null);
    }

    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker, int maxOutputTokens,
                         vip.mate.wiki.service.WikiContextService wikiContextService) {
        // Backward-compatible delegate. Callers that have not migrated to the explicit
        // supportsReasoningEffort parameter inherit the pre-PR-1 behavior: treat the bound
        // model as supporting reasoning_effort iff reasoningEffort was resolved to a non-null
        // value at construction time. New callers (AgentGraphBuilder) should use the
        // 9-arg constructor below.
        this(chatModel, toolSet, reasoningEffort, reasoningEffort != null,
                streamingHelper, conversationWindowManager, streamTracker,
                maxOutputTokens, wikiContextService);
    }

    /**
     * PR-1.2 (RFC-049): Primary constructor with explicit {@code supportsReasoningEffort}
     * capability flag — avoids inferring capability from {@code reasoningEffort == null},
     * which fails for a future "supports but not auto-enabled" scenario.
     */
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         boolean supportsReasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker, int maxOutputTokens,
                         vip.mate.wiki.service.WikiContextService wikiContextService) {
        this(chatModel, toolSet, reasoningEffort, supportsReasoningEffort, streamingHelper,
                conversationWindowManager, streamTracker, maxOutputTokens, wikiContextService, null);
    }

    /**
     * Primary constructor with the runtime {@link vip.mate.skill.runtime.SkillCatalogRenderer}.
     * The catalog is rendered each turn (ordered by skills loaded this run)
     * instead of being baked into the system prompt, so the prompt-cache prefix
     * stays stable and load_skill pins float to the top.
     */
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         boolean supportsReasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker, int maxOutputTokens,
                         vip.mate.wiki.service.WikiContextService wikiContextService,
                         vip.mate.skill.runtime.SkillCatalogRenderer skillCatalogRenderer) {
        this(chatModel, toolSet, reasoningEffort, supportsReasoningEffort, streamingHelper,
                conversationWindowManager, streamTracker, maxOutputTokens, wikiContextService,
                skillCatalogRenderer, null);
    }

    /**
     * Backward-compatible delegate for callers built before the
     * {@link vip.mate.agent.progress.ProgressLedgerService} was wired in —
     * passes {@code null} so the progress snapshot block is suppressed.
     * New call sites should use the 13-arg primary constructor below.
     */
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         boolean supportsReasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker, int maxOutputTokens,
                         vip.mate.wiki.service.WikiContextService wikiContextService,
                         vip.mate.skill.runtime.SkillCatalogRenderer skillCatalogRenderer,
                         vip.mate.tool.disclosure.ToolDisclosureService toolDisclosureService) {
        this(chatModel, toolSet, reasoningEffort, supportsReasoningEffort, streamingHelper,
                conversationWindowManager, streamTracker, maxOutputTokens, wikiContextService,
                skillCatalogRenderer, toolDisclosureService, null);
    }

    /**
     * Primary constructor with the {@link vip.mate.agent.progress.ProgressLedgerService}.
     * When non-null, a compact snapshot of the conversation's progress ledger
     * is appended to {@code nonHistoryPrefix} each turn so the agent retains
     * its "what is already done" view across message-window trims.
     */
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         boolean supportsReasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker, int maxOutputTokens,
                         vip.mate.wiki.service.WikiContextService wikiContextService,
                         vip.mate.skill.runtime.SkillCatalogRenderer skillCatalogRenderer,
                         vip.mate.tool.disclosure.ToolDisclosureService toolDisclosureService,
                         vip.mate.agent.progress.ProgressLedgerService progressLedgerService) {
        this.chatModel = chatModel;
        this.toolSet = toolSet;
        this.toolCallbacks = toolSet.callbacks();
        this.toolDisclosureService = toolDisclosureService;
        this.reasoningEffort = reasoningEffort;
        this.supportsReasoningEffort = supportsReasoningEffort;
        this.streamingHelper = streamingHelper;
        this.conversationWindowManager = conversationWindowManager;
        this.streamTracker = streamTracker;
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        this.wikiContextService = wikiContextService;
        this.skillCatalogRenderer = skillCatalogRenderer;
        this.progressLedgerService = progressLedgerService;
    }

    /**
     * Context window used by the per-loop budgeter. Returns the
     * conversation-window manager's effective max input tokens when one is
     * wired in (so L1 and L2 stay calibrated to the same model window),
     * otherwise the documented fallback.
     */
    private int loopContextWindowTokens() {
        if (conversationWindowManager != null) {
            int v = conversationWindowManager.getDefaultMaxInputTokens();
            if (v > 0) return v;
        }
        return DEFAULT_LOOP_CONTEXT_WINDOW_TOKENS;
    }

    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager) {
        this(chatModel, toolSet, reasoningEffort, streamingHelper, conversationWindowManager, null);
    }

    /** @deprecated */
    @Deprecated
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort) {
        this(chatModel, toolSet, reasoningEffort, null, null);
    }

    /** @deprecated */
    @Deprecated
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet) {
        this(chatModel, toolSet, null, null, null);
    }

    /** @deprecated */
    @Deprecated
    public ReasoningNode(ChatModel chatModel, List<ToolCallback> toolCallbacks) {
        this.chatModel = chatModel;
        this.toolSet = null;
        this.toolCallbacks = toolCallbacks;
        this.toolDisclosureService = null;
        this.reasoningEffort = null;
        this.supportsReasoningEffort = false;
        this.streamingHelper = null;
        this.conversationWindowManager = null;
        this.streamTracker = null;
        this.maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
        this.wikiContextService = null;
        this.skillCatalogRenderer = null;
        this.progressLedgerService = null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        // ======= 取消检查（LLM 调用前，尚未计数） =======
        String conversationId = accessor.conversationId();
        if (streamTracker != null && streamTracker.isStopRequested(conversationId)) {
            log.info("[ReasoningNode] Stop requested before LLM call, aborting");
            throw new CancellationException("Stream stopped by user");
        }

        // ======= forced_tool_call 检测：审批通过后的重放（不计入 LLM 调用） =======
        String forcedToolCallJson = accessor.forcedToolCall();
        if (!forcedToolCallJson.isEmpty()) {
            try {
                log.info("[ReasoningNode] Detected forced_tool_call, skipping LLM, emitting tool call directly");

                AssistantMessage.ToolCall toolCall = deserializeToolCall(forcedToolCallJson);

                AssistantMessage syntheticMsg = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();

                return reasonOutput()
                        .needsToolCall(true)
                        .toolCalls(List.of(toolCall))
                        .messages(List.of((Message) syntheticMsg))
                        .iterationCount(accessor.iterationCount() + 1)
                        .forcedToolCall("")
                        .currentPhase("forced_replay")
                        .contentStreamed(true)
                        .thinkingStreamed(true)
                        .events(List.of(GraphEventPublisher.phase("forced_replay", Map.of(
                                "toolName", toolCall.name(),
                                "iteration", accessor.iterationCount() + 1))))
                        .build();
            } catch (Exception e) {
                log.error("[ReasoningNode] Failed to deserialize forced_tool_call, falling through to normal LLM: {}",
                        e.getMessage());
            }
        }

        // ======= 构建 Prompt =======
        String systemPrompt = accessor.systemPrompt();
        // Append a tool-use enforcement clause to every ReasoningNode call.
        // Without it, some models (notably DeepSeek thinking and Claude Opus)
        // tend to "narrate" — emit a final_answer like "现在直接生成立项材料
        // docx" instead of actually calling renderDocx, which makes the
        // graph silently terminate at final_answer_node with the narration
        // as the user-facing reply.
        //
        // Appended at runtime rather than woven into the AgentEntity-stored
        // prompt so it stays out of the user-editable agent UI but is still
        // always-on for the runtime LLM.
        systemPrompt = systemPrompt + TOOL_USE_ENFORCEMENT;
        List<Message> messages = accessor.messages();

        // Per-loop budget: bound the working message list a single Reasoning
        // iteration hands to the LLM. The previous fixed head=4 + tail=36 cut
        // could lose the latest UserMessage once the ReAct loop accumulated
        // tool calls/observations past ~70 messages — the user's question
        // fell into the dropped middle, the LLM lost it, and the agent
        // answered off-topic. LoopMessageBudgeter anchors the latest
        // UserMessage as undroppable, sizes the tail by token budget instead
        // of message count, and keeps the same bidirectional tool-pair
        // integrity guard the old block already had. The L2 trim here is
        // distinct from ConversationWindowManager (L1): L1 runs once per
        // user turn and produces an LLM summary for multi-turn history; L2
        // runs per reasoning iteration on what L1 already produced plus
        // intra-turn tool-call growth.
        //
        // Reserved prefix tokens cover the non-history portion of the
        // prompt the LLM will receive: system prompt (with tool-use
        // enforcement already appended), tool schemas, output reserve,
        // and a buffer for skill catalog + runtime context + wiki
        // injections that are added downstream. Underestimating here only
        // delays the trigger slightly — invariants (anchor, pair integrity)
        // still hold once budget fires.
        int systemTokens = TokenEstimator.estimateTokens(systemPrompt);
        int toolsTokens = TokenEstimator.estimateToolsTokens(toolCallbacks);
        int loopReservedPrefixTokens = systemTokens + toolsTokens
                + maxOutputTokens + LOOP_PREFIX_AUXILIARY_RESERVE_TOKENS;
        LoopBudgetConfig loopCfg = LoopBudgetConfig.forContext(loopContextWindowTokens())
                .withReservedPrefixTokens(loopReservedPrefixTokens);
        LoopMessageBudgeter.Result budgeted = LOOP_BUDGETER.budget(messages, loopCfg);
        // Only log when the budget actually modified the list — a triggered-
        // but-no-op pass is normal (history fits comfortably under the tail
        // budget) and would otherwise spam logs every iteration.
        if (budgeted.trace().modified()) {
            LoopMessageBudgeter.BudgetTrace t = budgeted.trace();
            log.warn("[ReasoningNode] Loop budget trim: {} -> {} msgs (history {} -> {} tokens, "
                    + "prefix~{}), head={}, tail={}, droppedMiddle={}, orphans={}, "
                    + "anchorEnforced={}, anchorStitched={}, targetMaxTripped={}, "
                    + "capExceededForPairIntegrity={}, minTailFloorApplied={}, conv={}",
                    t.originalCount(), t.finalCount(), t.originalTokens(), t.finalTokens(),
                    t.reservedPrefixTokens(),
                    t.headKept(), t.tailKept(), t.droppedMiddle(), t.orphansRemoved(),
                    t.anchorEnforced(), t.anchorStitched(), t.targetMaxTripped(),
                    t.capExceededForPairIntegrity(), t.minTailFloorApplied(), conversationId);
        }
        messages = budgeted.messages();

        String workspaceBasePath = state.value(vip.mate.agent.graph.state.MateClawStateKeys.WORKSPACE_BASE_PATH, "");
        String agentIdStr = state.value(MateClawStateKeys.AGENT_ID, "");
        String userMsg = state.value(MateClawStateKeys.USER_MESSAGE, "");

        // Build the non-history prefix ONCE. The PTL retry branch below
        // reuses this list verbatim so the retried prompt has exactly the
        // same system / runtime context / wiki injection as the original —
        // the previous tail-only retry path silently dropped the wiki
        // segment which led to "answer regressed after compaction"
        // complaints on long sessions.
        List<Message> nonHistoryPrefix = buildNonHistoryPrefix(systemPrompt, workspaceBasePath, agentIdStr, userMsg,
                accessor.chatOrigin());

        // Append the runtime-rendered skill catalog as a SEPARATE SystemMessage
        // right after the skeleton system prompt. Keeping it out of the baked
        // prompt keeps the stable prefix's prompt-cache hash intact, while
        // re-rendering each turn lets skills loaded this run (load_skill) pin
        // to the top of the catalog. Reused verbatim by the PTL retry branch.
        if (skillCatalogRenderer != null) {
            String skillCatalog = skillCatalogRenderer.render(accessor.loadedSkills());
            if (skillCatalog != null && !skillCatalog.isBlank()) {
                nonHistoryPrefix.add(1, new SystemMessage(skillCatalog));
            }
        }

        // Inject the conversation's progress-ledger snapshot as a separate
        // SystemMessage. Sits in nonHistoryPrefix (never trimmed) so the
        // agent always sees its own "what's done / what's pending" record
        // even after the message-window trim above drops the tool-call
        // history that produced those done entries. Suppressed when the
        // ledger column is empty so short single-turn questions stay
        // prompt-cache-friendly.
        //
        // Past iteration ~10, also emit a stale-reminder SystemMessage when
        // the ledger looks abandoned (empty after many turns, or no
        // progress_update in >90s). This pushes the model back to the
        // ledger discipline before it drifts into the "I'm doing the work
        // but never marking it" failure mode observed in round-4 of the
        // LLM-review smoke test.
        if (progressLedgerService != null && conversationId != null && !conversationId.isBlank()) {
            try {
                vip.mate.agent.progress.ProgressLedger ledger =
                        progressLedgerService.load(conversationId);
                String snapshot = ledger.renderSnapshot();
                if (snapshot != null) {
                    nonHistoryPrefix.add(new SystemMessage(snapshot));
                }
                String staleReminder = ledger.renderStaleReminder(
                        accessor.iterationCount(), java.time.Instant.now());
                if (staleReminder != null) {
                    nonHistoryPrefix.add(new SystemMessage(staleReminder));
                    log.info("[ReasoningNode] Injected stale-ledger reminder at iter {} for conv {}",
                            accessor.iterationCount(), conversationId);
                }
            } catch (Exception e) {
                // Never let a ledger-side failure break the reasoning step.
                log.warn("[ReasoningNode] Failed to load progress ledger for {}: {}",
                        conversationId, e.getMessage());
            }
        }

        if (conversationWindowManager != null) {
            // Age-based compaction first: drop the body of tool responses
            // older than the K most recent into a one-line placeholder that
            // keeps the toolCallId / tool name (so the assistant/tool pair
            // stays valid) and, for spilled bodies, preserves the on-disk
            // path so read_file can still recover the original. Without
            // this, even spilled previews (~1-2 KB each) accumulate across
            // 30+ tool calls and bloat the prompt the model sees every turn.
            messages = conversationWindowManager.compactAgedToolResponses(
                    messages, KEEP_RECENT_TOOL_RESPONSES);
            // Pass conversationId + workspaceBasePath so oversized older
            // tool results can be spilled to the workspace spill directory
            // (preserving the full body for read_file recovery) instead of
            // being rewritten into a lossy single-line summary.
            messages = conversationWindowManager.pruneOldToolResultsForModelInput(
                    messages, conversationId, workspaceBasePath);
        }
        List<Message> promptMessages = new ArrayList<>(nonHistoryPrefix);
        promptMessages.addAll(messages);

        // 请求级思考深度覆盖（ThinkingLevelHolder 由 AgentService 设置）
        String effectiveReasoning = resolveEffectiveReasoningEffort();
        log.info("[ReasoningNode] thinkingLevel={}, effectiveReasoningEffort={}, nodeDefault={}",
                ThinkingLevelHolder.get(), effectiveReasoning, this.reasoningEffort);

        // Progressive disclosure: advertise only core tools plus the extensions
        // enabled this run, computed fresh each turn from ENABLED_EXTENSION_TOOLS
        // so an enable_tool call earlier in this loop takes effect immediately.
        // Falls back to the full tool set when no disclosure service is wired.
        List<ToolCallback> activeCallbacks = (toolDisclosureService != null && toolSet != null)
                ? toolDisclosureService.split(toolSet, accessor.enabledExtensionTools()).activeCallbacks()
                : toolCallbacks;

        ChatOptions options = buildChatOptions(effectiveReasoning, activeCallbacks);

        Prompt prompt = new Prompt(promptMessages, options);

        // ======= LLM 调用区域 =======
        // nextLlmCallCount 在首次 streamCall 之前计算。
        // 所有退出路径（正常、stopped、fatal error、CancellationException）都必须写回此值。
        // PTL compact retry 会再 +1。
        int nextLlmCallCount = accessor.llmCallCount() + 1;
        log.debug("[ReasoningNode] Calling LLM with {} messages, {} tool definitions, iteration {}/{}, llmCallCount={}",
                promptMessages.size(), activeCallbacks.size(),
                accessor.iterationCount(), accessor.maxIterations(), nextLlmCallCount);

        GraphEventPublisher.GraphEvent phaseEvent = GraphEventPublisher.phase("reasoning",
                Map.of("iteration", accessor.iterationCount()));
        // Iteration boundary marker for the parent ReAct loop. Reason
        // distinguishes the very first turn of the conversation from a
        // mid-loop repeat for consumers grouping events into per-turn cards.
        boolean iterationEventsOn = streamTracker == null || streamTracker.isIterationEventsEnabled();
        GraphEventPublisher.GraphEvent iterStartEvent = iterationEventsOn
                ? GraphEventPublisher.iterationStart(
                        accessor.iterationCount(),
                        accessor.iterationCount() == 0 ? "first_turn" : "react_step",
                        "parent",
                        null)
                : null;
        pushPhase(conversationId, "reasoning", Map.of(
                "iteration", accessor.iterationCount(),
                "llmCallCount", nextLlmCallCount
        ));

        NodeStreamingChatHelper.StreamResult result;
        try {
            result = streamingHelper.streamCall(chatModel, prompt, conversationId, "reasoning");

            // PTL 处理：结构化压缩后重试。复用 nonHistoryPrefix 保证重试
            // Prompt 仍带 wiki / runtime context；早期的 tail-only 路径会把
            // wiki 段一起丢掉，重试后的 prompt 比原始更短少一层信息。
            if (result.isPromptTooLong() && conversationWindowManager != null) {
                log.warn("[ReasoningNode] Prompt too long, attempting STRUCTURED compaction and retry");

                // MateClawStateAccessor.agentId() returns String per state
                // schema; the ConversationWindowManager hook expects Long
                // (nullable — onPreCompress is a no-op when null).
                Long agentIdLong = null;
                if (!agentIdStr.isEmpty()) {
                    try {
                        agentIdLong = Long.parseLong(agentIdStr);
                    } catch (NumberFormatException ignored) {
                        // Same fallback as the non-history prefix builder above.
                    }
                }

                List<Message> compactedMessages = conversationWindowManager.compactForRetry(
                        messages, chatModel, conversationId, agentIdLong);

                if (compactedMessages != null && compactedMessages.size() < messages.size()) {
                    // Reuse the SAME non-history prefix — wiki/runtime context preserved.
                    List<Message> retryPromptMessages = new ArrayList<>(nonHistoryPrefix);
                    retryPromptMessages.addAll(compactedMessages);
                    Prompt retryPrompt = new Prompt(retryPromptMessages, options);
                    log.info("[ReasoningNode] Retrying with compacted messages: {} -> {} messages",
                            messages.size(), compactedMessages.size());
                    nextLlmCallCount++;
                    pushPhase(conversationId, "reasoning", Map.of(
                            "iteration", accessor.iterationCount(),
                            "llmCallCount", nextLlmCallCount,
                            "compacted", true
                    ));
                    result = streamingHelper.streamCall(chatModel, retryPrompt, conversationId, "reasoning_compact_retry");
                } else {
                    log.warn("[ReasoningNode] Compaction did not reduce messages, cannot retry");
                }
            }

            // Empty-completion guard: a turn with no tool call, no content, and
            // no thinking is not a real answer. Under heavy message-window
            // trimming on long multi-step tasks the model occasionally emits a
            // blank turn; the final-answer branch would then treat it as "done"
            // (finalAnswer="") and end the run prematurely (observed: a 10-item
            // research task stopping at item 2). Re-prompt it to continue —
            // bounded, so a model that genuinely has nothing left still
            // terminates cleanly through the normal empty-answer path below.
            int emptyRetries = 0;
            while (emptyRetries < MAX_EMPTY_COMPLETION_RETRIES && isEmptyCompletion(result)) {
                emptyRetries++;
                log.warn("[ReasoningNode] Empty LLM completion (no tool call / content / thinking); "
                                + "nudging to continue (retry {}/{}), conv={}",
                        emptyRetries, MAX_EMPTY_COMPLETION_RETRIES, conversationId);
                List<Message> nudgedMessages = new ArrayList<>(promptMessages);
                nudgedMessages.add(new UserMessage(EMPTY_COMPLETION_NUDGE));
                Prompt nudgePrompt = new Prompt(nudgedMessages, options);
                nextLlmCallCount++;
                result = streamingHelper.streamCall(
                        chatModel, nudgePrompt, conversationId, "reasoning_empty_retry");
            }
        } catch (CancellationException ce) {
            // "调用已发出但尚未产出内容时用户停止" — streamHelper 抛 CancellationException。
            // 返回空 finalAnswer + STOPPED，让 FinalAnswerNode 按 STOPPED 语义处理。
            // 必须显式清零 needsToolCall/shouldSummarize，防止前一轮残留标志导致误路由。
            log.info("[ReasoningNode] CancellationException during LLM call (user stopped before first token), " +
                    "returning empty answer with STOPPED, llmCallCount={}", nextLlmCallCount);
            return reasonOutput()
                    .finalAnswer("")
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .llmCallCount(nextLlmCallCount)
                    .finishReason(FinishReason.STOPPED)
                    .contentStreamed(true)
                    .thinkingStreamed(true)
                    .build();
        }

        // ======= 处理 StreamResult =======

        // 用户主动停止且有部分内容
        if (result.stopped() && result.hasAnyContent()) {
            String partialText = result.text() != null ? result.text() : "";
            String partialThinking = result.thinking() != null ? result.thinking() : "";
            log.info("[ReasoningNode] Stop with partial content ({} chars, thinking {} chars), flushing as final answer",
                    partialText.length(), partialThinking.length());
            var builder = reasonOutput()
                    .finalAnswer(partialText)
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .contentStreamed(true)
                    .llmCallCount(nextLlmCallCount)
                    .mergeUsage(state, result)
                    .finishReason(FinishReason.STOPPED);
            if (!partialThinking.isEmpty()) {
                builder.finalThinking(partialThinking);
                builder.thinkingStreamed(true);
            }
            return builder.build();
        }

        // Order matters: the partial-truncation branch MUST sit before
        // hasFatalError(). hasFatalError() is "no text + no tool calls + non-
        // null errorMessage", which is also the shape of a thinking-only cap
        // result (text is empty by definition). Without this ordering the
        // soft cap would be re-promoted to ERROR_FALLBACK and we'd lose the
        // INCOMPLETE semantics.

        if (result.partial() && "thinking_only_no_content".equals(result.errorMessage())) {
            // Soft thinking-only loop: the helper disposed the upstream stream
            // because the model accumulated >= THINKING_ONLY_HARD_CAP_CHARS of
            // reasoning_content without emitting any visible content or tool
            // calls. Treat as INCOMPLETE rather than fatal — the thinking text
            // has already been streamed and is preserved for the UI's collapse
            // panel; the user gets a short fallback line they can retry from.
            String partialThinking = result.thinking() != null ? result.thinking() : "";
            log.warn("[ReasoningNode] Thinking-only soft cap hit ({} thinking chars, no content/tools); " +
                            "INCOMPLETE",
                    partialThinking.length());
            var builder = reasonOutput()
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .finalAnswer("（模型在思考阶段停留过久且未给出最终答案，请重试或拆分问题。）")
                    .llmCallCount(nextLlmCallCount)
                    .finishReason(FinishReason.INCOMPLETE)
                    .contentStreamed(false)
                    .thinkingStreamed(true)
                    .mergeUsage(state, result);
            if (!partialThinking.isEmpty()) {
                builder.finalThinking(partialThinking);
            }
            return builder.build();
        }

        if (result.partial() && "content_repetition".equals(result.errorMessage())) {
            // Reasoning loop: the helper disposed the stream because the
            // model emitted the same paragraph 4+ times in a row (qwen3.6
            // / deepseek-r1 self-arguing pattern). The streamed text
            // already showed the duplicates to the user — we can't unsend
            // SSE chunks — but the persisted finalAnswer should be ONE
            // clean copy so the IM channel reply and any page-reload
            // history don't show the wall of repetition. Skip
            // FinalAnswerNode's evidence validation: the answer is
            // already truncated, applying validateAnswer on top would
            // double-stamp warnings on something the user already knows
            // is incomplete.
            String rawContent = result.text() != null ? result.text() : "";
            String dedupedAnswer = NodeStreamingChatHelper.dedupTrailingRepeats(
                    rawContent,
                    NodeStreamingChatHelper.CONTENT_REPEAT_MIN_PERIOD,
                    NodeStreamingChatHelper.CONTENT_REPEAT_MAX_PERIOD);
            log.warn("[ReasoningNode] Content-repetition cap hit (raw={} chars → deduped={} chars); " +
                            "INCOMPLETE",
                    rawContent.length(), dedupedAnswer.length());
            var builder = reasonOutput()
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .finalAnswer(dedupedAnswer.isEmpty()
                            ? "（模型反复输出同一段内容，已自动截断。请尝试重新生成或换个问法。）"
                            : dedupedAnswer)
                    .llmCallCount(nextLlmCallCount)
                    .finishReason(FinishReason.INCOMPLETE)
                    // contentStreamed=true because the user already saw
                    // the looping text in their bubble; persisting again
                    // via streamedContent would replay it.
                    .contentStreamed(true)
                    .thinkingStreamed(result.thinking() != null && !result.thinking().isEmpty())
                    .mergeUsage(state, result);
            if (result.thinking() != null && !result.thinking().isEmpty()) {
                builder.finalThinking(result.thinking());
            }
            return builder.build();
        }

        // Fatal error：直接设置 finalAnswer 为错误文案 + ERROR_FALLBACK，
        // 不走 LimitExceededNode（后者会再发一次 LLM 调用，语义不对且对认证/配额错误会再失败）。
        // ReasoningDispatcher 看到 !needsToolCall && !shouldSummarize → finalAnswerNode，
        // FinalAnswerNode 检测到 existingAnswer 非空时直接使用，finishReason 保持 ERROR_FALLBACK。
        if (result.hasFatalError()) {
            log.error("[ReasoningNode] Fatal LLM error: {}", result.errorMessage());
            return reasonOutput()
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .finalAnswer("[错误] " + result.errorMessage())
                    .llmCallCount(nextLlmCallCount)
                    .finishReason(FinishReason.ERROR_FALLBACK)
                    .contentStreamed(true)
                    .thinkingStreamed(true)
                    .mergeUsage(state, result)
                    .build();
        }

        if (result.partial()) {
            int partialChars = result.text() != null ? result.text().length() : 0;
            log.warn("[ReasoningNode] Partial LLM result ({} chars), treating as final answer", partialChars);
        }

        if (result.hasToolCalls()) {
            log.info("[ReasoningNode] LLM requested {} tool call(s): {}",
                    result.toolCalls().size(),
                    result.toolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
            pushPhase(conversationId, "executing_tool", Map.of(
                    "iteration", accessor.iterationCount(),
                    "toolCount", result.toolCalls().size()
            ));

            return reasonOutput()
                    .needsToolCall(true)
                    .shouldSummarize(false)
                    .toolCalls(result.toolCalls())
                    .messages(List.of((Message) result.assistantMessage()))
                    .currentPhase("reasoning")
                    .currentThinking(result.thinking())
                    .streamedContent(result.text() != null ? result.text() : "")
                    .streamedThinking(result.thinking())
                    .contentStreamed(true)
                    .thinkingStreamed(!result.thinking().isEmpty())
                    .llmCallCount(nextLlmCallCount)
                    .mergeUsage(state, result)
                    .events(buildEvents(phaseEvent, iterStartEvent))
                    .build();
        } else {
            String content = result.text();
            log.info("[ReasoningNode] LLM produced final answer ({} chars)", content != null ? content.length() : 0);
            pushPhase(conversationId, "drafting_answer", Map.of(
                    "iteration", accessor.iterationCount(),
                    "answerChars", content != null ? content.length() : 0
            ));
            SourceEvidenceLedger.Validation validation =
                    accessor.sourceEvidenceLedger().validateAnswer(content != null ? content : "");
            boolean evidenceInsufficient = !validation.valid();
            String finalAnswer = evidenceInsufficient
                    ? evidenceWarning(validation.unsupportedReferences())
                    : (content != null ? content : "");
            if (evidenceInsufficient) {
                log.warn("[ReasoningNode] Evidence insufficient for final answer, unsupportedReferences={}",
                        validation.unsupportedReferences());
            }

            // Final-answer path: iteration ends in this same node because
            // ReAct never re-enters the loop afterwards.
            GraphEventPublisher.GraphEvent iterEndEvent = iterationEventsOn
                    ? GraphEventPublisher.iterationEnd(accessor.iterationCount(),
                            "parent", null,
                            content != null ? content.length() : 0,
                            result.thinking() != null ? result.thinking().length() : 0)
                    : null;
            return reasonOutput()
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .finalAnswer(finalAnswer)
                    .finalThinking(result.thinking())
                    .messages(List.of((Message) result.assistantMessage()))
                    .currentPhase("reasoning")
                    .streamedContent(evidenceInsufficient ? (content != null ? content : "") : "")
                    .finishReason(evidenceInsufficient ? FinishReason.EVIDENCE_INSUFFICIENT : FinishReason.NORMAL)
                    .contentStreamed(!evidenceInsufficient)
                    .thinkingStreamed(!result.thinking().isEmpty())
                    .llmCallCount(nextLlmCallCount)
                    .mergeUsage(state, result)
                    .events(buildEvents(phaseEvent, iterStartEvent, iterEndEvent))
                    .build();
        }
    }

    private static String evidenceWarning(List<String> unsupportedReferences) {
        return "\n\n[证据不足] 以下源码引用未出现在已读取/搜索到的工具证据中："
                + String.join(", ", unsupportedReferences)
                + "。请继续读取相关文件后再下结论。";
    }

    private AssistantMessage.ToolCall deserializeToolCall(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = OBJECT_MAPPER.readValue(json, Map.class);
            return new AssistantMessage.ToolCall(
                    map.getOrDefault("id", UUID.randomUUID().toString()),
                    map.getOrDefault("type", "function"),
                    map.getOrDefault("name", ""),
                    map.getOrDefault("arguments", "")
            );
        } catch (Exception e) {
            log.error("[ReasoningNode] Failed to deserialize forced_tool_call: {}", e.getMessage());
            throw new RuntimeException("无法反序列化 forced_tool_call: " + e.getMessage(), e);
        }
    }

    /**
     * Compose the per-call event list, dropping any null entries so the
     * iteration-boundary toggle ({@code mateclaw.stream.iteration-events})
     * works without forcing every caller into branching code.
     */
    private static List<GraphEventPublisher.GraphEvent> buildEvents(GraphEventPublisher.GraphEvent... events) {
        List<GraphEventPublisher.GraphEvent> out = new ArrayList<>(events.length);
        for (GraphEventPublisher.GraphEvent ev : events) {
            if (ev != null) out.add(ev);
        }
        return out;
    }

    /**
     * Build the part of the Prompt that does not depend on history messages:
     * system prompt, workspace runtime context, and (when wiring permits) the
     * wiki relevant-pages snippet. Extracted so the initial Prompt assembly
     * and the PTL retry path can share one source of truth — historically
     * these were two parallel code paths and the retry one silently dropped
     * the wiki injection.
     * <p>
     * {@code systemPrompt} is consumed as-is; the upstream callsite has
     * already appended the tool-use enforcement clause, so this helper must
     * NOT re-append it (doing so would duplicate the clause on every retry).
     *
     * @param systemPrompt      Fully-built system prompt (with tool-use
     *                          enforcement already appended upstream).
     * @param workspaceBasePath Active workspace directory; passed to
     *                          {@link RuntimeContextInjector}.
     * @param agentIdStr        Agent ID as carried in graph state — parsed
     *                          to {@code Long} only when non-empty and
     *                          numeric; otherwise the wiki segment is
     *                          skipped (matches the pre-refactor behavior).
     * @param userMsg           Current user message used by
     *                          {@code WikiContextService} to score
     *                          relevance.
     */
    // Package-private so ReasoningNodePtlPromptTest can directly assert on
    // the wiki / runtime-context layout; the production callsites inside
    // this class call it via {@code this.buildNonHistoryPrefix(...)} so
    // narrowing the visibility doesn't change behavior.
    List<Message> buildNonHistoryPrefix(String systemPrompt,
                                        String workspaceBasePath,
                                        String agentIdStr,
                                        String userMsg,
                                        vip.mate.agent.context.ChatOrigin chatOrigin) {
        List<Message> prefix = new ArrayList<>();
        prefix.add(new SystemMessage(systemPrompt));
        prefix.add(new UserMessage(RuntimeContextInjector.buildContextMessage(workspaceBasePath, null, chatOrigin)));
        // When this turn already recalled the user's own current project from
        // structured memory, skip auto-injecting knowledge-base reference context.
        // Otherwise the KB pages (reference material, possibly about unrelated
        // projects) compete with — and tend to override — the user's actual
        // project identity. The agent can still query the wiki on demand.
        boolean projectRecalled = userMsg != null
                && userMsg.contains(vip.mate.memory.service.StructuredMemoryService.PROJECT_RECALLED_MARKER);
        if (projectRecalled) {
            log.debug("[ReasoningNode] Skipping wiki-relevant injection: user's project was recalled from memory this turn");
        }
        if (!projectRecalled && wikiContextService != null && agentIdStr != null && !agentIdStr.isEmpty()) {
            try {
                Long parsedAgentId = Long.parseLong(agentIdStr);
                String wikiRelevant = wikiContextService.buildRelevantContext(parsedAgentId, userMsg);
                if (wikiRelevant != null && !wikiRelevant.isBlank()) {
                    prefix.add(new UserMessage(wikiRelevant));
                }
            } catch (NumberFormatException ignored) {
                // agentId not numeric — skip wiki injection (matches prior behavior).
            }
        }
        return prefix;
    }

    private void pushPhase(String conversationId, String phase, Map<String, Object> extra) {
        if (streamTracker == null || !StringUtils.hasText(conversationId)) {
            return;
        }
        streamTracker.updatePhase(conversationId, phase);
        streamTracker.broadcastObject(conversationId, "phase", GraphEventPublisher.phase(phase, extra).data());
    }

    /**
     * 根据 ChatModel 类型构建合适的 ChatOptions。
     * - AnthropicChatModel → AnthropicChatOptions（支持 extended thinking）
     * - 其他（OpenAI/DashScope）→ OpenAiChatOptions（支持 reasoningEffort）
     */
    private ChatOptions buildChatOptions(String effectiveReasoning, List<ToolCallback> activeCallbacks) {
        // Anthropic 协议模型（AnthropicChatModel）：MiniMax 也用此协议但不支持 thinking
        if (chatModel instanceof org.springframework.ai.anthropic.AnthropicChatModel anthropicModel) {
            org.springframework.ai.anthropic.AnthropicChatOptions.Builder builder =
                    org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                    .toolCallbacks(activeCallbacks)
                    .internalToolExecutionEnabled(false);

            // 仅对真正的 Claude 模型启用 extended thinking（MiniMax 等走 Anthropic 协议但不支持）
            String thinkingLevel = ThinkingLevelHolder.get();
            boolean thinkingOn = thinkingLevel != null && !"off".equalsIgnoreCase(thinkingLevel);
            String currentModel = getAnthropicModelName(anthropicModel);
            boolean isClaudeModel = currentModel != null && currentModel.toLowerCase().contains("claude");

            if (thinkingOn && isClaudeModel) {
                int budgetTokens = switch (thinkingLevel.toLowerCase()) {
                    case "low" -> 4096;
                    case "medium" -> 8192;
                    case "high" -> 16384;
                    case "max" -> 32768;
                    default -> 16384;
                };
                builder.thinking(org.springframework.ai.anthropic.api.AnthropicApi.ThinkingType.ENABLED, budgetTokens);
                builder.maxTokens(budgetTokens + maxOutputTokens);
                builder.temperature(1.0);
                log.info("[ReasoningNode] Anthropic extended thinking enabled: model={}, budget={}", currentModel, budgetTokens);
            } else {
                builder.maxTokens(maxOutputTokens);
                if (thinkingOn && !isClaudeModel) {
                    log.debug("[ReasoningNode] Anthropic protocol model {} does not support thinking, skipping", currentModel);
                }
            }
            return builder.build();
        }

        // OpenAI / DashScope / 其他
        // 始终使用 OpenAiChatOptions（而非 ToolCallingChatOptions），
        // 因为 ToolCallingChatOptions 会丢失 OpenAI 特有参数（streamUsage 等），
        // 导致 Kimi 等 OpenAI 兼容 API 响应异常或提前截断。
        // DashScope rejects max_tokens above its 8192 ceiling with a 400 that
        // the failover layer misreads as "model not found"; clamp so a
        // DashScope-backed model never overflows the provider limit.
        int effectiveMaxTokens = maxOutputTokens;
        if (chatModel instanceof com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel
                && effectiveMaxTokens > DASHSCOPE_MAX_OUTPUT_TOKENS) {
            log.debug("[ReasoningNode] Clamping max_tokens {} -> {} for DashScope-backed model",
                    effectiveMaxTokens, DASHSCOPE_MAX_OUTPUT_TOKENS);
            effectiveMaxTokens = DASHSCOPE_MAX_OUTPUT_TOKENS;
        }
        OpenAiChatOptions.Builder oaiBuilder = OpenAiChatOptions.builder()
                .toolCallbacks(activeCallbacks)
                .maxTokens(effectiveMaxTokens);
        if (StringUtils.hasText(effectiveReasoning)) {
            oaiBuilder.reasoningEffort(effectiveReasoning);
        }
        OpenAiChatOptions oaiOpts = oaiBuilder.build();
        oaiOpts.setInternalToolExecutionEnabled(false);
        oaiOpts.setStreamUsage(true);
        return oaiOpts;
    }

    /**
     * 解析有效的 reasoningEffort。
     * 优先级：ThinkingLevelHolder（请求级） > 构造时的 reasoningEffort（Agent/模型默认）。
     * "off" 会清除 reasoningEffort（返回 null）。
     *
     * <p>PR-1.2 (RFC-049 L1-B): If the bound model's family does not support
     * {@code reasoning_effort} (as declared via {@link #supportsReasoningEffort} at
     * construction time), the front-end thinking-level override is ignored.
     * Chat-type models like {@code deepseek-chat} must not be forced into thinking mode
     * just because the user ticked "deep thinking" in the UI — this is a product
     * contract, not a runtime option.
     */
    private String resolveEffectiveReasoningEffort() {
        String requestLevel = ThinkingLevelHolder.get();
        if (requestLevel != null) {
            if ("off".equalsIgnoreCase(requestLevel)) {
                return null;
            }
            if (!this.supportsReasoningEffort) {
                log.debug("[ReasoningNode] Ignoring thinkingLevel='{}' — bound model family does not support reasoning_effort",
                        requestLevel);
                return null;
            }
            // thinkingLevel → reasoningEffort 映射
            return switch (requestLevel.toLowerCase()) {
                case "low" -> "low";
                case "medium" -> "medium";
                case "high" -> "high";
                case "max" -> "high"; // OpenAI 最高支持 high
                default -> requestLevel; // 透传未知值
            };
        }
        // 无请求级覆盖，使用构造时的默认值
        return this.reasoningEffort;
    }

    /**
     * 从 AnthropicChatModel 的 defaultOptions 中提取模型名称。
     * 用于判断是否为真正的 Claude 模型（vs MiniMax 等走 Anthropic 协议的非 Claude 模型）。
     */
    private String getAnthropicModelName(org.springframework.ai.anthropic.AnthropicChatModel model) {
        try {
            var options = model.getDefaultOptions();
            if (options instanceof org.springframework.ai.anthropic.AnthropicChatOptions aOpts) {
                return aOpts.getModel();
            }
        } catch (Exception e) {
            log.debug("[ReasoningNode] Failed to extract Anthropic model name: {}", e.getMessage());
        }
        return null;
    }
}
