package vip.mate.agent.graph.state;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.messages.Message;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.NodeStreamingChatHelper;

import java.util.*;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 类型安全的状态访问器
 * <p>
 * 封装 {@link OverAllState} 的字符串 key 读写，
 * 提供带默认值的强类型方法，避免业务代码散落 state.value("xxx") 调用。
 *
 * @author MateClaw Team
 */
public final class MateClawStateAccessor {

    private final OverAllState state;

    public MateClawStateAccessor(OverAllState state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    // ===== 输入字段 =====

    public String userMessage() {
        return state.value(USER_MESSAGE, "");
    }

    public String conversationId() {
        return state.value(CONVERSATION_ID, "");
    }

    public String agentId() {
        return state.value(AGENT_ID, "");
    }

    public String systemPrompt() {
        return state.value(SYSTEM_PROMPT, "你是一个有帮助的AI助手。");
    }

    // ===== 消息列表 =====

    @SuppressWarnings("unchecked")
    public List<Message> messages() {
        return state.<List<Message>>value(MESSAGES).orElse(List.of());
    }

    // ===== 迭代控制 =====

    public int iterationCount() {
        return state.value(CURRENT_ITERATION, 0);
    }

    public int maxIterations() {
        return state.value(MAX_ITERATIONS, 25);
    }

    public boolean isLimitReached() {
        int max = maxIterations();
        return max > 0 && iterationCount() >= max; // max=0 表示不限制
    }

    // ===== 工具调用 =====

    public boolean needsToolCall() {
        return state.value(NEEDS_TOOL_CALL, false);
    }

    public int toolCallCount() {
        return state.value(TOOL_CALL_COUNT, 0);
    }

    public int llmCallCount() {
        return state.value(LLM_CALL_COUNT, 0);
    }

    // ===== 观察历史 =====

    @SuppressWarnings("unchecked")
    public List<String> observationHistory() {
        return state.<List<String>>value(OBSERVATION_HISTORY).orElse(List.of());
    }

    /**
     * 计算所有观察记录的总字符数
     */
    public int totalObservationChars() {
        return observationHistory().stream().mapToInt(String::length).sum();
    }

    // ===== Summarizing =====

    public boolean shouldSummarize() {
        return state.value(SHOULD_SUMMARIZE, false);
    }

    public String summarizedContext() {
        return state.value(SUMMARIZED_CONTEXT, "");
    }

    // ===== 终止控制 =====

    public String finalAnswer() {
        return state.value(FINAL_ANSWER, "");
    }

    public String finalAnswerDraft() {
        return state.value(FINAL_ANSWER_DRAFT, "");
    }

    public boolean limitExceeded() {
        return state.value(LIMIT_EXCEEDED, false);
    }

    public String finishReason() {
        return state.value(FINISH_REASON, "");
    }

    // ===== 错误 =====

    public String error() {
        return state.value(ERROR, (String) null);
    }

    public boolean hasError() {
        String err = error();
        return err != null && !err.isEmpty();
    }

    public int errorCount() {
        return state.value(ERROR_COUNT, 0);
    }

    // ===== 追踪 =====

    public String traceId() {
        return state.value(TRACE_ID, "");
    }

    // ===== 事件流 =====

    @SuppressWarnings("unchecked")
    public List<GraphEventPublisher.GraphEvent> pendingEvents() {
        return state.<List<GraphEventPublisher.GraphEvent>>value(PENDING_EVENTS).orElse(List.of());
    }

    public String currentPhase() {
        return state.value(CURRENT_PHASE, "");
    }

    // ===== Thinking =====

    public String finalThinking() {
        return state.value(FINAL_THINKING, "");
    }

    public String currentThinking() {
        return state.value(CURRENT_THINKING, "");
    }

    // ===== 流式防重 =====

    public boolean contentStreamed() {
        return state.value(CONTENT_STREAMED, false);
    }

    public boolean thinkingStreamed() {
        return state.value(THINKING_STREAMED, false);
    }

    // ===== 请求者身份 =====

    public String requesterId() {
        return state.value(REQUESTER_ID, "");
    }

    // ===== 流式内容暂存 =====

    public String streamedContent() {
        return state.value(STREAMED_CONTENT, "");
    }

    public String streamedThinking() {
        return state.value(STREAMED_THINKING, "");
    }

    // ===== 审批控制 =====

    public boolean awaitingApproval() {
        return state.value(AWAITING_APPROVAL, false);
    }

    // ===== RFC-052: returnDirect =====

    public boolean returnDirectTriggered() {
        return state.value(RETURN_DIRECT_TRIGGERED, false);
    }

    @SuppressWarnings("unchecked")
    public List<DirectToolOutput> directToolOutputs() {
        return state.<List<DirectToolOutput>>value(DIRECT_TOOL_OUTPUTS).orElse(List.of());
    }

    public SourceEvidenceLedger sourceEvidenceLedger() {
        return state.<SourceEvidenceLedger>value(SOURCE_EVIDENCE_LEDGER).orElse(SourceEvidenceLedger.empty());
    }

    // ===== 审批重放 =====

    public String forcedToolCall() {
        return state.value(FORCED_TOOL_CALL, "");
    }

    // ===== RFC-063r: ChatOrigin =====

    /**
     * RFC-063r §2.5: the {@link ChatOrigin} written into graph state by the
     * top-level agent. Returns {@link ChatOrigin#EMPTY} when the entry path
     * did not supply one (e.g., legacy callers using the bridge overloads).
     */
    public ChatOrigin chatOrigin() {
        return state.<ChatOrigin>value(CHAT_ORIGIN).orElse(ChatOrigin.EMPTY);
    }

    // ===== Skill progressive disclosure =====

    /**
     * Skills loaded via {@code load_skill} so far this run. Empty when none
     * have been loaded (the common first-iteration case).
     */
    @SuppressWarnings("unchecked")
    public Set<String> loadedSkills() {
        return state.<Set<String>>value(LOADED_SKILLS).orElse(Set.of());
    }

    /**
     * Extension tools activated via {@code enable_tool} so far this run. Empty
     * when none have been enabled (the common case).
     */
    @SuppressWarnings("unchecked")
    public Set<String> enabledExtensionTools() {
        return state.<Set<String>>value(ENABLED_EXTENSION_TOOLS).orElse(Set.of());
    }

    // ===== Token Usage =====

    public int promptTokens() {
        return state.value(PROMPT_TOKENS, 0);
    }

    public int completionTokens() {
        return state.value(COMPLETION_TOKENS, 0);
    }

    public String runtimeModelName() {
        return state.value(RUNTIME_MODEL_NAME, "");
    }

    public String runtimeProviderId() {
        return state.value(RUNTIME_PROVIDER_ID, "");
    }

    // ===== Persistent goal accessors =====

    /**
     * Active goal snapshot or empty. The injected object is the
     * {@code vip.mate.goal.model.GoalEntity}; we reference it by Object
     * here to avoid pulling the goal package into core graph state.
     */
    public Optional<Object> activeGoal() {
        return state.<Object>value(ACTIVE_GOAL);
    }

    public boolean hasActiveGoal() {
        return state.<Object>value(ACTIVE_GOAL).isPresent();
    }

    public boolean goalEvaluatedThisRun() {
        return state.value(GOAL_EVALUATED_THIS_RUN, false);
    }

    public boolean goalFollowupInjected() {
        return state.value(GOAL_FOLLOWUP_INJECTED, false);
    }

    public String goalFollowupPrompt() {
        return state.value(GOAL_FOLLOWUP_PROMPT, "");
    }

    /** Auto-followups already injected in this graph run (0 at run start). */
    public int goalFollowupCount() {
        return state.value(GOAL_FOLLOWUP_COUNT, 0);
    }

    /** Cumulative agent LLM calls already billed to the goal this run (0 at run start). */
    public int goalAccountedLlmCallCount() {
        return state.value(GOAL_ACCOUNTED_LLM_CALL_COUNT, 0);
    }

    /**
     * Bridge across ReAct and Plan-Execute: ReAct writes the terminal text
     * to {@link MateClawStateKeys#FINAL_ANSWER} via FinalAnswerNode;
     * Plan-Execute writes to {@code PlanStateKeys.FINAL_SUMMARY} (long
     * path) or {@code PlanStateKeys.DIRECT_ANSWER} (short path). The
     * GoalEvaluationNode reads whichever is populated without having to
     * know which graph it's inside.
     */
    public String terminalAnswer() {
        String fa = state.value(FINAL_ANSWER, "");
        if (!fa.isEmpty()) {
            return fa;
        }
        // Avoid a direct compile-time reference to PlanStateKeys (the plan
        // sub-package depends on core graph state); use the string keys
        // verbatim. Mismatches would surface as terminalAnswer() returning
        // empty in tests — the v3 TerminalAnswerTest pins exactly that.
        String summary = state.value("final_summary", "");
        if (!summary.isEmpty()) {
            return summary;
        }
        return state.value("direct_answer", "");
    }

    // ===== 输出构建器 =====

    /**
     * 创建一个 fluent 输出构建器，用于 NodeAction.apply() 返回值
     */
    public static OutputBuilder output() {
        return new OutputBuilder();
    }

    /**
     * Fluent 输出构建器
     * <p>
     * 使用示例：
     * <pre>
     * return MateClawStateAccessor.output()
     *     .iterationCount(3)
     *     .shouldSummarize(true)
     *     .observationHistory("搜索结果：xxx")
     *     .build();
     * </pre>
     */
    public static final class OutputBuilder {
        private final Map<String, Object> map = new HashMap<>();

        private OutputBuilder() {
        }

        public OutputBuilder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        // ---- 迭代控制 ----
        public OutputBuilder iterationCount(int count) {
            return put(CURRENT_ITERATION, count);
        }

        public OutputBuilder needsToolCall(boolean needs) {
            return put(NEEDS_TOOL_CALL, needs);
        }

        // ---- 消息 ----
        public OutputBuilder messages(List<Message> msgs) {
            return put(MESSAGES, msgs);
        }

        // ---- 工具调用 ----
        public OutputBuilder toolCalls(Object calls) {
            return put(TOOL_CALLS, calls);
        }

        public OutputBuilder toolResults(Object results) {
            return put(TOOL_RESULTS, results);
        }

        public OutputBuilder toolCallCount(int count) {
            return put(TOOL_CALL_COUNT, count);
        }

        public OutputBuilder llmCallCount(int count) {
            return put(LLM_CALL_COUNT, count);
        }

        // ---- 观察 ----
        public OutputBuilder observationHistory(String observation) {
            return put(OBSERVATION_HISTORY, List.of(observation));
        }

        public OutputBuilder shouldSummarize(boolean should) {
            return put(SHOULD_SUMMARIZE, should);
        }

        // ---- Summarizing ----
        public OutputBuilder summarizedContext(String ctx) {
            return put(SUMMARIZED_CONTEXT, ctx);
        }

        public OutputBuilder finalAnswerDraft(String draft) {
            return put(FINAL_ANSWER_DRAFT, draft);
        }

        // ---- 终止 ----
        public OutputBuilder finalAnswer(String answer) {
            return put(FINAL_ANSWER, answer);
        }

        public OutputBuilder finishReason(FinishReason reason) {
            return put(FINISH_REASON, reason.getValue());
        }

        public OutputBuilder limitExceeded(boolean exceeded) {
            return put(LIMIT_EXCEEDED, exceeded);
        }

        // ---- 错误 ----
        public OutputBuilder error(String err) {
            return put(ERROR, err);
        }

        public OutputBuilder errorCount(int count) {
            return put(ERROR_COUNT, count);
        }

        // ---- 追踪 ----
        public OutputBuilder traceId(String id) {
            return put(TRACE_ID, id);
        }

        // ---- 事件流 ----
        public OutputBuilder events(List<GraphEventPublisher.GraphEvent> events) {
            return put(PENDING_EVENTS, events);
        }

        public OutputBuilder currentPhase(String phase) {
            return put(CURRENT_PHASE, phase);
        }

        // ---- Thinking ----
        public OutputBuilder finalThinking(String thinking) {
            return put(FINAL_THINKING, thinking);
        }

        public OutputBuilder currentThinking(String thinking) {
            return put(CURRENT_THINKING, thinking);
        }

        // ---- 流式防重 ----
        public OutputBuilder contentStreamed(boolean streamed) {
            return put(CONTENT_STREAMED, streamed);
        }

        public OutputBuilder thinkingStreamed(boolean streamed) {
            return put(THINKING_STREAMED, streamed);
        }

        // ---- 请求者身份 ----
        public OutputBuilder requesterId(String id) {
            return put(REQUESTER_ID, id);
        }

        // ---- 流式内容暂存 ----
        public OutputBuilder streamedContent(String content) {
            return put(STREAMED_CONTENT, content);
        }

        public OutputBuilder streamedThinking(String thinking) {
            return put(STREAMED_THINKING, thinking);
        }

        // ---- 审批控制 ----
        public OutputBuilder awaitingApproval(boolean awaiting) {
            return put(AWAITING_APPROVAL, awaiting);
        }

        // ---- RFC-052: returnDirect ----
        public OutputBuilder returnDirectTriggered(boolean triggered) {
            return put(RETURN_DIRECT_TRIGGERED, triggered);
        }

        public OutputBuilder directToolOutputs(List<DirectToolOutput> outputs) {
            return put(DIRECT_TOOL_OUTPUTS, outputs);
        }

        public OutputBuilder sourceEvidenceLedger(SourceEvidenceLedger ledger) {
            return put(SOURCE_EVIDENCE_LEDGER, ledger);
        }

        // ---- 审批重放 ----
        public OutputBuilder forcedToolCall(String json) {
            return put(FORCED_TOOL_CALL, json);
        }

        // ---- RFC-063r: ChatOrigin ----
        public OutputBuilder chatOrigin(ChatOrigin origin) {
            return put(CHAT_ORIGIN, origin);
        }

        // ---- Skill progressive disclosure ----
        public OutputBuilder loadedSkills(Set<String> names) {
            return put(LOADED_SKILLS, names);
        }

        // ---- Tool progressive disclosure ----
        public OutputBuilder enabledExtensionTools(Set<String> names) {
            return put(ENABLED_EXTENSION_TOOLS, names);
        }

        // ---- Token Usage ----

        /** 将本次 LLM 调用的 usage 累加到 state 已有值上 */
        public OutputBuilder mergeUsage(OverAllState currentState,
                                        NodeStreamingChatHelper.StreamResult result) {
            int existingPrompt = currentState.value(PROMPT_TOKENS, 0);
            int existingCompletion = currentState.value(COMPLETION_TOKENS, 0);
            map.put(PROMPT_TOKENS, existingPrompt + result.promptTokens());
            map.put(COMPLETION_TOKENS, existingCompletion + result.completionTokens());
            return this;
        }

        // ---- Persistent goal ----

        public OutputBuilder goalEvaluationResult(Map<String, Object> result) {
            return put(GOAL_EVALUATION_RESULT, result);
        }

        public OutputBuilder goalFollowupInjected(boolean injected) {
            return put(GOAL_FOLLOWUP_INJECTED, injected);
        }

        public OutputBuilder goalFollowupPrompt(String prompt) {
            return put(GOAL_FOLLOWUP_PROMPT, prompt);
        }

        public OutputBuilder goalEvaluatedThisRun(boolean v) {
            return put(GOAL_EVALUATED_THIS_RUN, v);
        }

        public OutputBuilder goalFollowupCount(int n) {
            return put(GOAL_FOLLOWUP_COUNT, n);
        }

        public OutputBuilder goalAccountedLlmCallCount(int n) {
            return put(GOAL_ACCOUNTED_LLM_CALL_COUNT, n);
        }

        /** Wipe FINAL_ANSWER on follow-up so the next graph pass doesn't
         *  immediately re-terminate via the existing final text. */
        public OutputBuilder clearFinalAnswer() {
            return put(FINAL_ANSWER, "");
        }

        /** Wipe FINISH_REASON for the same reason as clearFinalAnswer(). */
        public OutputBuilder clearFinishReason() {
            return put(FINISH_REASON, "");
        }

        /** Plan-Execute follow-up: clear the terminal-side plan summary so
         *  the next PlanGeneration pass starts clean. Identifier is the
         *  string literal "final_summary" to avoid a compile-time link to
         *  the plan sub-package from core graph state. */
        public OutputBuilder clearPlanFinalSummary() {
            return put("final_summary", "");
        }

        public OutputBuilder clearPlanDirectAnswer() {
            return put("direct_answer", "");
        }

        /** Plan-Execute follow-up: wipe the mid-pass plan state so the next
         *  PlanGenerationNode pass re-derives everything from scratch. */
        public OutputBuilder clearPlanId() {
            return put("plan_id", null);
        }

        public OutputBuilder clearPlanSteps() {
            return put("plan_steps", List.of());
        }

        public OutputBuilder clearPlanValid() {
            return put("plan_valid", false);
        }

        public OutputBuilder clearNeedsPlanning() {
            return put("needs_planning", true);
        }

        public OutputBuilder clearCurrentStepIndex() {
            return put("current_step_index", 0);
        }

        public OutputBuilder clearCurrentStepTitle() {
            return put("current_step_title", "");
        }

        public OutputBuilder clearCurrentStepResult() {
            return put("current_step_result", "");
        }

        public OutputBuilder clearCompletedResults() {
            return put("completed_results", List.of());
        }

        public OutputBuilder clearFinalSummaryThinking() {
            return put("final_summary_thinking", "");
        }

        public OutputBuilder clearCurrentStepThinking() {
            return put("current_step_thinking", "");
        }

        public Map<String, Object> build() {
            return map;
        }
    }
}
