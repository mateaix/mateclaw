package vip.mate.agent.graph.plan;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentState;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.StructuredStreamCapable;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.planning.service.PlanningService;
import vip.mate.workspace.conversation.ConversationService;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 StateGraph 的 Plan-Execute Agent
 * <p>
 * 使用 spring-ai-alibaba-graph-core 的 StateGraph 引擎实现：
 * <ol>
 *   <li>简单问答快速退出（PlanGenerationNode 前置判断）</li>
 *   <li>多步任务：规划 → 逐步执行（带工具调用）→ 汇总</li>
 * </ol>
 * <p>
 * content_delta 和 thinking_delta 由节点内 NodeStreamingChatHelper 直推，
 * chatStructuredStream() 只处理 phase/tool/plan/step 等结构化事件。
 * 不再从 NodeOutput 二次整段下发已流式推送的内容。
 *
 * @author MateClaw Team
 */
@Slf4j
public class StateGraphPlanExecuteAgent extends BaseAgent implements StructuredStreamCapable {

    private final CompiledGraph compiledGraph;
    private final PlanningService planningService;
    private final org.springframework.ai.chat.model.ChatModel chatModel;
    private final ConversationWindowManager conversationWindowManager;
    /** Held only so context-window budget includes the tools schema. Nullable for legacy constructor. */
    private final vip.mate.agent.AgentToolSet toolSet;

    public StateGraphPlanExecuteAgent(ChatClient chatClient, ConversationService conversationService,
                                      CompiledGraph compiledGraph, PlanningService planningService,
                                      org.springframework.ai.chat.model.ChatModel chatModel,
                                      ConversationWindowManager conversationWindowManager) {
        this(chatClient, conversationService, compiledGraph, planningService,
                chatModel, conversationWindowManager, null);
    }

    public StateGraphPlanExecuteAgent(ChatClient chatClient, ConversationService conversationService,
                                      CompiledGraph compiledGraph, PlanningService planningService,
                                      org.springframework.ai.chat.model.ChatModel chatModel,
                                      ConversationWindowManager conversationWindowManager,
                                      vip.mate.agent.AgentToolSet toolSet) {
        super(chatClient, conversationService);
        this.compiledGraph = compiledGraph;
        this.planningService = planningService;
        this.chatModel = chatModel;
        this.conversationWindowManager = conversationWindowManager;
        this.toolSet = toolSet;
    }

    @Override
    public Flux<AgentService.StreamDelta> chatStructuredStream(String userMessage, String conversationId) {
        return chatStructuredStream(userMessage, conversationId, "");
    }

    @Override
    public Flux<AgentService.StreamDelta> chatStructuredStream(String userMessage, String conversationId,
                                                                String requesterId) {
        setState(AgentState.RUNNING);
        try {
            log.info("[{}] Plan-Execute structured stream: conversationId={}", agentName, conversationId);
            Map<String, Object> inputs = buildInitialState(userMessage, conversationId);
            inputs.put(MateClawStateKeys.REQUESTER_ID, requesterId != null ? requesterId : "");
            return executeStream(inputs);
        } catch (Exception e) {
            setState(AgentState.ERROR);
            return Flux.error(e);
        }
    }

    @Override
    public Flux<AgentService.StreamDelta> chatWithReplayStream(String userMessage, String conversationId,
                                                                String toolCallPayload) {
        setState(AgentState.RUNNING);
        try {
            log.info("[{}] Plan-Execute replay stream: conversationId={}", agentName, conversationId);
            Map<String, Object> inputs = buildInitialState(userMessage, conversationId);

            // 从 DB 恢复 awaiting_approval 状态的计划上下文
            PlanningService.PlanResumeContext ctx = planningService.findAwaitingApprovalContext();
            if (ctx != null) {
                inputs.put(PlanStateKeys.PLAN_ID, ctx.planId());
                inputs.put(PlanStateKeys.PLAN_STEPS, ctx.steps());
                inputs.put(PlanStateKeys.NEEDS_PLANNING, true);
                inputs.put(PlanStateKeys.PLAN_VALID, true);
                inputs.put(PlanStateKeys.CURRENT_STEP_INDEX, ctx.awaitingStepIndex());
                if (!ctx.completedResults().isEmpty()) {
                    inputs.put(PlanStateKeys.COMPLETED_RESULTS, ctx.completedResults());
                    // 重建 working context，包含历史消息和已完成步骤结果
                    @SuppressWarnings("unchecked")
                    List<Message> messages = (List<Message>) inputs.get(MateClawStateKeys.MESSAGES);
                    // messages 中最后一条是当前 UserMessage，去掉再算历史
                    List<Message> history = messages.size() > 1
                            ? messages.subList(0, messages.size() - 1) : List.of();
                    inputs.put(PlanStateKeys.WORKING_CONTEXT,
                            buildWorkingContext(history, ctx.completedResults()));
                }
                log.info("[{}] Replay: restored plan {} at step {}/{}", agentName,
                        ctx.planId(), ctx.awaitingStepIndex(), ctx.steps().size());
            } else {
                log.warn("[{}] Replay: no awaiting-approval plan found, falling back to fresh run", agentName);
            }

            // 注入预批准的工具调用，StepExecutionNode 匹配后跳过 ToolGuard
            if (toolCallPayload != null && !toolCallPayload.isEmpty()) {
                inputs.put(MateClawStateKeys.PRE_APPROVED_TOOL_CALL, toolCallPayload);
            }

            return executeStream(inputs);
        } catch (Exception e) {
            setState(AgentState.ERROR);
            return Flux.error(e);
        }
    }

    /** 公共流执行逻辑，由 chatStructuredStream 和 chatWithReplayStream 共用 */
    private Flux<AgentService.StreamDelta> executeStream(Map<String, Object> inputs) {
        String threadId = UUID.randomUUID().toString();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        AtomicInteger sentEventCount = new AtomicInteger(0);
        AtomicInteger finalPromptTokens = new AtomicInteger(0);
        AtomicInteger finalCompletionTokens = new AtomicInteger(0);
        AtomicReference<String> finalModelName = new AtomicReference<>("");
        AtomicReference<String> finalProviderId = new AtomicReference<>("");
        // 去重：记录上一次已持久化的 step 结果和 thinking，防止 PlanSummaryNode 重复 emit 上一步内容
        AtomicReference<String> lastPersistedStepResult = new AtomicReference<>("");
        AtomicReference<String> lastPersistedStepThinking = new AtomicReference<>("");

        return BaseAgent.routingStartupDelta(inputs).concatWith(compiledGraph.stream(inputs, config)
                .flatMapIterable(output -> {
                    List<AgentService.StreamDelta> deltas = new ArrayList<>();
                    // 1. 提取事件（只发送新增部分）
                    List<GraphEventPublisher.GraphEvent> allEvents = GraphEventPublisher.extractEvents(output);
                    int newStart = sentEventCount.get();
                    if (newStart < allEvents.size()) {
                        for (int i = newStart; i < allEvents.size(); i++) {
                            var event = allEvents.get(i);
                            deltas.add(AgentService.StreamDelta.event(event.type(), event.data()));
                        }
                        sentEventCount.set(allEvents.size());
                    }

                    // 2. 内容始终通过 StreamDelta 返回（用于持久化），已广播过的标记 persistOnly 避免重复推送
                    boolean contentAlreadyStreamed = output.state()
                            .value(MateClawStateKeys.CONTENT_STREAMED, false);
                    boolean thinkingAlreadyStreamed = output.state()
                            .value(MateClawStateKeys.THINKING_STREAMED, false);

                    // 2a. 各步骤执行结果（StepExecutionNode 已通过 NodeStreamingChatHelper 直推 SSE，
                    //     这里仅作为 persistOnly 送入 Accumulator，确保写入 mate_message）
                    //     利用内容本身去重，避免 PlanSummaryNode 输出时重复 emit 上一步残留在 state 的值
                    output.state().<String>value(PlanStateKeys.CURRENT_STEP_RESULT)
                            .filter(s -> !s.isEmpty())
                            .filter(s -> !s.equals(lastPersistedStepResult.get()))
                            .ifPresent(stepContent -> {
                                deltas.add(AgentService.StreamDelta.persistOnly(stepContent, null));
                                lastPersistedStepResult.set(stepContent);
                            });

                    output.state().<String>value(PlanStateKeys.CURRENT_STEP_THINKING)
                            .filter(s -> !s.isEmpty())
                            .filter(s -> !s.equals(lastPersistedStepThinking.get()))
                            .ifPresent(stepThinking -> {
                                deltas.add(AgentService.StreamDelta.persistOnly(null, stepThinking));
                                lastPersistedStepThinking.set(stepThinking);
                            });

                    // 2b. 最终汇总
                    output.state().<String>value(PlanStateKeys.FINAL_SUMMARY)
                            .filter(s -> !s.isEmpty())
                            .ifPresent(summary -> deltas.add(contentAlreadyStreamed
                                    ? AgentService.StreamDelta.persistOnly(summary, null)
                                    : new AgentService.StreamDelta(summary, null)));

                    output.state().<String>value(PlanStateKeys.FINAL_SUMMARY_THINKING)
                            .filter(s -> !s.isEmpty())
                            .ifPresent(thinking -> deltas.add(thinkingAlreadyStreamed
                                    ? AgentService.StreamDelta.persistOnly(null, thinking)
                                    : new AgentService.StreamDelta(null, thinking)));

                    // 3. 更新最新累计 token usage
                    finalPromptTokens.set(output.state().value(MateClawStateKeys.PROMPT_TOKENS, 0));
                    finalCompletionTokens.set(output.state().value(MateClawStateKeys.COMPLETION_TOKENS, 0));
                    finalModelName.set(output.state().value(MateClawStateKeys.RUNTIME_MODEL_NAME, ""));
                    finalProviderId.set(output.state().value(MateClawStateKeys.RUNTIME_PROVIDER_ID, ""));

                    return deltas;
                })
                .concatWith(Mono.fromSupplier(() -> {
                    if (finalPromptTokens.get() > 0 || finalCompletionTokens.get() > 0) {
                        return AgentService.StreamDelta.event("_usage_final", Map.of(
                                "promptTokens", finalPromptTokens.get(),
                                "completionTokens", finalCompletionTokens.get(),
                                "runtimeModelName", finalModelName.get(),
                                "runtimeProviderId", finalProviderId.get()
                        ));
                    }
                    return null;
                }).flatMapMany(d -> d != null ? Flux.just(d) : Flux.empty())))
                .doOnComplete(() -> setState(AgentState.IDLE))
                .doOnError(e -> {
                    log.error("[{}] Plan-Execute stream error: {}", agentName, e.getMessage());
                    setState(AgentState.ERROR);
                });
    }

    @Override
    public String chat(String userMessage, String conversationId) {
        // 委托到 chatStructuredStream，过滤事件，拼接内容
        return chatStructuredStream(userMessage, conversationId)
                .filter(delta -> !delta.isEvent() && delta.content() != null)
                .map(AgentService.StreamDelta::content)
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();
    }

    @Override
    public Flux<String> chatStream(String userMessage, String conversationId) {
        // 委托到 chatStructuredStream，过滤事件，只保留内容
        return chatStructuredStream(userMessage, conversationId)
                .filter(delta -> !delta.isEvent() && delta.content() != null)
                .map(AgentService.StreamDelta::content);
    }

    @Override
    public String execute(String goal, String conversationId) {
        // 同 chat()，走同一套 Plan-Execute Graph
        return chat(goal, conversationId);
    }

    private Map<String, Object> buildInitialState(String userMessage, String conversationId) {
        // 加载会话历史（复用 BaseAgent.buildConversationHistory，与 ReAct 对齐）
        List<Message> historyMessages = buildConversationHistory(conversationId, userMessage);

        // 上下文窗口管理：裁剪超出模型 context window 的历史（含当前消息预算）
        if (conversationWindowManager != null) {
            Long parsedAgentId = null;
            try { parsedAgentId = Long.valueOf(agentId); } catch (Exception ignored) {}
            historyMessages = conversationWindowManager.fitToWindow(
                    historyMessages,
                    systemPrompt != null ? systemPrompt : "",
                    userMessage,
                    maxInputTokens,
                    chatModel,
                    conversationId,
                    parsedAgentId,
                    toolSet != null ? toolSet.callbacks() : null,
                    workspaceBasePath);
        }

        List<Message> messages = new ArrayList<>(historyMessages);
        BaseAgent.CurrentTurnUserMessage currentTurn = buildCurrentUserMessageWithRouting(conversationId, userMessage);
        messages.add(currentTurn.userMessage());

        // 构建 working context：对历史消息做受控长度摘要
        String workingContext = buildWorkingContext(historyMessages, List.of());

        Map<String, Object> inputs = new HashMap<>();
        inputs.put(PlanStateKeys.GOAL, userMessage);
        inputs.put(MateClawStateKeys.SYSTEM_PROMPT,
                systemPrompt != null ? systemPrompt : "你是一个有帮助的AI助手。");
        inputs.put(MateClawStateKeys.CONVERSATION_ID, conversationId);
        inputs.put(MateClawStateKeys.AGENT_ID, agentId != null ? agentId : "");
        inputs.put(MateClawStateKeys.WORKSPACE_BASE_PATH, workspaceBasePath != null ? workspaceBasePath : "");
        // 注入会话消息（复用 MateClawStateKeys.MESSAGES，与 ReAct 一致）
        inputs.put(MateClawStateKeys.MESSAGES, messages);
        // 注入 working context
        inputs.put(PlanStateKeys.WORKING_CONTEXT, workingContext);
        inputs.put(PlanStateKeys.CURRENT_STEP_INDEX, 0);
        inputs.put(MateClawStateKeys.CONTENT_STREAMED, false);
        inputs.put(MateClawStateKeys.THINKING_STREAMED, false);
        inputs.put(MateClawStateKeys.STREAMED_CONTENT, "");
        inputs.put(MateClawStateKeys.STREAMED_THINKING, "");
        inputs.put(MateClawStateKeys.REQUESTER_ID, "");
        inputs.put(MateClawStateKeys.PROMPT_TOKENS, 0);
        inputs.put(MateClawStateKeys.COMPLETION_TOKENS, 0);
        inputs.put(MateClawStateKeys.RUNTIME_MODEL_NAME, modelName != null ? modelName : "");
        inputs.put(MateClawStateKeys.RUNTIME_PROVIDER_ID, runtimeProviderId != null ? runtimeProviderId : "");
        inputs.put(MateClawStateKeys.TRACE_ID, UUID.randomUUID().toString().substring(0, 8));

        if (currentTurn.routingDecision() != null
                && (currentTurn.routingDecision().strategy() != vip.mate.llm.routing.model.MultimodalRoutingDecision.Strategy.NONE
                        || !currentTurn.routingDecision().skipped().isEmpty())) {
            inputs.put(MateClawStateKeys.ROUTING_DECISION, currentTurn.routingDecision().toMap());
        }

        // RFC-063r §2.5: same as ReAct path — enrich and store the ChatOrigin
        // so StepExecutionNode (and any sub-graphs spawned via DelegateAgentTool)
        // can read it back from state.
        vip.mate.agent.context.ChatOrigin origin = vip.mate.agent.context.ChatOriginHolder.get();
        Long parsedAgentIdForOrigin = null;
        try { parsedAgentIdForOrigin = agentId != null ? Long.valueOf(agentId) : null; } catch (Exception ignored) {}
        if (parsedAgentIdForOrigin != null) {
            origin = origin.withAgent(parsedAgentIdForOrigin);
        }
        origin = origin.withConversationId(conversationId)
                .withWorkspace(origin.workspaceId(), workspaceBasePath);
        inputs.put(MateClawStateKeys.CHAT_ORIGIN, origin);

        // RFC 48 — inject active goal snapshot for GoalEvaluationNode.
        // Mirrors StateGraphReActAgent.buildInitialState exactly.
        if (goalService != null && conversationId != null && !conversationId.isBlank()) {
            try {
                vip.mate.goal.model.GoalEntity active =
                        goalService.findActiveByConversation(conversationId);
                if (active != null) {
                    inputs.put(MateClawStateKeys.ACTIVE_GOAL, active);
                }
            } catch (Exception e) {
                log.warn("[{}] findActiveByConversation failed: {}", agentName, e.getMessage());
            }
        }
        inputs.put(MateClawStateKeys.GOAL_EVALUATED_THIS_RUN, false);
        inputs.put(MateClawStateKeys.GOAL_FOLLOWUP_INJECTED, false);
        inputs.put(MateClawStateKeys.GOAL_FOLLOWUP_PROMPT, "");

        return inputs;
    }

    /**
     * 构建受控长度的 working context。
     * <p>
     * 将会话历史 + 已完成步骤结果压缩为结构化摘要块，
     * 避免 prompt 随对话和步骤执行无限膨胀。
     * <p>
     * 规则：
     * <ul>
     *   <li>历史消息：保留最近 MAX_HISTORY_MESSAGES 条，每条截断至 MAX_MSG_CHARS 字符</li>
     *   <li>步骤结果：保留最近 MAX_STEP_RESULTS 条，每条截断至 MAX_STEP_CHARS 字符</li>
     *   <li>总体截断至 MAX_CONTEXT_CHARS 字符</li>
     * </ul>
     */
    static String buildWorkingContext(List<Message> historyMessages, List<String> completedResults) {
        StringBuilder sb = new StringBuilder();

        // 历史消息摘要
        if (historyMessages != null && !historyMessages.isEmpty()) {
            sb.append("=== 对话历史摘要 ===\n");
            int startIdx = Math.max(0, historyMessages.size() - MAX_HISTORY_MESSAGES);
            for (int i = startIdx; i < historyMessages.size(); i++) {
                Message msg = historyMessages.get(i);
                String role = msg.getMessageType().name().toLowerCase();
                String content = msg.getText();
                if (content != null && !content.isEmpty()) {
                    String truncated = content.length() > MAX_MSG_CHARS
                            ? content.substring(0, MAX_MSG_CHARS) + "…" : content;
                    sb.append("[").append(role).append("] ").append(truncated).append("\n");
                }
            }
            sb.append("\n");
        }

        // 已完成步骤结果摘要
        if (completedResults != null && !completedResults.isEmpty()) {
            sb.append("=== 已完成步骤结果 ===\n");
            int startIdx = Math.max(0, completedResults.size() - MAX_STEP_RESULTS);
            for (int i = startIdx; i < completedResults.size(); i++) {
                String result = completedResults.get(i);
                String truncated = result.length() > MAX_STEP_CHARS
                        ? result.substring(0, MAX_STEP_CHARS) + "…" : result;
                sb.append(truncated).append("\n");
            }
        }

        // 总体截断
        String context = sb.toString();
        if (context.length() > MAX_CONTEXT_CHARS) {
            context = context.substring(0, MAX_CONTEXT_CHARS) + "\n…（上下文已截断）";
        }
        return context;
    }

    // Working context 长度控制参数
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final int MAX_MSG_CHARS = 500;
    private static final int MAX_STEP_RESULTS = 5;
    private static final int MAX_STEP_CHARS = 800;
    private static final int MAX_CONTEXT_CHARS = 6000;
}
