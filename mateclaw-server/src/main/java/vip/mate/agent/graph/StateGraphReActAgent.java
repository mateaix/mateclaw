package vip.mate.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
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
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.workspace.conversation.ConversationService;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 基于 StateGraph v2 的 ReAct Agent
 * <p>
 * 使用 spring-ai-alibaba-graph-core 的 StateGraph 引擎，
 * 实现显式可控的 Thought → Action → Observation 循环，
 * 含 Summarizing、LimitExceeded 和 FinalAnswer 节点。
 * <p>
 * 关键特性：
 * - 迭代次数强制控制（maxIterations 真正生效）
 * - ToolGuard 安全拦截（在 ActionNode 中执行）
 * - 工具调用过程可观测
 * - Summarizing 阶段收束冗长上下文
 * - 超限友好提示
 * - 结构化生命周期日志
 * <p>
 * content_delta 和 thinking_delta 由节点内 {@link NodeStreamingChatHelper} 直推，
 * chatStructuredStream() 只处理 phase/tool/事件等结构化事件。
 * 不再从 NodeOutput 二次整段下发已流式推送的内容。
 *
 * @author MateClaw Team
 */
@Slf4j
public class StateGraphReActAgent extends BaseAgent implements StructuredStreamCapable {

    private final CompiledGraph compiledGraph;
    private final org.springframework.ai.chat.model.ChatModel chatModel;
    private final ConversationWindowManager conversationWindowManager;
    /**
     * Held only so {@link #buildInitialState} can include the tools schema in
     * the context-window budget — those bytes ride along on every LLM call
     * and were previously ignored, making compression decisions fire late.
     * Nullable for the legacy 5-arg constructor used by older tests.
     */
    private final vip.mate.agent.AgentToolSet toolSet;

    public StateGraphReActAgent(ChatClient chatClient, ConversationService conversationService,
                                CompiledGraph compiledGraph,
                                org.springframework.ai.chat.model.ChatModel chatModel,
                                ConversationWindowManager conversationWindowManager) {
        this(chatClient, conversationService, compiledGraph, chatModel,
                conversationWindowManager, null);
    }

    public StateGraphReActAgent(ChatClient chatClient, ConversationService conversationService,
                                CompiledGraph compiledGraph,
                                org.springframework.ai.chat.model.ChatModel chatModel,
                                ConversationWindowManager conversationWindowManager,
                                vip.mate.agent.AgentToolSet toolSet) {
        super(chatClient, conversationService);
        this.compiledGraph = compiledGraph;
        this.chatModel = chatModel;
        this.conversationWindowManager = conversationWindowManager;
        this.toolSet = toolSet;
    }

    @Override
    public String chat(String userMessage, String conversationId) {
        setState(AgentState.RUNNING);
        try {
            log.info("[{}] StateGraph chat: conversationId={}", agentName, conversationId);

            Map<String, Object> inputs = buildInitialState(userMessage, conversationId);
            // Fresh thread per invocation so graph state never carries over
            // between calls. The CompiledGraph is cached and shared; without a
            // unique threadId, consecutive sync runs (e.g. back-to-back cron
            // executions) inherit the prior run's accumulated messages and
            // counters. Mirrors the streaming paths, which already do this.
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(UUID.randomUUID().toString()).build();
            Optional<OverAllState> result = compiledGraph.invoke(inputs, config);

            return result
                    .flatMap(s -> s.<String>value(FINAL_ANSWER))
                    .orElse("未能生成回答。");
        } catch (Exception e) {
            log.error("[{}] StateGraph chat failed: {}", agentName, e.getMessage(), e);
            setState(AgentState.ERROR);
            throw new RuntimeException("对话失败：" + e.getMessage(), e);
        } finally {
            if (getState() != AgentState.ERROR) {
                setState(AgentState.IDLE);
            }
        }
    }

    @Override
    public Flux<String> chatStream(String userMessage, String conversationId) {
        setState(AgentState.RUNNING);
        try {
            log.info("[{}] StateGraph stream: conversationId={}", agentName, conversationId);

            Map<String, Object> inputs = buildInitialState(userMessage, conversationId);
            String threadId = UUID.randomUUID().toString();
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

            return compiledGraph.stream(inputs, config)
                    .filter(this::hasFinalAnswer)
                    .map(this::extractFinalAnswer)
                    .filter(content -> content != null && !content.isEmpty())
                    .next()     // 只取第一个 finalAnswer，避免多个节点重复 emit
                    .flux()
                    .doOnComplete(() -> setState(AgentState.IDLE))
                    .doOnError(e -> {
                        log.error("[{}] StateGraph stream error: {}", agentName, e.getMessage());
                        setState(AgentState.ERROR);
                    });
        } catch (Exception e) {
            log.error("[{}] StateGraph stream setup failed: {}", agentName, e.getMessage(), e);
            setState(AgentState.ERROR);
            return Flux.error(e);
        }
    }

    @Override
    public String execute(String goal, String conversationId) {
        return chat(goal, conversationId);
    }

    @Override
    public String chatWithReplay(String userMessage, String conversationId, String toolCallPayload) {
        setState(AgentState.RUNNING);
        try {
            log.info("[{}] StateGraph chatWithReplay: conversationId={}", agentName, conversationId);

            Map<String, Object> inputs = buildInitialState(userMessage, conversationId);
            if (toolCallPayload != null && !toolCallPayload.isEmpty()) {
                inputs.put(FORCED_TOOL_CALL, toolCallPayload);
            }
            // Fresh thread per invocation — see chat() for rationale.
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(UUID.randomUUID().toString()).build();
            Optional<OverAllState> result = compiledGraph.invoke(inputs, config);

            return result
                    .flatMap(s -> s.<String>value(FINAL_ANSWER))
                    .orElse("工具已执行。");
        } catch (Exception e) {
            log.error("[{}] StateGraph chatWithReplay failed: {}", agentName, e.getMessage(), e);
            setState(AgentState.ERROR);
            throw new RuntimeException("重放执行失败：" + e.getMessage(), e);
        } finally {
            if (getState() != AgentState.ERROR) {
                setState(AgentState.IDLE);
            }
        }
    }

    @Override
    public Flux<AgentService.StreamDelta> chatWithReplayStream(String userMessage, String conversationId,
                                                                String toolCallPayload) {
        return chatWithReplayStream(userMessage, conversationId, toolCallPayload, "");
    }

    @Override
    public Flux<AgentService.StreamDelta> chatWithReplayStream(String userMessage, String conversationId,
                                                                String toolCallPayload, String requesterId) {
        setState(AgentState.RUNNING);
        try {
            log.info("[{}] StateGraph chatWithReplayStream: conversationId={}", agentName, conversationId);

            Map<String, Object> inputs = buildInitialState(userMessage, conversationId);
            inputs.put(REQUESTER_ID, requesterId != null ? requesterId : "");
            if (toolCallPayload != null && !toolCallPayload.isEmpty()) {
                inputs.put(FORCED_TOOL_CALL, toolCallPayload);
            }
            String threadId = UUID.randomUUID().toString();
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

            AtomicInteger sentEventCount = new AtomicInteger(0);
            AtomicInteger finalPromptTokens = new AtomicInteger(0);
            AtomicInteger finalCompletionTokens = new AtomicInteger(0);
            AtomicReference<String> finalModelName = new AtomicReference<>("");
            AtomicReference<String> finalProviderId = new AtomicReference<>("");
            // 防重保护：同 chatStructuredStream
            AtomicBoolean finalAnswerEmitted = new AtomicBoolean(false);
            AtomicBoolean finalThinkingEmitted = new AtomicBoolean(false);
            AtomicReference<String> lastEmittedStreamedContent = new AtomicReference<>("");
            // Silent-termination guard (mirrors chatStructuredStream)
            AtomicInteger lastIteration = new AtomicInteger(0);
            AtomicInteger lastSoftCap = new AtomicInteger(0);
            AtomicBoolean sawLegitimateExit = new AtomicBoolean(false);

            return BaseAgent.routingStartupDelta(inputs).concatWith(compiledGraph.stream(inputs, config)
                    .flatMapIterable(output -> {
                        List<AgentService.StreamDelta> deltas = new ArrayList<>();
                        List<GraphEventPublisher.GraphEvent> allEvents = GraphEventPublisher.extractEvents(output);
                        int newStart = sentEventCount.get();
                        if (newStart < allEvents.size()) {
                            for (int i = newStart; i < allEvents.size(); i++) {
                                var event = allEvents.get(i);
                                deltas.add(AgentService.StreamDelta.event(event.type(), event.data()));
                            }
                            sentEventCount.set(allEvents.size());
                        }

                        boolean contentAlreadyStreamed = output.state().value(CONTENT_STREAMED, false);
                        boolean thinkingAlreadyStreamed = output.state().value(THINKING_STREAMED, false);

                        // Route per-iteration STREAMED_CONTENT (reasoning preamble +
                        // SummarizingNode output) into segments only — final-answer
                        // text arrives via the FINAL_ANSWER branch below. Pre-#120
                        // this used persistOnly, which appended every iteration's
                        // narration into the persisted assistant content; next-turn
                        // replay then saw a chain of "Let me try X..." with no
                        // observations and looped retrying tools.
                        //
                        // Exception — evidence-insufficient terminal turn
                        // (ReasoningNode.java:617): when an answer is rejected for
                        // unsupported references, FINAL_ANSWER is replaced with a
                        // short "[证据不足]" warning and STREAMED_CONTENT carries the
                        // actual answer body the user/UI need to see. Falling back
                        // to persistOnly for that case keeps both the original
                        // answer text and the warning in mate_message.content; with
                        // pure segmentOnly the persisted content would shrink to
                        // just the warning, breaking single-segment renderers like
                        // copy / TTS / history reload (segments.length<=1 disables
                        // the segmented view in MessageBubble).
                        boolean isFinalAnswerTurn = hasFinalAnswer(output);
                        String streamed = output.state().<String>value(STREAMED_CONTENT).orElse("");
                        if (!streamed.isEmpty() && !streamed.equals(lastEmittedStreamedContent.get())) {
                            lastEmittedStreamedContent.set(streamed);
                            deltas.add(streamedContentDelta(isFinalAnswerTurn, streamed));
                        }

                        if (isFinalAnswerTurn && finalAnswerEmitted.compareAndSet(false, true)) {
                            String answer = extractFinalAnswer(output);
                            if (answer != null && !answer.isEmpty()) {
                                deltas.add(contentAlreadyStreamed
                                        ? AgentService.StreamDelta.persistOnly(answer, null)
                                        : new AgentService.StreamDelta(answer, null));
                            }
                        }
                        String thinking = extractFinalThinking(output);
                        if (thinking != null && !thinking.isEmpty()
                                && finalThinkingEmitted.compareAndSet(false, true)) {
                            deltas.add(thinkingAlreadyStreamed
                                    ? AgentService.StreamDelta.persistOnly(null, thinking)
                                    : new AgentService.StreamDelta(null, thinking));
                        }

                        finalPromptTokens.set(output.state().value(PROMPT_TOKENS, 0));
                        finalCompletionTokens.set(output.state().value(COMPLETION_TOKENS, 0));
                        finalModelName.set(output.state().value(RUNTIME_MODEL_NAME, ""));
                        finalProviderId.set(output.state().value(RUNTIME_PROVIDER_ID, ""));

                        lastIteration.set(output.state().value(CURRENT_ITERATION, 0));
                        lastSoftCap.set(output.state().value(MAX_ITERATIONS, 0));
                        if (hasFinalAnswer(output)
                                || Boolean.TRUE.equals(output.state().value(LIMIT_EXCEEDED, false))
                                || !output.state().<String>value(FINISH_REASON).orElse("").isBlank()) {
                            sawLegitimateExit.set(true);
                        }

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
                    .doOnComplete(() -> {
                        setState(AgentState.IDLE);
                        if (!sawLegitimateExit.get()) {
                            log.error("[{}] StateGraph replay stream completed WITHOUT a final answer / "
                                            + "limit_exceeded / finish_reason — likely framework-level silent "
                                            + "termination. conversationId={}, lastIteration={}, softCap={}",
                                    agentName, conversationId, lastIteration.get(), lastSoftCap.get());
                        }
                    })
                    .doOnError(e -> {
                        log.error("[{}] StateGraph replay stream error: {}", agentName, e.getMessage());
                        setState(AgentState.ERROR);
                    });
        } catch (Exception e) {
            setState(AgentState.ERROR);
            return Flux.error(e);
        }
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
            log.info("[{}] StateGraph structured stream: conversationId={}", agentName, conversationId);

            Map<String, Object> inputs = buildInitialState(userMessage, conversationId);
            inputs.put(REQUESTER_ID, requesterId != null ? requesterId : "");
            String threadId = UUID.randomUUID().toString();
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

            // Lambda 内需要维护已发送事件偏移；这里只是局部可变计数器，不涉及跨会话共享。
            AtomicInteger sentEventCount = new AtomicInteger(0);
            // Token usage 追踪（每次 NodeOutput 更新最新累计值，最后一次即最终值）
            AtomicInteger finalPromptTokens = new AtomicInteger(0);
            AtomicInteger finalCompletionTokens = new AtomicInteger(0);
            AtomicReference<String> finalModelName = new AtomicReference<>("");
            AtomicReference<String> finalProviderId = new AtomicReference<>("");
            // 防重保护：StateGraph 对每个节点都 emit NodeOutput，FINAL_ANSWER 一旦写入后续节点都携带，
            // 用 compareAndSet 保证只取第一次，避免 content/thinking 被重复追加
            AtomicBoolean finalAnswerEmitted = new AtomicBoolean(false);
            AtomicBoolean finalThinkingEmitted = new AtomicBoolean(false);
            // STREAMED_CONTENT 是 REPLACE 策略（每轮 ReasoningNode/SummarizingNode 覆写），
            // 用 lastEmitted 跟踪已发送的值，避免在 ActionNode/ObservationNode 的 NodeOutput 上重复发送同一段内容。
            AtomicReference<String> lastEmittedStreamedContent = new AtomicReference<>("");
            // Silent-termination guardrail: track the highest iteration / soft cap
            // observed and whether the graph reached a legitimate exit (final answer
            // or limit-exceeded node). If the framework completes the Flux without
            // either signal we log.error in doOnComplete — the graph framework
            // historically treated its own recursion cap as a silent normal
            // completion, which masked turns ending mid-execution. Decoupling the
            // recursionLimit at compile time should keep this from firing, but the
            // guard catches any future regression instead of letting it ship silent.
            AtomicInteger lastIteration = new AtomicInteger(0);
            AtomicInteger lastSoftCap = new AtomicInteger(0);
            AtomicBoolean sawLegitimateExit = new AtomicBoolean(false);

            return BaseAgent.routingStartupDelta(inputs).concatWith(compiledGraph.stream(inputs, config)
                    .flatMapIterable(output -> {
                        List<AgentService.StreamDelta> deltas = new ArrayList<>();
                        // 1. 提取所有累积的事件，只发送新增部分
                        List<GraphEventPublisher.GraphEvent> allEvents = GraphEventPublisher.extractEvents(output);
                        int newStart = sentEventCount.get();
                        if (newStart < allEvents.size()) {
                            for (int i = newStart; i < allEvents.size(); i++) {
                                var event = allEvents.get(i);
                                deltas.add(AgentService.StreamDelta.event(event.type(), event.data()));
                            }
                            sentEventCount.set(allEvents.size());
                        }

                        // 2. 内容始终通过 StreamDelta 发给 Accumulator 用于持久化
                        //    已由 NodeStreamingChatHelper 广播过的标记 persistOnly，避免前端收到重复 content_delta
                        boolean contentAlreadyStreamed = output.state()
                                .value(CONTENT_STREAMED, false);
                        boolean thinkingAlreadyStreamed = output.state()
                                .value(THINKING_STREAMED, false);

                        // 2a. Route per-iteration narrative into the segments timeline
                        //     so the segmented UI view still shows "我来…" preludes
                        //     between tool cards, but keep the top-level content
                        //     field (= persisted mate_message.content) reserved for
                        //     the final-answer span. NodeStreamingChatHelper already
                        //     broadcast the live deltas; segmentOnly suppresses
                        //     re-broadcast and skips content.append while still
                        //     populating the segments[] entry.
                        //
                        //     Exception — evidence-insufficient terminal turn
                        //     (ReasoningNode.java:617): STREAMED_CONTENT carries
                        //     the rejected answer body, FINAL_ANSWER is just the
                        //     short "[证据不足]" warning. Use persistOnly there so
                        //     mate_message.content keeps both the answer text and
                        //     the warning — single-segment renderers (copy / TTS /
                        //     history reload) read content, not segments.
                        boolean isFinalAnswerTurn = hasFinalAnswer(output);
                        String streamed = output.state().<String>value(STREAMED_CONTENT).orElse("");
                        if (!streamed.isEmpty() && !streamed.equals(lastEmittedStreamedContent.get())) {
                            lastEmittedStreamedContent.set(streamed);
                            deltas.add(streamedContentDelta(isFinalAnswerTurn, streamed));
                        }

                        if (isFinalAnswerTurn && finalAnswerEmitted.compareAndSet(false, true)) {
                            String answer = extractFinalAnswer(output);
                            if (answer != null && !answer.isEmpty()) {
                                deltas.add(contentAlreadyStreamed
                                        ? AgentService.StreamDelta.persistOnly(answer, null)
                                        : new AgentService.StreamDelta(answer, null));
                            }
                        }

                        String thinking = extractFinalThinking(output);
                        if (thinking != null && !thinking.isEmpty()
                                && finalThinkingEmitted.compareAndSet(false, true)) {
                            deltas.add(thinkingAlreadyStreamed
                                    ? AgentService.StreamDelta.persistOnly(null, thinking)
                                    : new AgentService.StreamDelta(null, thinking));
                        }

                        // 3. 更新最新累计 token usage
                        finalPromptTokens.set(output.state().value(PROMPT_TOKENS, 0));
                        finalCompletionTokens.set(output.state().value(COMPLETION_TOKENS, 0));
                        finalModelName.set(output.state().value(RUNTIME_MODEL_NAME, ""));
                        finalProviderId.set(output.state().value(RUNTIME_PROVIDER_ID, ""));

                        // 4. Silent-termination guard inputs
                        lastIteration.set(output.state().value(CURRENT_ITERATION, 0));
                        lastSoftCap.set(output.state().value(MAX_ITERATIONS, 0));
                        if (hasFinalAnswer(output)
                                || Boolean.TRUE.equals(output.state().value(LIMIT_EXCEEDED, false))
                                || !output.state().<String>value(FINISH_REASON).orElse("").isBlank()) {
                            sawLegitimateExit.set(true);
                        }

                        return deltas;
                    })
                    // 流正常完成后追加内部 usage 事件
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
                    .doOnComplete(() -> {
                        setState(AgentState.IDLE);
                        if (!sawLegitimateExit.get()) {
                            log.error("[{}] StateGraph structured stream completed WITHOUT a final answer / "
                                            + "limit_exceeded / finish_reason — likely framework-level silent "
                                            + "termination (recursionLimit reached or upstream truncation). "
                                            + "conversationId={}, lastIteration={}, softCap={}",
                                    agentName, conversationId, lastIteration.get(), lastSoftCap.get());
                        }
                    })
                    .doOnError(e -> {
                        log.error("[{}] StateGraph structured stream error: {}", agentName, e.getMessage());
                        setState(AgentState.ERROR);
                    });
        } catch (Exception e) {
            setState(AgentState.ERROR);
            return Flux.error(e);
        }
    }

    private Map<String, Object> buildInitialState(String userMessage, String conversationId) {
        // 加载会话历史
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
        // 构建当前用户消息：支持 multimodal（如果有图片附件，直接注入 Media）
        // 同步获取 routing decision，写入 state 供后续节点 / accumulator 读取。
        BaseAgent.CurrentTurnUserMessage currentTurn = buildCurrentUserMessageWithRouting(conversationId, userMessage);
        messages.add(currentTurn.userMessage());

        Map<String, Object> inputs = new HashMap<>();
        // 输入
        inputs.put(USER_MESSAGE, userMessage);
        inputs.put(CONVERSATION_ID, conversationId);
        inputs.put(AGENT_ID, agentId != null ? agentId : "");
        inputs.put(WORKSPACE_BASE_PATH, workspaceBasePath != null ? workspaceBasePath : "");
        inputs.put(SYSTEM_PROMPT, systemPrompt != null ? systemPrompt : "你是一个有帮助的AI助手。");
        inputs.put(MESSAGES, messages);
        // 迭代控制：深度思考模式允许更多迭代（思考需要更多轮工具调用）
        // maxIterations<=0 表示软上限解除（由 LLM 自己决定何时收尾），加分要短路，
        // 否则 thinking-on 会把"无限"误算成 5（变成"5 步就停"）。
        String thinkingLevel = vip.mate.llm.chatmodel.ThinkingLevelHolder.get();
        boolean thinkingOn = thinkingLevel != null && !"off".equalsIgnoreCase(thinkingLevel);
        int effectiveMaxIterations = (maxIterations <= 0)
                ? 0
                : (thinkingOn ? maxIterations + 5 : maxIterations);
        inputs.put(MAX_ITERATIONS, effectiveMaxIterations);
        inputs.put(CURRENT_ITERATION, 0);
        // 初始化新字段
        inputs.put(TOOL_CALL_COUNT, 0);
        inputs.put(ERROR_COUNT, 0);
        inputs.put(SHOULD_SUMMARIZE, false);
        inputs.put(LIMIT_EXCEEDED, false);
        inputs.put(CONTENT_STREAMED, false);
        inputs.put(THINKING_STREAMED, false);
        inputs.put(AWAITING_APPROVAL, false);
        inputs.put(STREAMED_CONTENT, "");
        inputs.put(STREAMED_THINKING, "");
        inputs.put(REQUESTER_ID, "");
        inputs.put(FORCED_TOOL_CALL, "");
        inputs.put(PROMPT_TOKENS, 0);
        inputs.put(COMPLETION_TOKENS, 0);
        inputs.put(RUNTIME_MODEL_NAME, modelName != null ? modelName : "");
        inputs.put(RUNTIME_PROVIDER_ID, runtimeProviderId != null ? runtimeProviderId : "");
        inputs.put(TRACE_ID, UUID.randomUUID().toString().substring(0, 8));

        // Multimodal sidecar routing — null when the turn carries no media or
        // the primary model already covers the modalities. Stored as a Map so
        // graph state stays JSON-friendly.
        if (currentTurn.routingDecision() != null
                && currentTurn.routingDecision().strategy() != vip.mate.llm.routing.model.MultimodalRoutingDecision.Strategy.NONE
                || (currentTurn.routingDecision() != null && !currentTurn.routingDecision().skipped().isEmpty())) {
            inputs.put(MateClawStateKeys.ROUTING_DECISION, currentTurn.routingDecision().toMap());
        }

        // RFC-063r §2.5: enrich the originating ChatOrigin with this agent's id
        // and workspace, then write it into graph state so ActionNode +
        // StepExecutionNode can forward it to ToolExecutionExecutor → ToolContext.
        vip.mate.agent.context.ChatOrigin origin = vip.mate.agent.context.ChatOriginHolder.get();
        Long parsedAgentIdForOrigin = null;
        try { parsedAgentIdForOrigin = agentId != null ? Long.valueOf(agentId) : null; } catch (Exception ignored) {}
        if (parsedAgentIdForOrigin != null) {
            origin = origin.withAgent(parsedAgentIdForOrigin);
        }
        origin = origin.withConversationId(conversationId)
                .withWorkspace(origin.workspaceId(), workspaceBasePath);
        inputs.put(CHAT_ORIGIN, origin);

        // RFC 48 — inject active goal snapshot for GoalEvaluationNode.
        // The node + dispatcher both bail out when ACTIVE_GOAL is absent,
        // so this is a no-op for conversations without a bound goal.
        // GOAL_EVALUATED_THIS_RUN explicitly seeded so the FinalAnswer→
        // GoalEvaluation conditional edge sees a clean false on each new
        // chat invocation (RFC 48 §6.3 exhaustsBudgetAndStopsLooping
        // depends on this — every new chat is a fresh evaluation pass).
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
     * Pick the right {@link AgentService.StreamDelta} flavor for the per-iteration
     * {@code STREAMED_CONTENT} the graph just emitted.
     *
     * <p>The contract:
     * <ul>
     *   <li>Intermediate ReAct iterations (no {@code FINAL_ANSWER} yet) →
     *       {@code segmentOnly}. The content is reasoning preamble / mid-loop
     *       summary that belongs in the segments timeline, not in the persisted
     *       {@code mate_message.content}.</li>
     *   <li>Terminal turn where {@code FINAL_ANSWER} is set →
     *       {@code persistOnly}. This covers the evidence-insufficient path
     *       (ReasoningNode.java:617) where {@code STREAMED_CONTENT} carries the
     *       actual rejected answer body and {@code FINAL_ANSWER} is just a short
     *       "[证据不足]" warning. Persisting the streamed body keeps single-segment
     *       renderers (copy / TTS / history reload) showing the full text.</li>
     * </ul>
     *
     * <p>Package-private so the unit test can pin the decision without standing
     * up a full StateGraph fixture. Returning {@code null} for blank input is the
     * caller's responsibility — this helper just decides flavor for non-blank
     * content.
     */
    static AgentService.StreamDelta streamedContentDelta(boolean isFinalAnswerTurn, String streamed) {
        return isFinalAnswerTurn
                ? AgentService.StreamDelta.persistOnly(streamed, null)
                : AgentService.StreamDelta.segmentOnly(streamed, null);
    }

    private boolean hasFinalAnswer(NodeOutput output) {
        if (output == null || output.state() == null) {
            return false;
        }
        return output.state().<String>value(FINAL_ANSWER)
                .filter(s -> !s.isEmpty())
                .isPresent();
    }

    private String extractFinalAnswer(NodeOutput output) {
        return output.state().<String>value(FINAL_ANSWER).orElse("");
    }

    private String extractFinalThinking(NodeOutput output) {
        if (output == null || output.state() == null) {
            return null;
        }
        return output.state().<String>value(FINAL_THINKING).orElse(null);
    }
}
