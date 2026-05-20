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
import vip.mate.agent.context.RuntimeContextInjector;
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
            + "  才输出无 tool_call 的纯文字回答。\n";

    private final ChatModel chatModel;
    private final List<ToolCallback> toolCallbacks;
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
        this.chatModel = chatModel;
        this.toolCallbacks = toolSet.callbacks();
        this.reasoningEffort = reasoningEffort;
        this.supportsReasoningEffort = supportsReasoningEffort;
        this.streamingHelper = streamingHelper;
        this.conversationWindowManager = conversationWindowManager;
        this.streamTracker = streamTracker;
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        this.wikiContextService = wikiContextService;
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
        this.toolCallbacks = toolCallbacks;
        this.reasoningEffort = null;
        this.supportsReasoningEffort = false;
        this.streamingHelper = null;
        this.conversationWindowManager = null;
        this.streamTracker = null;
        this.maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
        this.wikiContextService = null;
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

        // Guard against runaway message list growth.
        //
        // CRITICAL: a naive head+tail cut can break the OpenAI-compatible protocol invariant
        // that requires tool_call / tool_response pairs to be complete:
        //
        //   P0 (originally observed): AssistantMessage(tool_calls) falls into the dropped gap,
        //      its ToolResponseMessage lands in the kept tail → provider sees an orphaned
        //      ToolResponseMessage → kimi-code 400 "tool_call_id is not found".
        //
        //   P1 (symmetric): AssistantMessage(tool_calls) is kept in the head at the boundary,
        //      its ToolResponseMessage falls into the dropped gap → provider sees an assistant
        //      tool_call with no matching response → also a 400 on strict providers.
        //
        // Fix: perform the normal cut, then run an iterative bidirectional integrity pass until
        // the list is stable:
        //   • Remove any ToolResponseMessage whose parent AssistantMessage.tool_calls id was
        //     dropped (P0).
        //   • Remove any AssistantMessage whose tool_calls have no matching ToolResponseMessage
        //     (P1).
        // Iterate because a P1 removal could expose a new P0 orphan (and vice versa, though that
        // is pathological in practice).  With ≤40 messages convergence is always fast.
        // Dropping incomplete pairs is safe — prior iterations already processed those
        // observations; the LLM needs the summary context, not the raw tool I/O.
        final int MAX_LOOP_MESSAGES = 40;
        if (messages.size() > MAX_LOOP_MESSAGES) {
            log.warn("[ReasoningNode] Messages list too large ({} messages), trimming to {} for conversation {}",
                    messages.size(), MAX_LOOP_MESSAGES, conversationId);
            int headKeep = Math.min(4, messages.size());
            int tailKeep = MAX_LOOP_MESSAGES - headKeep;
            int tailStart = messages.size() - tailKeep;

            List<Message> trimmed = new ArrayList<>(MAX_LOOP_MESSAGES);
            trimmed.addAll(messages.subList(0, headKeep));
            trimmed.addAll(messages.subList(tailStart, messages.size()));

            // Iterative bidirectional integrity pass.
            int totalRemoved = 0;
            boolean changed;
            do {
                // Snapshot current tool_call ids and response ids.
                Set<String> callIds = new java.util.HashSet<>();
                Set<String> respIds = new java.util.HashSet<>();
                for (Message m : trimmed) {
                    if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                        for (AssistantMessage.ToolCall tc : am.getToolCalls()) callIds.add(tc.id());
                    }
                    if (m instanceof ToolResponseMessage trm) {
                        for (ToolResponseMessage.ToolResponse r : trm.getResponses()) respIds.add(r.id());
                    }
                }
                int before = trimmed.size();
                trimmed.removeIf(m -> {
                    // P0: ToolResponseMessage whose parent tool_call was dropped
                    if (m instanceof ToolResponseMessage trm) {
                        return trm.getResponses().stream().anyMatch(r -> !callIds.contains(r.id()));
                    }
                    // P1: AssistantMessage whose tool_call has no ToolResponseMessage
                    if (m instanceof AssistantMessage am && am.getToolCalls() != null
                            && !am.getToolCalls().isEmpty()) {
                        return am.getToolCalls().stream().anyMatch(tc -> !respIds.contains(tc.id()));
                    }
                    return false;
                });
                int removed = before - trimmed.size();
                totalRemoved += removed;
                changed = removed > 0;
            } while (changed);

            if (totalRemoved > 0) {
                log.warn("[ReasoningNode] Removed {} message(s) with broken tool_call/response pairs "
                        + "after trim (bidirectional integrity guard), conv={}", totalRemoved, conversationId);
            }

            messages = trimmed;
        }

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

        if (conversationWindowManager != null) {
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

        ChatOptions options = buildChatOptions(effectiveReasoning);

        Prompt prompt = new Prompt(promptMessages, options);

        // ======= LLM 调用区域 =======
        // nextLlmCallCount 在首次 streamCall 之前计算。
        // 所有退出路径（正常、stopped、fatal error、CancellationException）都必须写回此值。
        // PTL compact retry 会再 +1。
        int nextLlmCallCount = accessor.llmCallCount() + 1;
        log.debug("[ReasoningNode] Calling LLM with {} messages, {} tool definitions, iteration {}/{}, llmCallCount={}",
                promptMessages.size(), toolCallbacks.size(),
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
        if (wikiContextService != null && agentIdStr != null && !agentIdStr.isEmpty()) {
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
    private ChatOptions buildChatOptions(String effectiveReasoning) {
        // Anthropic 协议模型（AnthropicChatModel）：MiniMax 也用此协议但不支持 thinking
        if (chatModel instanceof org.springframework.ai.anthropic.AnthropicChatModel anthropicModel) {
            org.springframework.ai.anthropic.AnthropicChatOptions.Builder builder =
                    org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
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
                .toolCallbacks(toolCallbacks)
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
