package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.context.RuntimeContextInjector;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.planning.service.PlanningService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 步骤执行节点
 * <p>
 * 执行当前步骤，使用显式工具执行循环（internalToolExecutionEnabled=false）。
 * 单步最大工具调用次数限制为 {@link #MAX_TOOL_CALLS_PER_STEP} 次，与
 * {@code BaseAgent.MAX_ITERATIONS_HARD_CEILING} 对齐——因此实际生效的上限
 * 永远是 agent 的 {@code max_iterations}（DB 列），单步本身不会先于 agent
 * 的整体预算被打掉。早期 5 次的硬限制对"查新闻 + 整理 Word"这种合理多
 * 工具任务过紧，被 LimitExceededNode 提前拦截后用户看到的是冷冰冰的
 * "工具调用次数超出最大限制"。
 * <p>
 * 支持 NEEDS_APPROVAL 审批流程：对需要审批的工具调用创建 pending，
 * 发出 SSE 事件后立即返回审批提示（非阻塞）。审批通过后通过 replay 重新执行。
 *
 * @author MateClaw Team
 */
@Slf4j
public class StepExecutionNode implements NodeAction {

    private final ChatModel chatModel;
    private final AgentToolSet toolSet;
    private final ToolExecutionExecutor executor;
    private final PlanningService planningService;
    private final ChatStreamTracker streamTracker;
    private final ConversationWindowManager conversationWindowManager;
    private final String reasoningEffort;
    private final NodeStreamingChatHelper streamingHelper;
    private final long stepWallClockTimeoutMs;

    /**
     * Per-step tool-call ceiling, aligned with {@code BaseAgent.MAX_ITERATIONS_HARD_CEILING}.
     * Matching the agent-level cap means this constant is never the bottleneck —
     * the agent's own {@code max_iterations} (DB column) will fire first if a
     * task is genuinely runaway, and a well-budgeted multi-tool step (e.g.
     * web_search + browser_navigate + browser_read*N + file_write) is no longer
     * cut short by an arbitrary 5-call ceiling.
     */
    private static final int MAX_TOOL_CALLS_PER_STEP = 100;

    /**
     * Wall-clock budget per step, complementing {@link #MAX_TOOL_CALLS_PER_STEP}.
     * The call-count cap doesn't help when a single LLM stream stalls or a
     * concurrency-unsafe tool runs synchronously without a per-tool deadline
     * (the parallel batch path enforces {@code ToolTimeoutProperties}, but the
     * single-unsafe path in {@code ToolExecutionExecutor#executeSingleTool}
     * currently does not). 10 minutes is generous for legitimate long steps
     * (large file edits, multi-page browser flows) while still cutting off the
     * pathological cases where the agent appears frozen to the user.
     */
    private static final long STEP_WALL_CLOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public StepExecutionNode(ChatModel chatModel, AgentToolSet toolSet,
                             ToolExecutionExecutor executor,
                             PlanningService planningService,
                             ChatStreamTracker streamTracker,
                             String reasoningEffort, NodeStreamingChatHelper streamingHelper,
                             ConversationWindowManager conversationWindowManager) {
        this(chatModel, toolSet, executor, planningService, streamTracker,
                reasoningEffort, streamingHelper, conversationWindowManager,
                STEP_WALL_CLOCK_TIMEOUT_MS);
    }

    /** Test-friendly overload — production callers use the default timeout. */
    StepExecutionNode(ChatModel chatModel, AgentToolSet toolSet,
                      ToolExecutionExecutor executor,
                      PlanningService planningService,
                      ChatStreamTracker streamTracker,
                      String reasoningEffort, NodeStreamingChatHelper streamingHelper,
                      ConversationWindowManager conversationWindowManager,
                      long stepWallClockTimeoutMs) {
        this.chatModel = chatModel;
        this.toolSet = toolSet;
        this.executor = executor;
        this.planningService = planningService;
        this.streamTracker = streamTracker;
        this.conversationWindowManager = conversationWindowManager;
        this.reasoningEffort = reasoningEffort;
        this.streamingHelper = streamingHelper;
        this.stepWallClockTimeoutMs = stepWallClockTimeoutMs;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        PlanStateAccessor accessor = new PlanStateAccessor(state);
        int stepIndex = accessor.currentStepIndex();
        List<String> steps = accessor.planSteps();
        Long planId = accessor.planId();
        String systemPrompt = accessor.systemPrompt();

        String conversationId = state.value(MateClawStateKeys.CONVERSATION_ID, "");
        String agentId = state.value(MateClawStateKeys.AGENT_ID, "");
        String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");
        // RFC-063r §2.5: read parent ChatOrigin from graph state so tools in
        // this step (and any DelegateAgentTool sub-graphs) inherit channel /
        // workspace / requester context.
        vip.mate.agent.context.ChatOrigin chatOrigin =
                state.<vip.mate.agent.context.ChatOrigin>value(MateClawStateKeys.CHAT_ORIGIN)
                        .orElse(vip.mate.agent.context.ChatOrigin.EMPTY);

        if (stepIndex >= steps.size()) {
            log.warn("[StepExecution] stepIndex {} >= steps.size() {}, skipping", stepIndex, steps.size());
            return PlanStateAccessor.output()
                    .currentStepResult("步骤索引越界")
                    .completedResults(formatStepResult(stepIndex, "步骤索引越界"))
                    .currentStepIndex(stepIndex + 1)
                    .build();
        }

        String step = steps.get(stepIndex);
        log.info("[StepExecution] Executing step {}/{}: {}", stepIndex + 1, steps.size(), step);

        List<GraphEventPublisher.GraphEvent> events = new ArrayList<>();
        // Iteration boundary for the plan-execute loop: each step is one
        // iteration that may itself fan out to multiple LLM calls. Reason is
        // "plan_step" so consumers can distinguish it from ReAct's
        // "react_step" / "first_turn" markers when both stream into the
        // same SSE feed.
        boolean iterationEventsOn = streamTracker == null || streamTracker.isIterationEventsEnabled();
        if (iterationEventsOn) {
            events.add(GraphEventPublisher.iterationStart(stepIndex, "plan_step", "parent", null));
        }
        events.add(GraphEventPublisher.stepStarted(stepIndex, step));
        events.add(GraphEventPublisher.phase("executing", Map.of("stepIndex", stepIndex, "stepTitle", step)));

        planningService.updateSubPlanStatus(planId, stepIndex, "running");

        // 构建消息列表
        List<Message> messages = buildStepMessages(accessor, step, systemPrompt, workspaceBasePath);

        // 显式工具执行循环
        String finalResult = null;
        String stepThinking = "";
        int toolCallCount = 0;
        boolean approvalTriggered = false;
        String approvalToolName = null;
        int stepPromptTokens = 0;
        int stepCompletionTokens = 0;

        // RFC-052: any returnDirect tool that fires inside this step must
        // short-circuit the entire plan (not just this step). We accumulate
        // outputs across the inner loop and break out as soon as one appears.
        List<DirectToolOutput> stepDirectOutputs = new ArrayList<>();

        // Wall-clock budget guards against a single hung LLM stream or
        // synchronous unsafe-tool call (the per-tool timeout is enforced only
        // in the parallel batch path of ToolExecutionExecutor). Checked at the
        // top of each iteration so we never issue another LLM call after the
        // budget is gone.
        long stepStartedAtMs = System.currentTimeMillis();
        boolean wallClockExceeded = false;

        try {
            while (toolCallCount < MAX_TOOL_CALLS_PER_STEP) {
                long elapsedMs = System.currentTimeMillis() - stepStartedAtMs;
                if (elapsedMs > stepWallClockTimeoutMs) {
                    log.warn("[StepExecution] Step {} exceeded wall-clock budget " +
                            "({} ms > {} ms) after {} tool round(s); aborting step",
                            stepIndex, elapsedMs, stepWallClockTimeoutMs, toolCallCount);
                    wallClockExceeded = true;
                    break;
                }
                // PR-2 (RFC-049 §2.3.4): always use OpenAiChatOptions so the relay
                // producer in NodeStreamingChatHelper.doStreamCall can attach the
                // user-token. Using ToolCallingChatOptions when reasoningEffort is
                // null (e.g. DeepSeek-Reasoner whose thinking is model-inherent,
                // or Kimi-K2.5) would bypass the relay and multi-round tool-calls
                // would 400 again.
                OpenAiChatOptions oaiOpts = OpenAiChatOptions.builder()
                        .toolCallbacks(toolSet.callbacks())
                        .build();
                if (StringUtils.hasText(reasoningEffort)) {
                    oaiOpts.setReasoningEffort(reasoningEffort);
                }
                oaiOpts.setInternalToolExecutionEnabled(false);
                ChatOptions options = oaiOpts;

                if (conversationWindowManager != null) {
                    // Pass conversationId + workspaceBasePath so oversized
                    // older tool results can be spilled to disk instead of
                    // being rewritten into a lossy single-line summary.
                    messages = conversationWindowManager.pruneOldToolResultsForModelInput(
                            messages, conversationId, workspaceBasePath);
                }

                NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCall(
                        chatModel, new Prompt(messages, options), conversationId,
                        "step_execution[" + stepIndex + "]");

                // PTL 处理：压缩后重试
                if (result.isPromptTooLong() && conversationWindowManager != null) {
                    log.warn("[StepExecution] Prompt too long at step {}, attempting compaction", stepIndex);
                    List<Message> compactedMessages = conversationWindowManager.compactForRetry(
                            messages.subList(1, messages.size()));
                    if (compactedMessages != null) {
                        List<Message> retryMessages = new ArrayList<>();
                        retryMessages.add(messages.get(0));
                        retryMessages.addAll(compactedMessages);
                        result = streamingHelper.streamCall(
                                chatModel, new Prompt(retryMessages, options), conversationId,
                                "step_execution_compact_retry[" + stepIndex + "]");
                    }
                }

                stepPromptTokens += result.promptTokens();
                stepCompletionTokens += result.completionTokens();

                if (!result.thinking().isEmpty()) {
                    stepThinking = result.thinking();
                }

                messages.add(result.assistantMessage());

                if (!result.hasToolCalls()) {
                    finalResult = result.text();
                    break;
                }

                // 手动执行 tool calls
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                List<AssistantMessage.ToolCall> allToolCalls = result.toolCalls();

                // 从 state 读取预批准的工具调用（replay 注入）
                String preApprovedPayload = state.value(MateClawStateKeys.PRE_APPROVED_TOOL_CALL, "");

                if (!preApprovedPayload.isEmpty()) {
                    // Replay 路径：处理预批准工具
                    for (AssistantMessage.ToolCall toolCall : allToolCalls) {
                        if (isPreApprovedToolCall(toolCall.name(), preApprovedPayload)) {
                            String storedArguments = extractArgumentsFromPayload(preApprovedPayload);
                            events.add(GraphEventPublisher.toolStart(toolCall.name(), toolCall.arguments()));
                            // RFC-052: pass the directOutputs collector so that an
                            // approved direct tool's full content is captured here
                            // (instead of leaking into the next LLM round).
                            ToolResponseMessage.ToolResponse response = executor.executePreApproved(
                                    toolCall, storedArguments, events, conversationId, workspaceBasePath,
                                    stepDirectOutputs);
                            toolResponses.add(response);
                            preApprovedPayload = ""; // 只消费一次
                        } else {
                            // 非预批准工具走正常执行器
                            ToolExecutionExecutor.ToolExecutionResult execResult = executor.execute(
                                    List.of(toolCall), conversationId, agentId, false, "", workspaceBasePath, chatOrigin);
                            toolResponses.addAll(execResult.responses());
                            events.addAll(execResult.events());
                            if (execResult.hasDirectOutputs()) {
                                stepDirectOutputs.addAll(execResult.directOutputs());
                            }
                            if (execResult.awaitingApproval()) {
                                approvalTriggered = true;
                                approvalToolName = toolCall.name();
                                break;
                            }
                        }
                    }
                } else {
                    // 正常路径：委托 ToolExecutionExecutor（支持并发执行 + 审批 barrier）
                    ToolExecutionExecutor.ToolExecutionResult execResult = executor.execute(
                            allToolCalls, conversationId, agentId, false, "", workspaceBasePath, chatOrigin);
                    toolResponses.addAll(execResult.responses());
                    events.addAll(execResult.events());
                    if (execResult.hasDirectOutputs()) {
                        stepDirectOutputs.addAll(execResult.directOutputs());
                    }
                    if (execResult.awaitingApproval()) {
                        approvalTriggered = true;
                        approvalToolName = execResult.barrierToolName() != null
                                ? execResult.barrierToolName() : "unknown";
                    }
                }

                // 将工具响应追加到消息
                ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                        .responses(toolResponses)
                        .build();
                messages.add(toolResponseMessage);
                toolCallCount++;

                // 如果审批触发，退出 while 循环
                if (approvalTriggered) {
                    break;
                }

                // RFC-052: returnDirect short-circuit. Any direct tool in this
                // step ends the plan immediately; the dispatcher routes via
                // currentPhase=plan_aborted so no further LLM call happens.
                if (!stepDirectOutputs.isEmpty()) {
                    log.info("[StepExecution] RETURN_DIRECT — step {} produced {} direct " +
                            "tool output(s); aborting plan execution",
                            stepIndex, stepDirectOutputs.size());
                    break;
                }
            }

            // 处理审批暂停
            if (approvalTriggered) {
                planningService.updateSubPlanStatus(planId, stepIndex, "awaiting_approval");
                String awaitingResult = "[APPROVAL_PENDING] " + approvalToolName + " awaiting user decision";
                return PlanStateAccessor.output()
                        .currentStepResult(awaitingResult)
                        .currentStepIndex(stepIndex)  // 不递增！下次重放从同一步开始
                        .currentPhase("awaiting_approval")
                        .contentStreamed(true)
                        .thinkingStreamed(!stepThinking.isEmpty())
                        .put(MateClawStateKeys.PROMPT_TOKENS, state.value(MateClawStateKeys.PROMPT_TOKENS, 0) + stepPromptTokens)
                        .put(MateClawStateKeys.COMPLETION_TOKENS, state.value(MateClawStateKeys.COMPLETION_TOKENS, 0) + stepCompletionTokens)
                        .events(events)
                        .build();
            }

            // RFC-052: direct tool short-circuit at the plan level. Treat the
            // assembled direct text as the final summary and abort the plan;
            // the dispatcher routes plan_aborted to END so no further LLM call
            // is made. Persisting RETURN_DIRECT_TRIGGERED + DIRECT_TOOL_OUTPUTS
            // lets the SSE accumulator pick up directToolNames metadata so
            // history scrub (BaseAgent.isDirectToolMessage) kicks in next turn.
            //
            // Plan status is "completed" (not "failed"): the user got their
            // answer correctly, the plan just terminated earlier than the
            // model's planning stage anticipated. Marking as failed would skew
            // operational dashboards and confuse plan-history readers.
            if (!stepDirectOutputs.isEmpty()) {
                String assembled = assembleDirectAnswerText(stepDirectOutputs);
                planningService.updateSubPlanResult(planId, stepIndex, assembled);
                planningService.completePlan(planId,
                        "Plan completed via returnDirect tool: " +
                        stepDirectOutputs.get(0).toolName());
                events.add(GraphEventPublisher.stepCompleted(stepIndex, assembled));
                if (iterationEventsOn) {
                    events.add(GraphEventPublisher.iterationEnd(stepIndex, "parent", null,
                            assembled != null ? assembled.length() : 0, 0));
                }
                return PlanStateAccessor.output()
                        .currentStepResult(assembled)
                        .currentStepIndex(steps.size())  // 越界 → dispatcher 收束
                        .currentPhase("plan_aborted")
                        .finalSummary(assembled)
                        .contentStreamed(false)  // 由 StateGraphPlanExecuteAgent 经 finalSummary 推送
                        .put(MateClawStateKeys.RETURN_DIRECT_TRIGGERED, true)
                        .put(MateClawStateKeys.DIRECT_TOOL_OUTPUTS, List.copyOf(stepDirectOutputs))
                        .put(MateClawStateKeys.PROMPT_TOKENS,
                                state.value(MateClawStateKeys.PROMPT_TOKENS, 0) + stepPromptTokens)
                        .put(MateClawStateKeys.COMPLETION_TOKENS,
                                state.value(MateClawStateKeys.COMPLETION_TOKENS, 0) + stepCompletionTokens)
                        .events(events)
                        .build();
            }

            if (finalResult == null) {
                if (wallClockExceeded) {
                    finalResult = "步骤执行超过最大耗时限制（"
                            + (stepWallClockTimeoutMs / 1000) + "秒），已中止本步骤";
                } else {
                    finalResult = "步骤执行超过最大工具调用次数限制（" + MAX_TOOL_CALLS_PER_STEP + "次）";
                    log.warn("[StepExecution] Step {} exceeded max tool call limit", stepIndex);
                }
            }

        } catch (Exception e) {
            log.error("[StepExecution] Step {} execution failed: {}", stepIndex, e.getMessage(), e);
            String shortError = summarizeError(e);
            planningService.updateSubPlanFailure(planId, stepIndex, shortError);
            planningService.markPlanFailed(planId, "步骤" + (stepIndex + 1) + " 执行失败：" + shortError);
            events.add(GraphEventPublisher.stepCompleted(stepIndex, shortError));
            if (iterationEventsOn) {
                events.add(GraphEventPublisher.iterationEnd(stepIndex, "parent", null,
                        shortError != null ? shortError.length() : 0, 0));
            }
            return PlanStateAccessor.output()
                    .currentStepResult(shortError)
                    .currentPhase("plan_aborted")
                    .contentStreamed(false)
                    .put(MateClawStateKeys.PROMPT_TOKENS, state.value(MateClawStateKeys.PROMPT_TOKENS, 0) + stepPromptTokens)
                    .put(MateClawStateKeys.COMPLETION_TOKENS, state.value(MateClawStateKeys.COMPLETION_TOKENS, 0) + stepCompletionTokens)
                    .events(events)
                    .build();
        }

        planningService.updateSubPlanResult(planId, stepIndex, finalResult);
        events.add(GraphEventPublisher.stepCompleted(stepIndex, finalResult));
        if (iterationEventsOn) {
            events.add(GraphEventPublisher.iterationEnd(stepIndex, "parent", null,
                    finalResult != null ? finalResult.length() : 0,
                    stepThinking != null ? stepThinking.length() : 0));
        }

        log.info("[StepExecution] Step {}/{} completed: {}",
                stepIndex + 1, steps.size(),
                finalResult.length() > 100 ? finalResult.substring(0, 100) + "..." : finalResult);

        // RFC-008 P4.2: incremental working-context update.
        // Previous behavior rebuilt the entire context from history + every
        // completed result on every step (O(N) per step). On long plans this
        // re-walks the same conversation history each iteration. Now we take
        // the previous context as-is (which already encodes earlier history
        // and earlier completed steps) and append just the freshly-completed
        // step, then trim from the head if the running total exceeds the cap.
        // For first-step calls where prior context is empty, fall through to
        // the original rebuild path so the conversation history seed is still
        // captured.
        String prevWorkingContext = accessor.workingContext();
        String formattedNewStep = formatStepResult(stepIndex, finalResult);
        String updatedWorkingContext = prevWorkingContext.isEmpty()
                ? rebuildWorkingContext(accessor,
                    appendOne(accessor.completedResults(), formattedNewStep))
                : appendStepIncremental(prevWorkingContext, formattedNewStep);

        return PlanStateAccessor.output()
                .currentStepResult(finalResult)
                .completedResults(formatStepResult(stepIndex, finalResult))
                .currentStepIndex(stepIndex + 1)
                .currentStepThinking(stepThinking)
                .workingContext(updatedWorkingContext)
                .currentPhase("step_completed")
                .contentStreamed(true)
                .thinkingStreamed(!stepThinking.isEmpty())
                .put(MateClawStateKeys.PROMPT_TOKENS, state.value(MateClawStateKeys.PROMPT_TOKENS, 0) + stepPromptTokens)
                .put(MateClawStateKeys.COMPLETION_TOKENS, state.value(MateClawStateKeys.COMPLETION_TOKENS, 0) + stepCompletionTokens)
                .events(events)
                .build();
    }

    /**
     * RFC-052: assemble the final answer text from direct tool outputs in this
     * step. Mirrors {@code FinalAnswerNode#assembleDirectAnswer} so the user
     * sees the same shape regardless of which graph (ReAct / Plan-Execute)
     * produced the answer.
     */
    private static String assembleDirectAnswerText(List<DirectToolOutput> outputs) {
        if (outputs.size() == 1) {
            return outputs.get(0).fullResult();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outputs.size(); i++) {
            DirectToolOutput out = outputs.get(i);
            if (i > 0) sb.append("\n\n");
            sb.append("### ").append(out.toolName()).append("\n");
            sb.append(out.fullResult());
        }
        return sb.toString();
    }

    private List<Message> buildStepMessages(PlanStateAccessor accessor, String step, String systemPrompt, String workspaceBasePath) {
        List<Message> messages = new ArrayList<>();

        // Layer 1: System prompt（增强指令）
        String enhancedSystemPrompt = systemPrompt + """

                你是任务执行器，只负责执行"当前步骤"。

                硬性规则：
                1. 不要先解释你要做什么，直接行动。
                2. 如果需要工具，直接调用工具，不要先用自然语言描述。
                3. 如果某个工具进入审批等待，立刻停止，不要改写命令重试，不要继续调用其他工具。
                4. 默认不要额外读取 MEMORY.md、PROFILE.md 或 memory/ 每日日记；但如果当前步骤明显依赖历史偏好、既有决策、长期约束或持续上下文，可以做一次必要的记忆读取。
                5. 不要输出"我来先看一下""现在我来..."之类的过程话术。
                6. 当前步骤完成后只返回这一步的结果，不要总结整个任务。
                7. 如果前一步已经有结果，默认信任，不要重复验证，除非当前步骤必须依赖再次确认。
                8. 每一步最多做一个必要的检查和一个必要的执行，不要无意义循环。
                """;
        messages.add(new SystemMessage(enhancedSystemPrompt));
        // 注入运行时上下文（当前时间 + 工作目录 + 发起者上下文）
        messages.add(new UserMessage(
                RuntimeContextInjector.buildContextMessage(workspaceBasePath, null, accessor.chatOrigin())));

        // Layer 2: Working context（对话历史 + 步骤结果的受控长度摘要）
        String workingContext = accessor.workingContext();
        if (!workingContext.isEmpty()) {
            messages.add(new UserMessage(
                    "以下是此前对话上下文和已完成工作的摘要，请参考但不必重复验证：\n\n"
                            + workingContext));
        }

        // Layer 3: Plan context + current step instruction
        List<String> steps = accessor.planSteps();
        int currentIndex = accessor.currentStepIndex();
        List<String> completedResults = accessor.completedResults();

        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(accessor.goal()).append("\n\n");

        // 展示计划全貌（步骤标题列表），让执行器知道自己在整个流程中的位置
        context.append("执行计划（共 ").append(steps.size()).append(" 步）：\n");
        for (int i = 0; i < steps.size(); i++) {
            String status = i < currentIndex ? "✓" : (i == currentIndex ? "→" : "○");
            context.append("  ").append(status).append(" 步骤").append(i + 1).append("：").append(steps.get(i)).append("\n");
        }
        context.append("\n");

        // Layer 4: 最近完成步骤结果（精简后，避免与 working context 重复太多）
        if (!completedResults.isEmpty()) {
            context.append("最近完成的步骤结果：\n");
            // 只保留最近 3 条，每条截断至 500 字
            List<String> recentResults = completedResults.size() > 3
                    ? completedResults.subList(completedResults.size() - 3, completedResults.size())
                    : completedResults;
            for (String result : recentResults) {
                String summary = result.length() > 500 ? result.substring(0, 500) + "…" : result;
                context.append(summary).append("\n");
            }
            context.append("\n");
        }

        // Layer 5: Current step instruction
        context.append("当前需要执行的步骤（第 ").append(currentIndex + 1).append(" 步）：").append(step);
        context.append("\n\n请执行当前步骤并给出结果。");

        messages.add(new UserMessage(context.toString()));
        return messages;
    }

    private String formatStepResult(int stepIndex, String result) {
        return String.format("步骤%d结果：%s", stepIndex + 1, result);
    }

    /**
     * 判断当前工具调用是否与预批准 payload 中的工具名匹配。
     * payload 格式: {"name":"toolName","arguments":"...","status":"running"}
     */
    private boolean isPreApprovedToolCall(String toolName, String preApprovedPayload) {
        if (preApprovedPayload == null || preApprovedPayload.isEmpty()) return false;
        try {
            JsonNode node = MAPPER.readTree(preApprovedPayload);
            String approvedName = node.path("name").asText("");
            return toolName.equals(approvedName);
        } catch (Exception e) {
            log.warn("[StepExecution] Failed to parse pre-approved payload: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 将异常转换为简短的错误摘要，避免将完整异常体（尤其是 429 JSON）写入后续 prompt。
     * <ul>
     *   <li>限流错误（429 / rate_limit / overloaded）→ 固定简短提示</li>
     *   <li>其他错误 → 取前 200 字符</li>
     * </ul>
     */
    private static String summarizeError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        }
        String lower = msg.toLowerCase();
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit")
                || lower.contains("too many requests") || lower.contains("overloaded")) {
            return "LLM 限流（rate limit），请稍后重试";
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    /**
     * 从预批准 payload 中提取完整的 arguments 字符串。
     * 审批创建时存储的是原始完整参数，优先使用，避免 LLM 流式截断导致 JSON 残缺。
     *
     * @return arguments 字符串，若解析失败返回 null（调用方回退到 LLM 流式参数）
     */
    private String extractArgumentsFromPayload(String preApprovedPayload) {
        if (preApprovedPayload == null || preApprovedPayload.isEmpty()) return null;
        try {
            JsonNode node = MAPPER.readTree(preApprovedPayload);
            JsonNode argsNode = node.path("arguments");
            if (argsNode.isMissingNode() || argsNode.isNull()) return null;
            return argsNode.asText();
        } catch (Exception e) {
            log.warn("[StepExecution] Failed to extract arguments from pre-approved payload: {}", e.getMessage());
            return null;
        }
    }

    /** Append helper used by the incremental working-context fast path. */
    private static List<String> appendOne(List<String> previous, String item) {
        List<String> out = new ArrayList<>(previous);
        out.add(item);
        return out;
    }

    /**
     * Incrementally extend the previous working context with one new step
     * result. Cheap O(1) path used for steps 2..N: avoids walking the full
     * conversation history again. The result is trimmed from the head if it
     * exceeds the same overall cap that {@link #rebuildWorkingContext}
     * enforces, so the budget invariant is preserved.
     *
     * <p>Per-step truncation: a single step result longer than 800 chars is
     * abbreviated before append, mirroring the per-step caps in
     * {@code rebuildWorkingContext}.</p>
     */
    private static String appendStepIncremental(String previousContext, String formattedStepResult) {
        final int OVERALL_CAP = 6000;
        final int PER_STEP_CAP = 800;
        String stepLine = formattedStepResult.length() > PER_STEP_CAP
                ? formattedStepResult.substring(0, PER_STEP_CAP) + "…"
                : formattedStepResult;
        String combined = previousContext + "\n" + stepLine + "\n";
        if (combined.length() <= OVERALL_CAP) {
            return combined;
        }
        // Drop oldest content from the head until we fit. Cut on a newline
        // boundary so we don't truncate mid-line.
        int overshoot = combined.length() - OVERALL_CAP;
        int cutFrom = combined.indexOf('\n', overshoot);
        if (cutFrom < 0 || cutFrom >= combined.length() - 1) {
            cutFrom = overshoot;
        } else {
            cutFrom += 1; // skip the newline itself
        }
        return "…(earlier context truncated)\n" + combined.substring(cutFrom);
    }

    /**
     * Full rebuild of working context from conversation history plus all
     * completed step results. Reused on the cold path (first step, or when
     * the incremental path can't be applied). Mirrors
     * {@code StateGraphPlanExecuteAgent.buildWorkingContext}.
     */
    private static String rebuildWorkingContext(PlanStateAccessor accessor, List<String> allCompletedResults) {
        List<Message> messages = accessor.messages();
        // messages 中最后一条通常是当前 UserMessage（goal），前面的是历史
        List<Message> history = messages.size() > 1 ? messages.subList(0, messages.size() - 1) : List.of();

        StringBuilder sb = new StringBuilder();

        // 历史消息摘要
        if (!history.isEmpty()) {
            sb.append("=== 对话历史摘要 ===\n");
            int startIdx = Math.max(0, history.size() - 10);
            for (int i = startIdx; i < history.size(); i++) {
                Message msg = history.get(i);
                String role = msg.getMessageType().name().toLowerCase();
                String content = msg.getText();
                if (content != null && !content.isEmpty()) {
                    String truncated = content.length() > 500 ? content.substring(0, 500) + "…" : content;
                    sb.append("[").append(role).append("] ").append(truncated).append("\n");
                }
            }
            sb.append("\n");
        }

        // 已完成步骤结果摘要
        if (!allCompletedResults.isEmpty()) {
            sb.append("=== 已完成步骤结果 ===\n");
            int startIdx = Math.max(0, allCompletedResults.size() - 5);
            for (int i = startIdx; i < allCompletedResults.size(); i++) {
                String result = allCompletedResults.get(i);
                String truncated = result.length() > 800 ? result.substring(0, 800) + "…" : result;
                sb.append(truncated).append("\n");
            }
        }

        // 总体截断
        String context = sb.toString();
        if (context.length() > 6000) {
            context = context.substring(0, 6000) + "\n…（上下文已截断）";
        }
        return context;
    }
}
