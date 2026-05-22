package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.context.RuntimeContextInjector;
import vip.mate.planning.service.PlanningService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Task triage node for the Plan-Execute graph.
 * <p>
 * Decides one of three routes for the user's goal and emits a JSON directive:
 * <ul>
 *   <li>{@code direct_answer} — pure knowledge question, no tools, no planning</li>
 *   <li>single-step plan — needs tools but a single coherent action (steps=1)</li>
 *   <li>multi-step plan — genuinely independent subtasks (2–6 steps)</li>
 * </ul>
 * When {@code needs_planning} is false the node streams the direct answer
 * through {@link NodeStreamingChatHelper} and the graph exits via
 * {@code DirectAnswerNode}. Otherwise a plan is persisted via
 * {@link PlanningService} and {@code step_execution} takes over.
 * <p>
 * The previous version forced {@code needs_planning=true} whenever any tool
 * was required, producing multi-step plans for trivial single-hop tasks.
 * The revised prompt collapses single-hop tool use into a 1-step plan so the
 * executor can handle it with one ReAct-style iteration (see RFC-008).
 */
@Slf4j
public class PlanGenerationNode implements NodeAction {

    private final ChatModel chatModel;
    private final PlanningService planningService;
    private final NodeStreamingChatHelper streamingHelper;
    private final ConversationWindowManager conversationWindowManager;
    private final AgentToolSet toolSet;

    /**
     * Structured triage result — field names use @JsonProperty to match the
     * snake_case keys the LLM is instructed to produce, so no prompt changes needed.
     */
    record TriageResult(
            @JsonProperty("needs_planning") boolean needsPlanning,
            @JsonProperty("direct_answer")  String directAnswer,
            @JsonProperty("plan_type")      String planType,
            @JsonProperty("steps")          List<String> steps
    ) {}

    private static final String PLANNING_PROMPT = """
            你是任务分流器，不是聊天助手。根据用户目标把请求分到三类之一，并只输出一个 JSON 对象。

            硬性规则：
            1. 只返回一个 JSON 对象；不允许 markdown 代码块、不允许任何 JSON 以外的文字。
            2. 不要解释，不要寒暄，不要说"我来...""我先..."。
            3. 不确定时优先选择"单步"，而不是拆成多步。

            三类分流：

            (A) 直接回答 — 纯知识问答，模型凭自身知识即可回答，不需要任何工具、不需要读文件、不需要查询当前状态。
                输出：{"needs_planning": false, "direct_answer": "<你的回答>"}

            (B) 单步任务 — 需要工具，但本质是一个连贯动作（一次文件读取 / 一次搜索 / 一次命令 / 一次记忆读写 / 一次计算）。
                执行器会在这一步内部迭代调用多次工具，你**不要**提前拆分。
                输出：{"needs_planning": true, "steps": ["<将用户目标复述为一句清晰可执行的指令>"]}

            (C) 多步任务 — 用户目标包含 2 个及以上明显独立、必须先后完成的子任务（例如"先调研 A 再调研 B 然后对比"、
                "读配置、迁移数据、验证结果"）。子任务之间如果可以合并，应当合并。
                输出：{"needs_planning": true, "steps": ["步骤1", "步骤2", ...]}（2 到 6 个步骤）

            关键原则：
            - 单工具调用绝对不拆成多步。例："读 A 文件并总结" 是单步（B），不是两步。
            - 默认不要把 MEMORY.md / PROFILE.md / 技能文件读取当成独立步骤；仅当用户明确询问偏好、历史决策或长期约束时才加入。
            - 每个步骤必须是可执行动作，不写"思考一下""确认一下"之类的空话。
            - 解析不出来时，视作(B) 单步；宁愿单步也不要无脑拆分。
            """;

    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService,
                              NodeStreamingChatHelper streamingHelper,
                              ConversationWindowManager conversationWindowManager,
                              AgentToolSet toolSet) {
        this.chatModel = chatModel;
        this.planningService = planningService;
        this.streamingHelper = streamingHelper;
        this.conversationWindowManager = conversationWindowManager;
        this.toolSet = toolSet;
    }

    /**
     * @deprecated use the full-parameter constructor instead
     */
    @Deprecated
    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService) {
        this(chatModel, planningService, null, null, null);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        PlanStateAccessor accessor = new PlanStateAccessor(state);
        String goal = accessor.goal();

        // Goal follow-up injection: GoalEvaluationNode requested a re-plan
        // pass with extra guidance. The mid-pass plan state was wiped by
        // the previous node, so we run the normal planning flow but
        // append the follow-up prompt to the user goal so the planner
        // sees "do these original objectives + this next step the
        // evaluator just asked for".
        String followupPrompt = state.value(MateClawStateKeys.GOAL_FOLLOWUP_PROMPT, "");
        if (!followupPrompt.isEmpty()) {
            log.info("[PlanGeneration] Goal follow-up active, augmenting goal with {} chars of guidance",
                    followupPrompt.length());
            goal = goal + "\n\n[Follow-up guidance]\n" + followupPrompt;
        }

        String systemPrompt = accessor.systemPrompt();
        String agentId = state.value(MateClawStateKeys.TRACE_ID, "unknown");
        String conversationId = accessor.conversationId();

        log.info("[PlanGeneration] Evaluating goal: {}", goal.length() > 100 ? goal.substring(0, 100) + "..." : goal);

        List<GraphEventPublisher.GraphEvent> events = new ArrayList<>();
        events.add(GraphEventPublisher.phase("planning", Map.of("goal", goal)));

        // Replay path: plan is already in state (injected by chatWithReplayStream); skip LLM.
        Long existingPlanId = state.<Long>value(PlanStateKeys.PLAN_ID).orElse(null);
        if (existingPlanId != null) {
            List<String> existingSteps = accessor.planSteps();
            int resumeIndex = accessor.currentStepIndex();
            log.info("[PlanGeneration] Replay mode — reusing plan {} at step {}/{}", existingPlanId, resumeIndex, existingSteps.size());
            return PlanStateAccessor.output()
                    .needsPlanning(true)
                    .planId(existingPlanId)
                    .planSteps(existingSteps)
                    .planValid(true)
                    .currentStepIndex(resumeIndex)
                    .currentPhase("plan_generated")
                    .events(events)
                    .build();
        }

        try {
            // PLANNING_PROMPT is the sole system message; we deliberately do NOT
            // concatenate the agent's full systemPrompt (wiki / skill / memory guidance),
            // which would dilute the triage instructions.
            List<Message> promptMessages = new ArrayList<>();
            promptMessages.add(new SystemMessage(PLANNING_PROMPT));
            String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");
            vip.mate.agent.context.ChatOrigin chatOrigin =
                    state.<vip.mate.agent.context.ChatOrigin>value(MateClawStateKeys.CHAT_ORIGIN)
                            .orElse(vip.mate.agent.context.ChatOrigin.EMPTY);
            promptMessages.add(new UserMessage(
                    RuntimeContextInjector.buildContextMessage(workspaceBasePath, null, chatOrigin)));

            // Advertise available tools so the LLM can recognize when an action is possible,
            // but do NOT force "any tool usage implies multi-step" — single-hop tool use
            // should resolve to a 1-step plan, not a multi-step decomposition.
            if (toolSet != null && !toolSet.callbacks().isEmpty()) {
                String toolNames = toolSet.callbacks().stream()
                        .map(cb -> cb.getToolDefinition().name())
                        .collect(Collectors.joining(", "));
                promptMessages.add(new UserMessage(
                        "可用工具：" + toolNames
                                + "\n单次工具调用应归为单步（B），不要拆成多步。"));
            }

            // Inject working context (rolling conversation summary) so triage respects
            // prior constraints without re-reading full history.
            String workingContext = accessor.workingContext();
            if (!workingContext.isEmpty()) {
                promptMessages.add(new UserMessage(
                        "以下是此前对话中用户提出的约束、说明和上下文，请在分流时参考：\n\n"
                                + workingContext));
            }

            promptMessages.add(new UserMessage("用户目标：" + goal));

            // Append JSON schema hint generated by BeanOutputConverter so the LLM
            // knows the exact expected structure (replaces hand-written schema in PLANNING_PROMPT).
            BeanOutputConverter<TriageResult> converter = new BeanOutputConverter<>(TriageResult.class);
            promptMessages.add(new UserMessage(converter.getFormat()));

            Prompt prompt = new Prompt(promptMessages);

            // Broadcast a lightweight progress token so the frontend shows activity
            // during the silent triage call (typically 1-3 s).
            if (streamingHelper != null) {
                streamingHelper.broadcastProgress(conversationId, "分析中...");
            }

            // Silent streaming call — structured JSON is parsed below; tokens are not forwarded to the client.
            long triageStartMs = System.currentTimeMillis();
            NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCallSilent(
                    chatModel, prompt, conversationId, "plan_generation");

            // Prompt-too-long handling: compact the conversation window and retry once.
            if (result.isPromptTooLong() && conversationWindowManager != null) {
                log.warn("[PlanGeneration] Prompt too long, attempting compaction and retry");
                List<Message> compactedMessages = conversationWindowManager.compactForRetry(
                        promptMessages.subList(1, promptMessages.size()));
                if (compactedMessages != null) {
                    List<Message> retryMessages = new ArrayList<>();
                    retryMessages.add(promptMessages.get(0));
                    retryMessages.addAll(compactedMessages);
                    result = streamingHelper.streamCallSilent(
                            chatModel, new Prompt(retryMessages), conversationId, "plan_generation_compact_retry");
                }
            }

            long triageMs = System.currentTimeMillis() - triageStartMs;

            String llmResponse = result.text();
            log.info("[PlanGeneration] Triage completed in {}ms", triageMs);
            log.debug("[PlanGeneration] LLM response: {}", llmResponse);

            // D-6: emit triage perf summary
            events.add(GraphEventPublisher.perfSummary("triage", Map.of(
                    "triage_ms", triageMs,
                    "prompt_tokens", result.promptTokens(),
                    "completion_tokens", result.completionTokens()
            )));

            TriageResult triage = converter.convert(llmResponse);
            boolean needsPlanning = triage != null && triage.needsPlanning();

            if (!needsPlanning) {
                // Category (A): direct answer — push to client and terminate via DirectAnswerNode.
                String directAnswer = triage != null && triage.directAnswer() != null
                        ? triage.directAnswer() : llmResponse;
                log.info("[PlanGeneration] Direct-answer route taken (no tools, no planning)");

                streamingHelper.broadcastContent(conversationId, directAnswer);

                return PlanStateAccessor.output()
                        .needsPlanning(false)
                        .directAnswer(directAnswer)
                        .currentPhase("direct_answer")
                        .contentStreamed(true)
                        .thinkingStreamed(!result.thinking().isEmpty())
                        .mergeUsage(state, result)
                        .events(events)
                        .build();
            }

            // Categories (B) single-step or (C) multi-step: extract steps.
            List<String> steps = triage != null ? triage.steps() : null;
            if (steps == null || steps.isEmpty()) {
                // LLM asked for planning but produced no steps — fall back to a
                // synthetic 1-step plan using the user's goal so the executor
                // can still reach the tools. (Previous behavior dropped back to
                // direct_answer, which silently stripped tool capability.)
                log.warn("[PlanGeneration] needs_planning=true with empty steps; falling back to single-step plan");
                steps = List.of(goal);
            }

            var plan = planningService.createPlan(agentId, goal, steps);
            log.info("[PlanGeneration] Plan created: id={}, steps={} ({})",
                    plan.getId(), steps.size(), steps.size() == 1 ? "single-step" : "multi-step");

            events.add(GraphEventPublisher.planCreated(plan.getId(), steps));

            return PlanStateAccessor.output()
                    .needsPlanning(true)
                    .planId(plan.getId())
                    .planSteps(steps)
                    .planValid(true)
                    .currentStepIndex(0)
                    .currentPhase("plan_generated")
                    .contentStreamed(true)
                    .thinkingStreamed(!result.thinking().isEmpty())
                    .mergeUsage(state, result)
                    .events(events)
                    .build();

        } catch (Exception e) {
            log.error("[PlanGeneration] Triage failed, falling back to single-step plan: {}", e.getMessage(), e);
            // When the triage LLM fails or returns unparseable output we now fall back to
            // a single-step plan (the user's goal verbatim) instead of a direct text
            // answer. This preserves tool access on the failure path; the previous
            // "direct answer" fallback silently degraded tool-requiring tasks.
            try {
                var plan = planningService.createPlan(agentId, goal, List.of(goal));
                events.add(GraphEventPublisher.planCreated(plan.getId(), List.of(goal)));
                return PlanStateAccessor.output()
                        .needsPlanning(true)
                        .planId(plan.getId())
                        .planSteps(List.of(goal))
                        .planValid(true)
                        .currentStepIndex(0)
                        .currentPhase("plan_generated")
                        .events(events)
                        .build();
            } catch (Exception persistErr) {
                log.error("[PlanGeneration] Single-step fallback persistence also failed: {}", persistErr.getMessage());
                return PlanStateAccessor.output()
                        .needsPlanning(false)
                        .directAnswer("抱歉，我暂时无法完成任务分流，请重试或换一种方式描述任务。")
                        .currentPhase("direct_answer")
                        .events(events)
                        .build();
            }
        }
    }

}
