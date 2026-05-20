package vip.mate.agent.graph.plan.state;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.messages.Message;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.util.*;

import static vip.mate.agent.graph.plan.state.PlanStateKeys.*;

/**
 * Plan-Execute 类型安全的状态访问器
 * <p>
 * 参照 {@link vip.mate.agent.graph.state.MateClawStateAccessor} 的模式，
 * 为 Plan-Execute 特有的状态字段提供类型安全读取和 fluent 输出构建。
 *
 * @author MateClaw Team
 */
public final class PlanStateAccessor {

    private final OverAllState state;

    public PlanStateAccessor(OverAllState state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    // ===== 输入 =====

    public String goal() {
        return state.value(GOAL, "");
    }

    // ===== 计划 =====

    public Long planId() {
        return state.value(PLAN_ID, 0L);
    }

    @SuppressWarnings("unchecked")
    public List<String> planSteps() {
        return state.<List<String>>value(PLAN_STEPS).orElse(List.of());
    }

    public boolean planValid() {
        return state.value(PLAN_VALID, false);
    }

    public boolean needsPlanning() {
        // Default to false: an unset triage flag means the request was not
        // classified as requiring a plan. See PlanGenerationDispatcher for the
        // rationale and RFC-008 for the full discussion.
        return state.value(NEEDS_PLANNING, false);
    }

    // ===== 步骤控制 =====

    public int currentStepIndex() {
        return state.value(CURRENT_STEP_INDEX, 0);
    }

    public String currentStepTitle() {
        return state.value(CURRENT_STEP_TITLE, "");
    }

    public String currentStepResult() {
        return state.value(CURRENT_STEP_RESULT, "");
    }

    @SuppressWarnings("unchecked")
    public List<String> completedResults() {
        return state.<List<String>>value(COMPLETED_RESULTS).orElse(List.of());
    }

    // ===== 终止 =====

    public String finalSummary() {
        return state.value(FINAL_SUMMARY, "");
    }

    public String directAnswer() {
        return state.value(DIRECT_ANSWER, "");
    }

    // ===== Thinking =====

    public String finalSummaryThinking() {
        return state.value(FINAL_SUMMARY_THINKING, "");
    }

    public String currentStepThinking() {
        return state.value(CURRENT_STEP_THINKING, "");
    }

    // ===== 共享键 =====

    public String systemPrompt() {
        return state.value(MateClawStateKeys.SYSTEM_PROMPT, "你是一个有帮助的AI助手。");
    }

    public String conversationId() {
        return state.value(MateClawStateKeys.CONVERSATION_ID, "");
    }

    public String traceId() {
        return state.value(MateClawStateKeys.TRACE_ID, "");
    }

    /**
     * The {@link vip.mate.agent.context.ChatOrigin} forwarded into graph
     * state by {@code MateClawStateAccessor.OutputBuilder.chatOrigin}.
     * Returns {@link vip.mate.agent.context.ChatOrigin#EMPTY} when nothing
     * was injected (legacy callers / non-channel entry points).
     */
    public vip.mate.agent.context.ChatOrigin chatOrigin() {
        return state.<vip.mate.agent.context.ChatOrigin>value(MateClawStateKeys.CHAT_ORIGIN)
                .orElse(vip.mate.agent.context.ChatOrigin.EMPTY);
    }

    // ===== 会话消息（复用 MateClawStateKeys.MESSAGES）=====

    @SuppressWarnings("unchecked")
    public List<Message> messages() {
        return state.<List<Message>>value(MateClawStateKeys.MESSAGES).orElse(List.of());
    }

    // ===== 工作上下文 =====

    public String workingContext() {
        return state.value(WORKING_CONTEXT, "");
    }

    // ===== 输出构建器 =====

    public static OutputBuilder output() {
        return new OutputBuilder();
    }

    /**
     * Fluent 输出构建器
     */
    public static final class OutputBuilder {
        private final Map<String, Object> map = new HashMap<>();

        private OutputBuilder() {}

        public OutputBuilder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        // ---- 输入 ----
        public OutputBuilder goal(String goal) {
            return put(GOAL, goal);
        }

        // ---- 会话消息（写入共享键 MateClawStateKeys.MESSAGES）----
        public OutputBuilder messages(List<Message> msgs) {
            return put(MateClawStateKeys.MESSAGES, msgs);
        }

        // ---- 工作上下文 ----
        public OutputBuilder workingContext(String ctx) {
            return put(WORKING_CONTEXT, ctx);
        }

        // ---- 计划 ----
        public OutputBuilder planId(Long id) {
            return put(PLAN_ID, id);
        }

        public OutputBuilder planSteps(List<String> steps) {
            return put(PLAN_STEPS, steps);
        }

        public OutputBuilder planValid(boolean valid) {
            return put(PLAN_VALID, valid);
        }

        public OutputBuilder needsPlanning(boolean needs) {
            return put(NEEDS_PLANNING, needs);
        }

        // ---- 步骤控制 ----
        public OutputBuilder currentStepIndex(int index) {
            return put(CURRENT_STEP_INDEX, index);
        }

        public OutputBuilder currentStepTitle(String title) {
            return put(CURRENT_STEP_TITLE, title);
        }

        public OutputBuilder currentStepResult(String result) {
            return put(CURRENT_STEP_RESULT, result);
        }

        /**
         * 追加到 COMPLETED_RESULTS（APPEND 策略，传入单条结果包装为 List）
         */
        public OutputBuilder completedResults(String result) {
            return put(COMPLETED_RESULTS, List.of(result));
        }

        // ---- 终止 ----
        public OutputBuilder finalSummary(String summary) {
            return put(FINAL_SUMMARY, summary);
        }

        public OutputBuilder directAnswer(String answer) {
            return put(DIRECT_ANSWER, answer);
        }

        // ---- Thinking ----
        public OutputBuilder finalSummaryThinking(String thinking) {
            return put(FINAL_SUMMARY_THINKING, thinking);
        }

        public OutputBuilder currentStepThinking(String thinking) {
            return put(CURRENT_STEP_THINKING, thinking);
        }

        // ---- 流式防重（写入共享键）----
        public OutputBuilder contentStreamed(boolean streamed) {
            return put(MateClawStateKeys.CONTENT_STREAMED, streamed);
        }

        public OutputBuilder thinkingStreamed(boolean streamed) {
            return put(MateClawStateKeys.THINKING_STREAMED, streamed);
        }

        // ---- 事件流（写入共享键 MateClawStateKeys.PENDING_EVENTS）----
        public OutputBuilder events(List<GraphEventPublisher.GraphEvent> events) {
            return put(MateClawStateKeys.PENDING_EVENTS, events);
        }

        // ---- 阶段标记（写入共享键 MateClawStateKeys.CURRENT_PHASE）----
        public OutputBuilder currentPhase(String phase) {
            return put(MateClawStateKeys.CURRENT_PHASE, phase);
        }

        // ---- Token Usage（写入共享键）----

        /** 将本次 LLM 调用的 usage 累加到 state 已有值上 */
        public OutputBuilder mergeUsage(OverAllState currentState,
                                        NodeStreamingChatHelper.StreamResult result) {
            int existingPrompt = currentState.value(MateClawStateKeys.PROMPT_TOKENS, 0);
            int existingCompletion = currentState.value(MateClawStateKeys.COMPLETION_TOKENS, 0);
            map.put(MateClawStateKeys.PROMPT_TOKENS, existingPrompt + result.promptTokens());
            map.put(MateClawStateKeys.COMPLETION_TOKENS, existingCompletion + result.completionTokens());
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }
    }
}
