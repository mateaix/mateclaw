package vip.mate.agent;

// PR-0b: DashScope imports moved with the construction code into AgentDashScopeChatModelBuilder.
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// PR-0b: Anthropic imports moved with the construction code into AgentAnthropicChatModelBuilder.
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import vip.mate.agent.ThinkingLevelHolder;
import vip.mate.agent.graph.StateGraphReActAgent;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.edge.ObservationDispatcher;
import vip.mate.agent.graph.edge.ReasoningDispatcher;
import vip.mate.agent.graph.lifecycle.ReActLifecycleListener;
import vip.mate.agent.graph.node.*;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent;
import vip.mate.agent.graph.plan.edge.PlanGenerationDispatcher;
import vip.mate.agent.graph.plan.edge.StepProgressDispatcher;
import vip.mate.agent.graph.plan.node.*;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.config.GraphObservationProperties;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelFamily;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.routing.ProviderRouter;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.planning.service.PlanningService;
import vip.mate.skill.service.SkillService;
import vip.mate.system.service.SystemSettingService;
import vip.mate.tool.ToolRegistry;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.tool.guard.service.ToolGuardService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.wiki.service.WikiContextService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 图构建器
 * <p>
 * 纯构建器，不做执行。从 AgentService 中提取出所有 Agent 实例构建逻辑，
 * 包括模型创建、图编译、prompt 增强等。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGraphBuilder {

    private final ToolRegistry toolRegistry;
    private final AgentBindingService agentBindingService;
    private final SkillService skillService;
    private final vip.mate.skill.runtime.SkillRuntimeService skillRuntimeService;
    private final ConversationService conversationService;
    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;
    private final vip.mate.llm.service.ModelCapabilityService modelCapabilityService;
    private final ProviderRouter providerRouter;
    private final PlanningService planningService;
    private final ToolGuardService toolGuardService;
    private final vip.mate.tool.guard.service.ToolGuardConfigService toolGuardConfigService;
    private final ApprovalWorkflowService approvalService;
    private final ChatStreamTracker streamTracker;
    private final SystemSettingService systemSettingService;
    // PR-0b: dashScopeChatModel + dashScopeConnectionProperties live on AgentDashScopeChatModelBuilder now.
    private final RetryTemplate retryTemplate;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectMapper objectMapper;
    private final GraphObservationProperties graphObservationProperties;
    private final vip.mate.config.ToolTimeoutProperties toolTimeoutProperties;
    private final MemoryManager memoryManager;
    private final WorkspaceFileService workspaceFileService;
    private final vip.mate.agent.context.ConversationWindowManager conversationWindowManager;
    private final vip.mate.llm.chatgpt.ChatGPTResponsesClient chatGPTResponsesClient;
    private final WikiContextService wikiContextService;
    private final vip.mate.workspace.core.service.WorkspaceService workspaceService;
    private final vip.mate.llm.cache.AnthropicCacheOptionsFactory anthropicCacheOptionsFactory;
    private final vip.mate.llm.cache.LlmCacheMetricsAggregator llmCacheMetricsAggregator;
    private final vip.mate.agent.graph.executor.ToolResultStorage toolResultStorage;
    private final vip.mate.tool.ToolConcurrencyRegistry toolConcurrencyRegistry;
    private final vip.mate.i18n.I18nService i18nService;
    private final vip.mate.llm.failover.ProviderHealthTracker providerHealthTracker;
    private final vip.mate.llm.chatmodel.ProviderChatModelFactory chatModelFactory;
    private final vip.mate.llm.failover.AvailableProviderPool providerPool;
    private final vip.mate.tool.document.GeneratedFileCache generatedFileCache;
    /** PR-0b: DashScope-specific construction lives here now; we only call into it for the search-on log. */
    private final vip.mate.agent.chatmodel.AgentDashScopeChatModelBuilder dashScopeBuilder;
    private final vip.mate.llm.routing.MultimodalRouter multimodalRouter;
    private final vip.mate.llm.routing.MediaCaptionService mediaCaptionService;

    /**
     * Optional audit pipeline. Setter injection (rather than a constructor
     * parameter) keeps existing constructor-based wiring + tests intact.
     * When present, the executor receives it so child-agent denied-tool
     * attempts can be recorded.
     */
    private vip.mate.audit.service.AuditEventService auditEventService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditEventService(vip.mate.audit.service.AuditEventService s) {
        this.auditEventService = s;
    }

    /**
     * 根据 AgentEntity 构建完整的 Agent 实例
     */
    public BaseAgent build(AgentEntity entity) {
        AgentToolSet toolSet = toolRegistry.getEnabledToolSet();

        // 过滤掉 denied 工具，使模型完全看不到它们（防止 prompt injection 利用 schema）
        toolSet = toolSet.withDeniedToolsFiltered(toolGuardConfigService.getDeniedTools());

        // RFC-090 §14.2 — single entry point that merges:
        //   (a) tools expanded from bound skills' active features, and
        //   (b) directly bound atomic tools (the Advanced bypass, §9.2 调整 B).
        // Three-state semantics: null = no agent-level restriction (use
        // global default); non-null (possibly empty) = explicit allowlist.
        Set<String> boundTools = agentBindingService.getEffectiveToolNames(entity.getId());
        toolSet = toolSet.withAllowedToolsOnly(boundTools); // null = 全局默认

        // RFC-090 §9.2 调整 C — pick a primary model that satisfies
        // the agent's bound-skill requires-model. Falls back to the
        // global default when no preferred provider satisfies, so the
        // existing "no default model" error path stays intact.
        // Honor per-Agent model override when set.
        // resolveModel() looks up entity.modelName in enabled-only models;
        // null / blank / unmatched silently fall back to getDefaultModel(),
        // preserving the legacy behavior for Agents without an override.
        ModelConfigEntity globalDefault;
        try {
            globalDefault = modelConfigService.resolveModel(entity.getModelName());
        } catch (Exception e) {
            throw new MateClawException("err.agent.no_default_model", "无法构建 Agent：请先在「设置 → 模型」中配置并启用默认模型");
        }
        ModelConfigEntity runtimeModel;
        try {
            runtimeModel = providerRouter.selectPrimary(entity.getId(), globalDefault);
            if (runtimeModel == null) runtimeModel = globalDefault;
        } catch (Exception e) {
            log.debug("[ProviderRouter] primary selection failed, falling back to global default: {}",
                    e.getMessage());
            runtimeModel = globalDefault;
        }
        // Even after the upgrade, log a WARN when the chosen primary
        // still doesn't satisfy needs (e.g. no preferred provider was
        // capable). The diagnostic is observability-only.
        try {
            providerRouter.diagnosePrimary(entity.getId(), runtimeModel);
        } catch (Exception e) {
            log.debug("[ProviderRouter] diagnostic failed: {}", e.getMessage());
        }

        ModelProviderEntity provider;
        try {
            provider = modelProviderService.getProviderConfig(runtimeModel.getProvider());
        } catch (Exception e) {
            throw new MateClawException("err.agent.model_not_configured", "模型 " + runtimeModel.getModelName()
                    + " 的 Provider（" + runtimeModel.getProvider() + "）未配置，请检查模型设置");
        }

        // Safety net: getDefaultModel() already skips unconfigured providers, but guard here
        // too so a stale cached model doesn't silently proceed to a broken API call.
        if (!modelProviderService.isProviderConfigured(provider.getProviderId())) {
            String reason = modelProviderService.getProviderUnavailableReason(provider.getProviderId());
            log.warn("Runtime model {}/{} provider not configured ({}); trying fallback",
                    runtimeModel.getProvider(), runtimeModel.getModelName(), reason);
            ModelConfigEntity fallback = findFirstAvailableChatModel();
            if (fallback == null) {
                throw new MateClawException("err.agent.no_configured_model",
                        "默认模型 Provider「" + runtimeModel.getProvider() + "」未配置（" + reason
                        + "），且找不到其他已配置的 Provider，请先在「设置 → 模型」中完成配置");
            }
            runtimeModel = fallback;
            try {
                provider = modelProviderService.getProviderConfig(runtimeModel.getProvider());
            } catch (Exception e) {
                throw new MateClawException("err.agent.model_not_configured", "备用模型 " + runtimeModel.getModelName()
                        + " 的 Provider（" + runtimeModel.getProvider() + "）获取失败");
            }
        }

        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());

        // 内置搜索检测（DashScope / Kimi），但不再移除 WebSearchTool — 两者协同而非互斥
        boolean builtinSearchEnabled = false;
        Map<String, Object> providerKwargs = modelProviderService.readProviderGenerateKwargs(provider);
        if (protocol == ModelProtocol.DASHSCOPE_NATIVE) {
            builtinSearchEnabled = dashScopeBuilder.isBuiltinSearchEnabled(runtimeModel, provider);
        } else if (isKimiProvider(provider) && Boolean.TRUE.equals(providerKwargs.get("enableSearch"))) {
            builtinSearchEnabled = true;
        }
        if (builtinSearchEnabled) {
            // Phase 2: 不再移除 search 工具，改为在 prompt 中设定优先级引导
            // 内置搜索作为首选，search 工具作为补充/兜底
            log.info("内置搜索已开启 (provider={})，search 工具保留作为补充通道", provider.getProviderId());
        }
        // Default 100 if DB row leaves max_iterations null. Negative or zero is an
        // explicit opt-in to "no soft cap" — ObservationDispatcher already treats
        // maxIterations<=0 as "do not enforce", so the agent runs until the LLM
        // emits a final answer (or returnDirect short-circuits). Positive values
        // are clamped to the hard ceiling so a misconfigured row can't skip the
        // safety net unintentionally.
        int rawMaxIter = entity.getMaxIterations() != null ? entity.getMaxIterations() : 100;
        int maxIter;
        if (rawMaxIter <= 0) {
            maxIter = 0;
            log.info("Agent {} max_iterations={} → unlimited soft cap (LLM controls termination)",
                    entity.getId(), rawMaxIter);
        } else {
            maxIter = Math.min(rawMaxIter, BaseAgent.MAX_ITERATIONS_HARD_CEILING);
            if (maxIter != rawMaxIter) {
                log.warn("Agent {} max_iterations={} clamped to {} (1..{})",
                        entity.getId(), rawMaxIter, maxIter, BaseAgent.MAX_ITERATIONS_HARD_CEILING);
            }
        }

        String enhancedPrompt = buildEnhancedPrompt(entity, builtinSearchEnabled,
                boundTools, runtimeModel.getMaxInputTokens());

        // 当前仅支持 DashScope 和 OpenAI-compatible，其他协议直接拒绝
        if (!supportsStateGraph(protocol)) {
            throw new MateClawException("err.agent.protocol_not_supported", "当前不支持协议 " + protocol.getId()
                    + "，请切换到 DashScope 或 OpenAI-compatible 模型");
        }

        BaseAgent agent;
        boolean toolCallingEnabled;
        if ("plan_execute".equals(entity.getAgentType())) {
            agent = buildPlanExecuteAgent(toolSet, runtimeModel, maxIter, entity.getId());
            toolCallingEnabled = true;
            log.info("Built StateGraph Plan-Execute agent: {} (maxIterations={}, tools={}, protocol={})",
                    entity.getName(), maxIter, toolSet.size(), protocol.getId());
        } else {
            agent = buildReActAgent(toolSet, runtimeModel, maxIter, entity.getId());
            // StateGraph 路径下工具调用由 ActionNode 控制，始终启用
            toolCallingEnabled = true;
            log.info("Built StateGraph ReAct agent: {} (maxIterations={}, tools={}, protocol={})",
                    entity.getName(), maxIter, toolSet.size(), protocol.getId());
        }

        // 设置通用属性
        agent.agentId = String.valueOf(entity.getId());
        agent.agentName = entity.getName();
        agent.systemPrompt = enhancedPrompt;
        agent.maxIterations = maxIter;
        agent.modelName = runtimeModel.getModelName();
        agent.modelCapabilities = modelCapabilityService.resolve(
                runtimeModel.getModelName(), runtimeModel.getModalities());
        agent.runtimeProviderId = provider != null ? provider.getProviderId() : "";
        agent.runtimeModelConfig = runtimeModel;
        agent.toolSet = toolSet;
        agent.multimodalRouter = multimodalRouter;
        agent.mediaCaptionService = mediaCaptionService;
        agent.userLocale = resolveLocale();
        agent.temperature = runtimeModel.getTemperature();
        agent.maxTokens = runtimeModel.getMaxTokens();
        agent.maxInputTokens = runtimeModel.getMaxInputTokens();
        agent.topP = runtimeModel.getTopP();
        agent.toolCallingEnabled = toolCallingEnabled;

        // 查找工作区活动目录
        if (entity.getWorkspaceId() != null) {
            try {
                var workspace = workspaceService.getById(entity.getWorkspaceId());
                if (workspace != null && workspace.getBasePath() != null && !workspace.getBasePath().isBlank()) {
                    agent.workspaceBasePath = workspace.getBasePath();
                    log.info("Agent {} bound to workspace basePath: {}", entity.getName(), agent.workspaceBasePath);
                }
            } catch (Exception e) {
                log.warn("Failed to lookup workspace basePath for agent {}: {}", entity.getName(), e.getMessage());
            }
        }

        log.info("Built agent instance: {} (type={}, protocol={}, tools={}, toolCallingEnabled={})",
                entity.getName(), entity.getAgentType(), protocol.getId(),
                toolSet.size(), agent.toolCallingEnabled);
        return agent;
    }

    // ==================== Agent 构建方法 ====================

    StateGraphReActAgent buildReActAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel, int maxIter) {
        return buildReActAgent(toolSet, runtimeModel, maxIter, null);
    }

    StateGraphReActAgent buildReActAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel,
                                         int maxIter, Long agentId) {
        ChatModel chatModel = buildRuntimeChatModel(runtimeModel);
        ChatClient chatClient = ChatClient.create(chatModel);
        String reasoningEffort = resolveReasoningEffortForModel(runtimeModel);
        CompiledGraph compiledGraph = buildReActGraph(toolSet, chatModel, maxIter, reasoningEffort, runtimeModel, agentId);
        return new StateGraphReActAgent(chatClient, conversationService, compiledGraph,
                chatModel, conversationWindowManager, toolSet);
    }

    StateGraphPlanExecuteAgent buildPlanExecuteAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel, int maxIter) {
        return buildPlanExecuteAgent(toolSet, runtimeModel, maxIter, null);
    }

    StateGraphPlanExecuteAgent buildPlanExecuteAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel,
                                                     int maxIter, Long agentId) {
        ChatModel chatModel = buildRuntimeChatModel(runtimeModel);
        ChatClient chatClient = ChatClient.create(chatModel);
        String reasoningEffort = resolveReasoningEffortForModel(runtimeModel);
        CompiledGraph graph = buildPlanExecuteGraph(toolSet, chatModel, maxIter, reasoningEffort, runtimeModel, agentId);
        return new StateGraphPlanExecuteAgent(chatClient, conversationService, graph, planningService,
                chatModel, conversationWindowManager, toolSet);
    }

    CompiledGraph buildPlanExecuteGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations, String reasoningEffort) {
        return buildPlanExecuteGraph(toolSet, chatModel, maxIterations, reasoningEffort, null, null);
    }

    CompiledGraph buildPlanExecuteGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                         String reasoningEffort, ModelConfigEntity primaryModelConfig) {
        return buildPlanExecuteGraph(toolSet, chatModel, maxIterations, reasoningEffort, primaryModelConfig, null);
    }

    CompiledGraph buildPlanExecuteGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                         String reasoningEffort, ModelConfigEntity primaryModelConfig,
                                         Long agentId) {
        try {
            List<vip.mate.llm.failover.FallbackEntry> fallbackChain = buildFallbackChain(primaryModelConfig, agentId);
            NodeStreamingChatHelper streamingHelper = new NodeStreamingChatHelper(
                    streamTracker, fallbackChain, llmCacheMetricsAggregator, providerHealthTracker,
                    primaryModelConfig != null ? primaryModelConfig.getProvider() : null,
                    providerPool);
            ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, toolGuardService, approvalService, streamTracker, toolTimeoutProperties, toolResultStorage, toolConcurrencyRegistry);
            // Issue #46: enable skill-aware "Tool not found" hint so when the
            // LLM mis-calls a skill name as a tool, the response tells it
            // the right invocation pattern instead of a dead-end error.
            executor.setSkillRuntimeService(skillRuntimeService);
            // Optional: route child-agent denied-tool audit events through
            // the audit pipeline. Null when audit is not wired (legacy / test).
            if (auditEventService != null) {
                executor.setAuditEventService(auditEventService);
            }
            PlanGenerationNode planGenerationNode = new PlanGenerationNode(chatModel, planningService, streamingHelper, conversationWindowManager, toolSet);
            StepExecutionNode stepExecutionNode = new StepExecutionNode(chatModel, toolSet, executor, planningService, streamTracker, reasoningEffort, streamingHelper, conversationWindowManager);
            PlanSummaryNode planSummaryNode = new PlanSummaryNode(chatModel, planningService, streamingHelper);
            DirectAnswerNode directAnswerNode = new DirectAnswerNode();

            KeyStrategyFactory keyStrategyFactory = KeyStrategy.builder()
                    // 共享键
                    .addStrategy(MateClawStateKeys.PENDING_EVENTS, KeyStrategy.APPEND)
                    .addStrategy(MateClawStateKeys.CURRENT_PHASE, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.SYSTEM_PROMPT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.CONVERSATION_ID, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TRACE_ID, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.AGENT_ID, KeyStrategy.REPLACE)
                    // 会话消息（复用 ReAct 的 MESSAGES key，APPEND 策略）
                    .addStrategy(MateClawStateKeys.MESSAGES, KeyStrategy.APPEND)
                    // Plan 特有键
                    .addStrategy(PlanStateKeys.GOAL, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.PLAN_ID, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.PLAN_STEPS, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.PLAN_VALID, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.NEEDS_PLANNING, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_INDEX, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_TITLE, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_RESULT, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.COMPLETED_RESULTS, KeyStrategy.APPEND)
                    .addStrategy(PlanStateKeys.FINAL_SUMMARY, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.DIRECT_ANSWER, KeyStrategy.REPLACE)
                    // 工作上下文（REPLACE 策略，每次重新生成）
                    .addStrategy(PlanStateKeys.WORKING_CONTEXT, KeyStrategy.REPLACE)
                    // Thinking 键
                    .addStrategy(PlanStateKeys.FINAL_SUMMARY_THINKING, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_THINKING, KeyStrategy.REPLACE)
                    // 流式防重键
                    .addStrategy(MateClawStateKeys.CONTENT_STREAMED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.THINKING_STREAMED, KeyStrategy.REPLACE)
                    // 流式内容暂存（AWAITING_APPROVAL 路径持久化使用）
                    .addStrategy(MateClawStateKeys.STREAMED_CONTENT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.STREAMED_THINKING, KeyStrategy.REPLACE)
                    // 请求者身份（审批身份校验使用）
                    .addStrategy(MateClawStateKeys.REQUESTER_ID, KeyStrategy.REPLACE)
                    // 审批重放键
                    .addStrategy(MateClawStateKeys.FORCED_TOOL_CALL, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.PRE_APPROVED_TOOL_CALL, KeyStrategy.REPLACE)
                    // RFC-063r §2.5: ChatOrigin must survive every node merge so
                    // sub-graph nodes (StepExecutionNode + DelegateAgentTool's
                    // child agents) can read the originating channel binding.
                    // Without explicit REPLACE the framework's merge drops it
                    // on multi-iteration paths — root cause of the channel-binding
                    // flakiness reported on first deployment.
                    .addStrategy(MateClawStateKeys.CHAT_ORIGIN, KeyStrategy.REPLACE)
                    // Caught by StateKeyRegistrationCoverageTest — these state keys
                    // were silently unregistered before the post-deploy audit.
                    // WORKSPACE_BASE_PATH: written by buildInitialState; sub-graph
                    //   tools read it via WorkspacePathGuard.
                    // STOP_REQUESTED: external cancel flag checked by every node.
                    // RETURN_DIRECT_TRIGGERED / DIRECT_TOOL_OUTPUTS (RFC-052):
                    //   Plan-Execute itself doesn't trigger returnDirect, but
                    //   DelegateAgentTool sub-agents could; register defensively.
                    .addStrategy(MateClawStateKeys.WORKSPACE_BASE_PATH, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.STOP_REQUESTED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RETURN_DIRECT_TRIGGERED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.DIRECT_TOOL_OUTPUTS, KeyStrategy.REPLACE)
                    // Token Usage
                    .addStrategy(MateClawStateKeys.PROMPT_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.COMPLETION_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_MODEL_NAME, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_PROVIDER_ID, KeyStrategy.REPLACE)
                    // SourceEvidenceLedger: ActionNode 把每轮 ToolResponse 抽取出的
                    // (sourcePaths, sourceSymbols, failedPaths) merge 进这个 ledger，
                    // 后续 ReasoningNode / FinalAnswerNode 调 validateAnswer 校验
                    // 模型引用是否有真实证据。漏注册时框架在多 node merge 时会偶发
                    // 丢这个键，evidence_insufficient 检查会"静默地不生效" ——
                    // StateKeyRegistrationCoverageTest 专门兜这条。
                    .addStrategy(MateClawStateKeys.SOURCE_EVIDENCE_LEDGER, KeyStrategy.REPLACE)
                    // Multimodal sidecar routing decision for the current turn.
                    .addStrategy(MateClawStateKeys.ROUTING_DECISION, KeyStrategy.REPLACE)
                    .build();

            // Graph 拓扑：
            // START → PLAN_GENERATION → (PlanGenerationDispatcher)
            //   ├→ DIRECT_ANSWER_NODE → END
            //   └→ STEP_EXECUTION → (StepProgressDispatcher)
            //       ├→ STEP_EXECUTION (loop)
            //       └→ PLAN_SUMMARY → END

            StateGraph graph = new StateGraph("plan-execute-agent", keyStrategyFactory)
                    .addNode(PlanStateKeys.PLAN_GENERATION_NODE,
                            AsyncNodeAction.node_async(planGenerationNode))
                    .addNode(PlanStateKeys.STEP_EXECUTION_NODE,
                            AsyncNodeAction.node_async(stepExecutionNode))
                    .addNode(PlanStateKeys.PLAN_SUMMARY_NODE,
                            AsyncNodeAction.node_async(planSummaryNode))
                    .addNode(PlanStateKeys.DIRECT_ANSWER_NODE,
                            AsyncNodeAction.node_async(directAnswerNode))
                    .addEdge(StateGraph.START, PlanStateKeys.PLAN_GENERATION_NODE)
                    .addConditionalEdges(PlanStateKeys.PLAN_GENERATION_NODE,
                            AsyncEdgeAction.edge_async(new PlanGenerationDispatcher()),
                            Map.of(
                                    PlanStateKeys.STEP_EXECUTION_NODE, PlanStateKeys.STEP_EXECUTION_NODE,
                                    PlanStateKeys.DIRECT_ANSWER_NODE, PlanStateKeys.DIRECT_ANSWER_NODE))
                    .addConditionalEdges(PlanStateKeys.STEP_EXECUTION_NODE,
                            AsyncEdgeAction.edge_async(new StepProgressDispatcher()),
                            Map.of(
                                    PlanStateKeys.STEP_EXECUTION_NODE, PlanStateKeys.STEP_EXECUTION_NODE,
                                    PlanStateKeys.PLAN_SUMMARY_NODE, PlanStateKeys.PLAN_SUMMARY_NODE,
                                    StateGraph.END, StateGraph.END))
                    .addEdge(PlanStateKeys.PLAN_SUMMARY_NODE, StateGraph.END)
                    .addEdge(PlanStateKeys.DIRECT_ANSWER_NODE, StateGraph.END);

            return graph.compile(CompileConfig.builder()
                    .recursionLimit(frameworkRecursionLimit())
                    .build());
        } catch (Exception e) {
            throw new MateClawException("err.agent.plan_compile_failed", "Plan-Execute StateGraph 编译失败: " + e.getMessage());
        }
    }

    /**
     * Hard ceiling for the underlying graph framework's recursion guard.
     * <p>
     * The framework treats "recursion limit reached" as a normal completion —
     * it emits a {@code done} signal with no exception and no log. That makes
     * it indistinguishable from a real final answer downstream, and is the
     * mechanism by which a turn can silently stop mid-execution and persist
     * only whatever partial content the accumulator happened to hold.
     * <p>
     * To avoid that class of bug, the recursion limit must be sized so it can
     * <em>never</em> trip before the soft cap (ObservationDispatcher →
     * LimitExceededNode), which is the only path that produces a proper
     * {@code finish_reason} and human-facing message. Sized for the maximum
     * effective soft cap (DB hard ceiling + thinking-mode bonus) multiplied
     * by 4 (each iteration is worst-case reasoning + summarizing + action +
     * observation) plus a 100-step buffer for phase nodes, approval replays
     * and tool-result chunking. Decoupled from the per-agent value so a small
     * {@code max_iterations} can never accidentally re-introduce the silent
     * killer.
     */
    private static int frameworkRecursionLimit() {
        return (BaseAgent.MAX_ITERATIONS_HARD_CEILING + 5) * 4 + 100;
    }

    CompiledGraph buildReActGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations, String reasoningEffort) {
        return buildReActGraph(toolSet, chatModel, maxIterations, reasoningEffort, null, null);
    }

    CompiledGraph buildReActGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                   String reasoningEffort, ModelConfigEntity primaryModelConfig) {
        return buildReActGraph(toolSet, chatModel, maxIterations, reasoningEffort, primaryModelConfig, null);
    }

    CompiledGraph buildReActGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                   String reasoningEffort, ModelConfigEntity primaryModelConfig,
                                   Long agentId) {
        try {
            List<vip.mate.llm.failover.FallbackEntry> fallbackChain = buildFallbackChain(primaryModelConfig, agentId);
            NodeStreamingChatHelper streamingHelper = new NodeStreamingChatHelper(
                    streamTracker, fallbackChain, llmCacheMetricsAggregator, providerHealthTracker,
                    primaryModelConfig != null ? primaryModelConfig.getProvider() : null,
                    providerPool);
            ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, toolGuardService, approvalService, streamTracker, toolTimeoutProperties, toolResultStorage, toolConcurrencyRegistry);
            // Issue #46: enable skill-aware "Tool not found" hint so when the
            // LLM mis-calls a skill name as a tool, the response tells it
            // the right invocation pattern instead of a dead-end error.
            executor.setSkillRuntimeService(skillRuntimeService);
            // Optional: route child-agent denied-tool audit events through
            // the audit pipeline. Null when audit is not wired (legacy / test).
            if (auditEventService != null) {
                executor.setAuditEventService(auditEventService);
            }
            // PR-1.2 (RFC-049 L1-B): propagate the bound model's capability so ReasoningNode
            // can gate the ThinkingLevelHolder override explicitly, rather than inferring
            // capability from reasoningEffort == null.
            boolean supportsReasoningEffort = primaryModelConfig != null
                    && ModelFamily.detect(primaryModelConfig.getModelName()).supportsReasoningEffort();
            ReasoningNode reasoningNode = new ReasoningNode(chatModel, toolSet, reasoningEffort,
                    supportsReasoningEffort,
                    streamingHelper, conversationWindowManager, streamTracker, 0, wikiContextService);
            ActionNode actionNode = new ActionNode(executor, streamTracker);
            ObservationProcessor observationProcessor = new ObservationProcessor(graphObservationProperties);
            ObservationNode observationNode = new ObservationNode(observationProcessor, streamTracker);
            SummarizingNode summarizingNode = new SummarizingNode(chatModel, streamingHelper, streamTracker);
            LimitExceededNode limitExceededNode = new LimitExceededNode(chatModel, observationProcessor, streamingHelper, i18nService);
            FinalAnswerNode finalAnswerNode = new FinalAnswerNode(generatedFileCache);

            KeyStrategyFactory keyStrategyFactory = KeyStrategy.builder()
                    // 输入字段
                    .addStrategy(MateClawStateKeys.USER_MESSAGE, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.CONVERSATION_ID, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.SYSTEM_PROMPT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.AGENT_ID, KeyStrategy.REPLACE)
                    // 消息列表（追加策略）
                    .addStrategy(MateClawStateKeys.MESSAGES, KeyStrategy.APPEND)
                    // 迭代控制
                    .addStrategy(MateClawStateKeys.CURRENT_ITERATION, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.MAX_ITERATIONS, KeyStrategy.REPLACE)
                    // 工具调用
                    .addStrategy(MateClawStateKeys.TOOL_CALLS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TOOL_RESULTS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TOOL_CALL_COUNT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.LLM_CALL_COUNT, KeyStrategy.REPLACE)
                    // 控制流
                    .addStrategy(MateClawStateKeys.FINAL_ANSWER, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.NEEDS_TOOL_CALL, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.ERROR, KeyStrategy.REPLACE)
                    // 观察历史（REPLACE 策略，由 ObservationNode 手动累加，SummarizingNode 可清空）
                    .addStrategy(MateClawStateKeys.OBSERVATION_HISTORY, KeyStrategy.REPLACE)
                    // Summarizing
                    .addStrategy(MateClawStateKeys.SUMMARIZED_CONTEXT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.FINAL_ANSWER_DRAFT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.SHOULD_SUMMARIZE, KeyStrategy.REPLACE)
                    // 终止控制
                    .addStrategy(MateClawStateKeys.FINISH_REASON, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.LIMIT_EXCEEDED, KeyStrategy.REPLACE)
                    // 统计与追踪
                    .addStrategy(MateClawStateKeys.ERROR_COUNT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TRACE_ID, KeyStrategy.REPLACE)
                    // 事件流
                    .addStrategy(MateClawStateKeys.PENDING_EVENTS, KeyStrategy.APPEND)
                    .addStrategy(MateClawStateKeys.CURRENT_PHASE, KeyStrategy.REPLACE)
                    // Thinking
                    .addStrategy(MateClawStateKeys.FINAL_THINKING, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.CURRENT_THINKING, KeyStrategy.REPLACE)
                    // 流式防重
                    .addStrategy(MateClawStateKeys.CONTENT_STREAMED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.THINKING_STREAMED, KeyStrategy.REPLACE)
                    // 审批控制
                    .addStrategy(MateClawStateKeys.AWAITING_APPROVAL, KeyStrategy.REPLACE)
                    // 流式内容暂存（AWAITING_APPROVAL 路径持久化使用）
                    .addStrategy(MateClawStateKeys.STREAMED_CONTENT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.STREAMED_THINKING, KeyStrategy.REPLACE)
                    // 请求者身份（审批身份校验使用）
                    .addStrategy(MateClawStateKeys.REQUESTER_ID, KeyStrategy.REPLACE)
                    // 审批重放
                    .addStrategy(MateClawStateKeys.FORCED_TOOL_CALL, KeyStrategy.REPLACE)
                    // RFC-063r §2.5: ChatOrigin must survive every node merge so
                    // ActionNode (and DelegateAgentTool's child agents) can read
                    // the originating channel binding across multi-iteration ReAct
                    // loops. Without explicit REPLACE the framework's merge drops
                    // it after the first node transition — root cause of the
                    // channel-binding flakiness reported on first deployment.
                    .addStrategy(MateClawStateKeys.CHAT_ORIGIN, KeyStrategy.REPLACE)
                    // Caught by StateKeyRegistrationCoverageTest — silently
                    // unregistered before the audit. WORKSPACE_BASE_PATH from
                    // initial state; STOP_REQUESTED is the external cancel flag;
                    // RETURN_DIRECT_TRIGGERED / DIRECT_TOOL_OUTPUTS are RFC-052
                    // returnDirect short-circuit signals consumed by ObservationDispatcher.
                    .addStrategy(MateClawStateKeys.WORKSPACE_BASE_PATH, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.STOP_REQUESTED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RETURN_DIRECT_TRIGGERED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.DIRECT_TOOL_OUTPUTS, KeyStrategy.REPLACE)
                    // Token Usage
                    .addStrategy(MateClawStateKeys.PROMPT_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.COMPLETION_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_MODEL_NAME, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_PROVIDER_ID, KeyStrategy.REPLACE)
                    // SourceEvidenceLedger: ActionNode 把每轮 ToolResponse 抽取出的
                    // (sourcePaths, sourceSymbols, failedPaths) merge 进这个 ledger，
                    // 后续 ReasoningNode / FinalAnswerNode 调 validateAnswer 校验
                    // 模型引用是否有真实证据。漏注册时框架在多 node merge 时会偶发
                    // 丢这个键，evidence_insufficient 检查会"静默地不生效" ——
                    // StateKeyRegistrationCoverageTest 专门兜这条。
                    .addStrategy(MateClawStateKeys.SOURCE_EVIDENCE_LEDGER, KeyStrategy.REPLACE)
                    // Multimodal sidecar routing decision for the current turn.
                    .addStrategy(MateClawStateKeys.ROUTING_DECISION, KeyStrategy.REPLACE)
                    .build();

            StateGraph graph = new StateGraph("react-agent-v2", keyStrategyFactory)
                    .addNode(MateClawStateKeys.REASONING_NODE,
                            AsyncNodeAction.node_async(reasoningNode))
                    .addNode(MateClawStateKeys.ACTION_NODE,
                            AsyncNodeAction.node_async(actionNode))
                    .addNode(MateClawStateKeys.OBSERVATION_NODE,
                            AsyncNodeAction.node_async(observationNode))
                    .addNode(MateClawStateKeys.SUMMARIZING_NODE,
                            AsyncNodeAction.node_async(summarizingNode))
                    .addNode(MateClawStateKeys.LIMIT_EXCEEDED_NODE,
                            AsyncNodeAction.node_async(limitExceededNode))
                    .addNode(MateClawStateKeys.FINAL_ANSWER_NODE,
                            AsyncNodeAction.node_async(finalAnswerNode))
                    .addEdge(StateGraph.START, MateClawStateKeys.REASONING_NODE)
                    .addConditionalEdges(MateClawStateKeys.REASONING_NODE,
                            AsyncEdgeAction.edge_async(new ReasoningDispatcher()),
                            Map.of(MateClawStateKeys.ACTION_NODE, MateClawStateKeys.ACTION_NODE,
                                    MateClawStateKeys.SUMMARIZING_NODE, MateClawStateKeys.SUMMARIZING_NODE,
                                    MateClawStateKeys.FINAL_ANSWER_NODE, MateClawStateKeys.FINAL_ANSWER_NODE,
                                    MateClawStateKeys.LIMIT_EXCEEDED_NODE, MateClawStateKeys.LIMIT_EXCEEDED_NODE))
                    .addEdge(MateClawStateKeys.ACTION_NODE, MateClawStateKeys.OBSERVATION_NODE)
                    .addConditionalEdges(MateClawStateKeys.OBSERVATION_NODE,
                            AsyncEdgeAction.edge_async(new ObservationDispatcher()),
                            Map.of(MateClawStateKeys.REASONING_NODE, MateClawStateKeys.REASONING_NODE,
                                    MateClawStateKeys.SUMMARIZING_NODE, MateClawStateKeys.SUMMARIZING_NODE,
                                    MateClawStateKeys.LIMIT_EXCEEDED_NODE, MateClawStateKeys.LIMIT_EXCEEDED_NODE,
                                    MateClawStateKeys.FINAL_ANSWER_NODE, MateClawStateKeys.FINAL_ANSWER_NODE))
                    .addEdge(MateClawStateKeys.SUMMARIZING_NODE, MateClawStateKeys.REASONING_NODE)
                    .addEdge(MateClawStateKeys.LIMIT_EXCEEDED_NODE, MateClawStateKeys.FINAL_ANSWER_NODE)
                    .addEdge(MateClawStateKeys.FINAL_ANSWER_NODE, StateGraph.END);

            return graph.compile(CompileConfig.builder()
                    .recursionLimit(frameworkRecursionLimit())
                    .withLifecycleListener(new ReActLifecycleListener())
                    .build());
        } catch (Exception e) {
            throw new MateClawException("err.agent.graph_compile_failed", "StateGraph v2 编译失败: " + e.getMessage());
        }
    }

    // ==================== 协议能力判断 ====================

    private boolean supportsStateGraph(ModelProtocol protocol) {
        return protocol == ModelProtocol.DASHSCOPE_NATIVE
                || protocol == ModelProtocol.OPENAI_COMPATIBLE
                || protocol == ModelProtocol.ANTHROPIC_MESSAGES
                // RFC-062: Claude Code OAuth tunnels through the same Messages API
                // wrapped in AnthropicChatModel — same StateGraph capability surface.
                || protocol == ModelProtocol.ANTHROPIC_CLAUDE_CODE
                || protocol == ModelProtocol.OPENAI_CHATGPT;
    }

    // ==================== 模型构建 ====================

    /**
     * 构建运行时 ChatModel（不包装为 ChatClient）
     * 用于 StateGraph 节点直接调用。使用注入的共享 {@link #retryTemplate} 作为 Spring AI
     * 内层重试策略。
     */
    public ChatModel buildRuntimeChatModel(ModelConfigEntity runtimeModel) {
        return buildRuntimeChatModel(runtimeModel, this.retryTemplate);
    }

    /**
     * Resolve the user-facing locale used for sidecar caption prompts.
     * Reads {@code language} from system settings; falls back to
     * {@code zh-CN} so CN deployments stay consistent with the chat UI.
     */
    private java.util.Locale resolveLocale() {
        try {
            String lang = systemSettingService.getLanguage();
            if (lang == null || lang.isBlank()) return java.util.Locale.SIMPLIFIED_CHINESE;
            return java.util.Locale.forLanguageTag(lang);
        } catch (Exception e) {
            return java.util.Locale.SIMPLIFIED_CHINESE;
        }
    }

    /**
     * 构建运行时 ChatModel，并指定自定义的 Spring AI {@link RetryTemplate}。
     * <p>
     * 用于调用方（如 Wiki 消化管线）已经有自己的外层重试策略，
     * 希望绕过 Spring AI 内层重试、独占重试控制权的场景：传入
     * {@code RetryTemplate.builder().maxAttempts(1).build()} 即可把内层降级为"只跑一次"。
     * <p>
     * DashScope 和 OpenAI-ChatGPT 分支不走 Spring AI 的 RetryTemplate 接口，
     * 本参数对它们无效（它们各自有内部重试或直通）。
     */
    public ChatModel buildRuntimeChatModel(ModelConfigEntity runtimeModel, RetryTemplate retryOverride) {
        // PR-0 (RFC-009 Phase 4 prelude): protocol switch extracted to
        // ProviderChatModelFactory + per-protocol ChatModelBuilder strategies.
        // Per-protocol builders (DashScope / OpenAI-compatible / Anthropic /
        // ChatGPT-Responses) live in vip.mate.agent.chatmodel + vip.mate.llm.chatmodel.
        // See RFC-009 Phase 4 plan for the rationale (circular-dep break for
        // ProviderInitProbe + AgentGraphBuilder slimming).
        return chatModelFactory.buildFor(runtimeModel, retryOverride);
    }

    /**
     * RFC-009: build the full multi-provider failover chain for a primary
     * model. Providers are read from {@code mate_model_provider} ordered by
     * {@code fallback_priority ASC} (positive values only), each resolved to
     * its default {@link ModelConfigEntity} and turned into a {@link ChatModel}
     * via {@link #buildRuntimeChatModel(ModelConfigEntity, RetryTemplate)}.
     *
     * <p>Providers whose API key / base URL is missing (build throws) are
     * <b>silently skipped</b> with a warning — fallback should never break
     * the primary call path. The returned list preserves chain order; the
     * streaming helper tries entries in order until one succeeds.</p>
     *
     * <p>The primary model is excluded from the chain when its provider +
     * model name matches a chain entry. Previously only reference equality
     * was checked, which meant a DashScope-primary deployment ended up with
     * {@code null} fallback — exactly the case RFC-009 targets.</p>
     *
     * @param primaryModelConfig the {@code ModelConfigEntity} used to build
     *     the primary model; used to identity-filter the chain
     * @return ordered, possibly-empty list of fallback {@link ChatModel}s
     */
    List<vip.mate.llm.failover.FallbackEntry> buildFallbackChain(ModelConfigEntity primaryModelConfig) {
        return buildFallbackChain(primaryModelConfig, null);
    }

    /**
     * RFC-009 PR-3 overload: when {@code agentId} is non-null, the agent's
     * {@code mate_agent_provider_preference} rows bias the chain order — listed
     * providers come first in their declared {@code sort_order}, then the
     * remaining providers fall in by global {@code fallback_priority} ascending,
     * tie-broken by provider id alphabetically. {@code null} agentId keeps the
     * pre-PR-3 ordering (pure global priority) — that's the path for legacy
     * callers and tests.
     *
     * <p><b>Source = the available pool</b> (RFC-009 follow-up). Earlier this
     * method only considered providers with {@code fallback_priority > 0}, which
     * meant any provider the user hadn't explicitly opted into the chain was
     * silently excluded — even if it was healthy and in the pool. The pool is
     * the source of truth for "what's usable right now"; {@code fallback_priority}
     * is just an ordering hint within the pool.</p>
     *
     * <p><b>Per-provider model selection</b> falls back gracefully: the
     * provider's {@code is_default=true} chat model wins, otherwise we pick
     * the first enabled chat model on that provider. Forcing users to mark a
     * default per provider was administrative friction with no real benefit.</p>
     */
    List<vip.mate.llm.failover.FallbackEntry> buildFallbackChain(ModelConfigEntity primaryModelConfig,
                                                                  Long agentId) {
        List<ModelProviderEntity> providers;
        try {
            // Pull every configured provider, not just the ones with
            // fallback_priority > 0 — pool membership is what gates usability,
            // not this admin-set hint.
            providers = modelProviderService.listProviders().stream()
                    .filter(dto -> Boolean.TRUE.equals(dto.getConfigured()))
                    .map(dto -> {
                        try {
                            return modelProviderService.getProviderConfig(dto.getId());
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            log.warn("[LlmFailover] failed to load configured providers: {}; running without fallback",
                    e.getMessage());
            return List.of();
        }
        if (providers.isEmpty()) {
            return List.of();
        }

        // Order: explicit fallback_priority > 0 wins (asc), priority == 0 trails alphabetically.
        providers.sort((a, b) -> {
            int pa = a.getFallbackPriority() == null ? 0 : a.getFallbackPriority();
            int pb = b.getFallbackPriority() == null ? 0 : b.getFallbackPriority();
            if (pa > 0 && pb > 0) return Integer.compare(pa, pb);
            if (pa > 0) return -1;          // a has explicit priority, comes first
            if (pb > 0) return 1;           // b has explicit priority, comes first
            return a.getProviderId().compareTo(b.getProviderId()); // both 0: alphabetical
        });

        String primaryProviderId = primaryModelConfig != null ? primaryModelConfig.getProvider() : null;
        String primaryModelName = primaryModelConfig != null ? primaryModelConfig.getModelName() : null;

        // RFC-009 PR-3: bias by agent preferences (if any). Listed providers win
        // their declared order; everything else keeps the global priority order.
        List<String> preferred = agentId == null
                ? java.util.Collections.emptyList()
                : agentBindingService.getPreferredProviderIds(agentId);
        if (!preferred.isEmpty()) {
            providers = reorderByPreferences(providers, preferred);
            log.debug("[LlmFailover] agent={} preferences={} -> chain head reordered", agentId, preferred);
        }

        // RFC-090 §9.2 调整 C — second-pass reorder: lift providers
        // that satisfy the bound-skill capability set (vision / video /
        // audio) ahead of those that don't. Stable otherwise so the
        // user-preferred order still wins among capable providers.
        try {
            providers = new ArrayList<>(providerRouter.reorderForCapabilities(agentId, providers));
        } catch (Exception e) {
            log.debug("[ProviderRouter] chain reorder failed: {}", e.getMessage());
        }

        List<vip.mate.llm.failover.FallbackEntry> chain = new ArrayList<>();
        for (ModelProviderEntity p : providers) {
            // Don't put the primary provider's row into the fallback chain — same-instance
            // skipping is also done in the runtime walker, but excluding here saves building
            // a duplicate ChatModel at agent-build time.
            if (primaryProviderId != null && primaryProviderId.equals(p.getProviderId())) {
                log.debug("[LlmFailover] skipping primary provider {} in fallback chain", primaryProviderId);
                continue;
            }
            // RFC-009 Phase 4: skip providers known-bad at build time. The runtime walker in
            // NodeStreamingChatHelper re-checks pool membership per request, so a provider
            // that re-enters the pool later still gets used (the graph is rebuilt on
            // ModelConfigChangedEvent).
            if (providerPool != null && !providerPool.contains(p.getProviderId())) {
                log.debug("[LlmFailover] skipping provider {} — not in available pool",
                        p.getProviderId());
                continue;
            }
            ModelConfigEntity fallbackConfig = pickFallbackModel(p.getProviderId());
            if (fallbackConfig == null) {
                log.debug("[LlmFailover] skipping provider {} — no enabled chat model",
                        p.getProviderId());
                continue;
            }
            if (primaryModelName != null && primaryModelName.equals(fallbackConfig.getModelName())) {
                // Same model name picked for a different provider — exact same call, skip.
                continue;
            }
            try {
                ChatModel m = buildRuntimeChatModel(fallbackConfig, RetryTemplate.builder().maxAttempts(1).build());
                chain.add(new vip.mate.llm.failover.FallbackEntry(p.getProviderId(), m));
                log.info("[LlmFailover] chain[{}] = {}/{} (priority={})",
                        chain.size(), p.getProviderId(), fallbackConfig.getModelName(),
                        p.getFallbackPriority());
            } catch (Exception e) {
                log.warn("[LlmFailover] skipping provider {} — chat model build failed: {}",
                        p.getProviderId(), e.getMessage());
            }
        }
        return chain;
    }

    /**
     * Pick a chat model to use as a fallback for the given provider:
     * <ol>
     *   <li>Provider's explicit default ({@code is_default=true}) — most user-aligned.</li>
     *   <li>First enabled chat model on the provider — pragmatic fallback so the user
     *       isn't required to mark a default per provider just to participate in failover.</li>
     * </ol>
     * Returns {@code null} when the provider has no usable chat model.
     */
    private ModelConfigEntity pickFallbackModel(String providerId) {
        try {
            ModelConfigEntity defaultModel = modelConfigService.getDefaultModelByProvider(providerId);
            if (defaultModel != null) return defaultModel;
        } catch (Exception ignored) {
            // No default — fall through to first-enabled lookup.
        }
        try {
            return modelConfigService.listModelsByProvider(providerId).stream()
                    .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                    .filter(m -> m.getModelType() == null || "chat".equals(m.getModelType()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[LlmFailover] cannot list models for provider {}: {}", providerId, e.getMessage());
            return null;
        }
    }

    /**
     * Reorder a provider list by an agent's preference list. Listed provider
     * ids come first in their preference order; any provider not in the
     * preference list keeps its original position relative to other unlisted
     * providers (stable partition). Preference entries that don't match any
     * actual provider are silently dropped.
     */
    /** Package-private for unit testing — see {@code AgentGraphBuilderPreferenceTest}. */
    static List<ModelProviderEntity> reorderByPreferences(List<ModelProviderEntity> providers,
                                                          List<String> preferredOrder) {
        Map<String, ModelProviderEntity> byId = new java.util.LinkedHashMap<>();
        for (ModelProviderEntity p : providers) {
            byId.put(p.getProviderId(), p);
        }
        List<ModelProviderEntity> reordered = new ArrayList<>(providers.size());
        Set<String> placed = new java.util.HashSet<>();
        for (String prefId : preferredOrder) {
            ModelProviderEntity p = byId.get(prefId);
            if (p != null && placed.add(prefId)) {
                reordered.add(p);
            }
        }
        for (ModelProviderEntity p : providers) {
            if (placed.add(p.getProviderId())) {
                reordered.add(p);
            }
        }
        return reordered;
    }

    /**
     * Finds the first enabled chat model whose provider is fully configured.
     * Used as a fallback when the default model's provider is not available.
     */
    private ModelConfigEntity findFirstAvailableChatModel() {
        return modelConfigService.listByType("chat").stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .filter(m -> {
                    try {
                        return modelProviderService.isProviderConfigured(m.getProvider());
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(null);
    }

    // PR-0b: legacy single-fallback buildFallbackModel deleted (already @Deprecated, no callers).
    // PR-0b: isDashScopeSearchEnabled moved to AgentDashScopeChatModelBuilder.

    // ==================== Prompt 构建 ====================

    private String buildEnhancedPrompt(AgentEntity entity, boolean builtinSearchEnabled,
                                       Set<String> boundTools, Integer maxInputTokens) {
        // The agent's own systemPrompt encodes its identity (role / goal /
        // backstory). The memory block from workspace files (AGENTS.md, SOUL.md,
        // PROFILE.md, MEMORY.md, ...) augments that identity with durable
        // context. Both are independently optional, but when both exist they
        // must be joined — earlier this branch picked memory and silently
        // dropped the identity prompt, so editor-side identity changes never
        // reached runtime if the agent had any workspace files.
        String identityPrompt = entity.getSystemPrompt() != null ? entity.getSystemPrompt().trim() : "";
        String memoryPrompt = memoryManager.buildSystemPromptBlock(entity.getId());
        StringBuilder basePromptBuilder = new StringBuilder();
        if (!identityPrompt.isEmpty()) {
            basePromptBuilder.append(identityPrompt);
        }
        if (memoryPrompt != null && !memoryPrompt.isBlank()) {
            if (basePromptBuilder.length() > 0) {
                basePromptBuilder.append("\n\n");
            }
            basePromptBuilder.append(memoryPrompt);
        }
        String basePrompt = basePromptBuilder.toString();

        // 使用 skill runtime 构建技能增强（per-agent 绑定过滤 + 工作区隔离）
        Set<Long> boundSkillIds = agentBindingService.getBoundSkillIds(entity.getId());
        String skillEnhancement = skillRuntimeService.buildSkillPromptEnhancement(
                boundSkillIds, boundTools, maxInputTokens, entity.getId(), entity.getWorkspaceId());

        // 工具调用指导
        String toolGuidance = """

                ## Runtime Context
                - Current Agent ID: %s

                ## Workspace Memory Guidelines
                Your durable memory is stored in database-backed workspace markdown files for this agent:
                - `PROFILE.md`: stable user profile, preferences, collaboration style
                - `MEMORY.md`: distilled long-term memory, durable facts, lessons, recurring patterns
                - `memory/YYYY-MM-DD.md`: daily notes, raw events, temporary observations, open loops

                Use workspace memory tools instead of local filesystem tools for those files:
                - `list_workspace_memory_files(agentId=..., filenamePrefix=...)`
                - `read_workspace_memory_file(agentId=..., filename=...)`
                - `write_workspace_memory_file(agentId=..., filename=..., content=...)`
                - `edit_workspace_memory_file(agentId=..., filename=..., oldText=..., newText=...)`

                Memory writing policy:
                - Stable user preference, identity, collaboration habit -> `PROFILE.md`
                - Stable project fact, workflow, tool setup, lesson learned, recurring decision -> `MEMORY.md`
                - One-off event, meeting note, temporary context, today's decision trace -> `memory/YYYY-MM-DD.md`
                - Read before write unless you are creating a brand new daily note
                - Do not store secrets or highly sensitive data unless the user explicitly asks
                - Updating workspace memory files is internal state maintenance for this agent and can be done proactively when useful

                Memory emergence policy:
                - If the same preference, constraint, workflow, or lesson appears repeatedly, consolidate it from daily notes into `MEMORY.md`
                - Prefer updating an existing section over appending duplicate bullets
                - Treat `MEMORY.md` as a compact mental model, not a raw transcript dump
                - When answering tasks involving prior decisions, preferences, habits, or ongoing work, proactively consult relevant workspace memory first

                ## Structured Memory Tools
                For discrete, typed facts use structured memory tools (separate from workspace files):
                - `remember_structured(agentId, type, key, content)` — store a typed entry
                - `recall_structured(agentId, type, keyword)` — search entries by type and/or keyword
                - `forget_structured(agentId, type, key)` — remove an entry

                Types:
                - `user`: preferences, expertise, communication style, role
                - `feedback`: behavioral corrections or confirmed approaches (include WHY)
                - `project`: decisions, deadlines, constraints not derivable from code/git
                - `reference`: pointers to external systems (Linear boards, Grafana dashboards, Slack channels)

                Use workspace memory tools (MEMORY.md, daily notes) for long-form narrative notes.
                Use structured memory tools for key-value facts the system can query efficiently.

                ## Session Search
                - `session_search(agentId, currentConversationId, mode, query, limit)` — search conversation history
                - mode="recent": list recent conversations (titles, times, message counts)
                - mode="search": keyword full-text search across past messages
                - Use this to recall previous discussions, look up past decisions, or find context from earlier conversations

                ## Tool Usage Guidelines
                When you have available tools, use them to access local system information, files, or execute commands.
                Do not assume you cannot access local resources - try calling the appropriate tool first.
                If a tool requires approval due to security policies, the system will prompt the user for confirmation.
                Only state you cannot access something if no relevant tool is available.
                Do not claim a tool-generated file, URL, UUID, path, task id, or success result before the corresponding tool call has completed. If a tool is needed, call the tool first, then report only the actual returned result.

                ## Multi-Part Question Guidelines
                When the user asks multiple questions or requests multiple tasks in a single message:
                1. Structure your final answer with numbered sections, one per sub-task
                2. Each section must contain the complete, detailed result for that sub-task
                3. Never compress earlier sub-tasks into summary sentences while expanding the last one
                4. If observations were summarized during processing, reconstruct each section from the summary
                5. Treat each sub-task's result as equally important regardless of processing order

                ## File Reading Guidelines

                **Text Files** (use read_file):
                For .txt, .md, .json, .yaml, .csv, .log, .py, .java, .js, .html, .xml, .sql, .conf, .ini, .toml files.

                **Office/PDF Documents** (DO NOT use read_file):
                For .pdf, .docx, .doc, .xlsx, .xls, .pptx, .ppt files, NEVER use read_file.
                Instead use:
                - detect_file_type(filePath="...") - to check file type first
                - extract_document_text(filePath="...") - general document extraction
                - extract_pdf_text(filePath="...") - for PDF files
                - extract_docx_text(filePath="...") - for Word documents

                Example workflow for document:
                1. detect_file_type(filePath="/path/to/document.pdf")
                2. Based on result, use extract_pdf_text() or extract_document_text()
                3. Process the extracted text content

                If you try to read a PDF/Office file with read_file, you will get binary garbage or an error.
                """.formatted(entity.getId());

        // Web-search vs browser_use priority guidance — emitted unconditionally so the rule
        // also reaches OpenAI-compatible / Anthropic / Gemini / DeepSeek / Ollama agents that
        // do not have builtin search. Issue #40: without this rule the model treats
        // browser_use as a search tool and gets stuck in a Playwright launch loop on Windows.
        String searchGuidance = """

                ## Web Search Capability

                ### Tool Priority
                - For plain web search or fetching public page content, call the `search` tool. It supports advanced parameters: `freshness` (day/week/month/year), `language` (zh-CN/en), `count` (1-10).
                - Call `browser_use` ONLY when you need to interact with a page (click, fill forms, screenshot, run JS, follow a logged-in flow). Do NOT use `browser_use` as a search alternative.
                - **NEVER** call both `browser_use` and `search` for the same query.
                - When searching for news, use the standard format: `📰 [Category] Title — Source | Time + Summary`, up to 5 results per category.
                """;
        if (builtinSearchEnabled) {
            searchGuidance += """

                ### Built-in Search (preferred when available)
                Your responses automatically incorporate live web search results from the model provider. For most queries, answer directly — your reply already includes real-time search data. Do NOT say you cannot search.
                Use the `search` tool ONLY when you need precise time filtering (e.g., "yesterday's news" → freshness=day), a specific language, or when built-in results feel insufficient.
                """;
        }

        // Wiki 知识库上下文注入
        String wikiContext = wikiContextService.buildWikiContext(entity.getId());

        return basePrompt + skillEnhancement + toolGuidance + searchGuidance + wikiContext;
    }

    // ==================== 模型选项构建 ====================

    // PR-0b: buildDashScopeOptions moved to AgentDashScopeChatModelBuilder

    /** Transitional public visibility for {@code chatmodel} sub-package builders; will move into the builder in PR-0c (OpenAI). */
    public OpenAiChatOptions buildOpenAiOptions(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        String modelName = runtimeModel.getModelName();
        ModelFamily family = ModelFamily.detect(modelName);

        if (StringUtils.hasText(modelName)) {
            builder.model(modelName);
        }

        // temperature：部分模型族强制 1.0
        Double temperature = resolveOpenAiTemperature(modelName, runtimeModel.getTemperature(), kwargs, family);
        if (temperature != null) {
            builder.temperature(temperature);
        }

        // max_tokens / max_completion_tokens：按模型族路由
        if (family.suppressMaxTokens()) {
            // OPENAI_REASONING 族：禁止 max_tokens，改用 max_completion_tokens
            // fallback 优先级：kwargs.maxCompletionTokens > kwargs.maxTokens > config.maxTokens
            Integer kwargsMaxTokens = resolveIntegerOption("maxTokens", runtimeModel.getMaxTokens(), kwargs);
            Integer maxCompletionTokens = resolveIntegerOption("maxCompletionTokens", kwargsMaxTokens, kwargs);
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
            log.debug("ModelFamily {} suppressed max_tokens, using max_completion_tokens={} for model {}",
                    family, maxCompletionTokens, modelName);
        } else {
            // 其他模型族：正常使用 max_tokens
            Integer maxTokens = resolveIntegerOption("maxTokens", runtimeModel.getMaxTokens(), kwargs);
            if (maxTokens != null) {
                builder.maxTokens(maxTokens);
            }
            // 仍允许通过 generateKwargs 手动指定 maxCompletionTokens
            Integer maxCompletionTokens = resolveIntegerOption("maxCompletionTokens", null, kwargs);
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
        }

        // top_p：部分模型族禁止发送
        Double topP = resolveOpenAiTopP(modelName, runtimeModel.getTopP(), kwargs, family);
        if (topP != null) {
            builder.topP(topP);
        }

        // reasoning_effort：仅支持的模型族才注入
        String reasoningEffort = resolveReasoningEffort(modelName, kwargs, family);
        if (StringUtils.hasText(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort);
        }

        // 内置搜索：模型级字段优先，provider generateKwargs 作为 fallback
        boolean searchEnabled = Boolean.TRUE.equals(runtimeModel.getEnableSearch())
                || Boolean.TRUE.equals(kwargs.get("enableSearch"));
        if (searchEnabled) {
            String strategy = runtimeModel.getSearchStrategy();
            if (!StringUtils.hasText(strategy)) {
                strategy = (String) kwargs.get("searchStrategy");
            }
            OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize contextSize;
            try {
                contextSize = StringUtils.hasText(strategy)
                        ? OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.valueOf(strategy.toUpperCase())
                        : OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.MEDIUM;
            } catch (IllegalArgumentException e) {
                contextSize = OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.MEDIUM;
            }
            builder.webSearchOptions(new OpenAiApi.ChatCompletionRequest.WebSearchOptions(contextSize, null));
        }

        OpenAiChatOptions options = builder.build();
        options.setInternalToolExecutionEnabled(false);
        // 注意：不设置 parallelToolCalls — 设为 false 会导致无 tools 时 OpenAI 返回 400：
        // "parallel_tool_calls is only allowed when 'tools' are specified"
        // 保持 null 让 Spring AI 不序列化该字段，由各 Node 在有 tools 时自行控制。
        options.setStreamUsage(true);
        return options;
    }

    // ==================== OpenAI API 构建 ====================

    /** Transitional public visibility for {@code chatmodel} sub-package builders; will move into the builder in PR-0b. */
    public OpenAiApi buildOpenAiApi(ModelProviderEntity provider) {
        return buildOpenAiApi(provider, null);
    }

    /**
     * Overload that accepts a per-model read-timeout override (seconds).
     * Threaded into both the sync RestClient and streaming WebClient so
     * timeout behavior is consistent across blocking and streaming chat
     * completions. Null falls back to the default 180s.
     */
    public OpenAiApi buildOpenAiApi(ModelProviderEntity provider, Integer readTimeoutOverride) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.provider_not_configured", "Provider 未完成配置，请在模型设置中填写有效的 API Key 和 Base URL");
        }
        String apiKey = provider.getApiKey();
        // Honor the provider's requireApiKey flag instead of hard-failing on every empty key.
        // Local + key-free providers (Ollama, LM Studio, MLX, llama.cpp, OpenCode) declare
        // requireApiKey=false; for them an empty / placeholder key means "no Authorization
        // header" — Spring AI's NoopApiKey expresses that. Without this the chat path
        // rejected providers that probe / discovery / connection-test all considered usable.
        boolean keyRequired = !Boolean.FALSE.equals(provider.getRequireApiKey());
        if (keyRequired && !modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.provider_apikey_invalid", "Provider API Key 未配置或无效: " + provider.getProviderId());
        }
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.agent.provider_baseurl_missing", "Provider Base URL 未配置: " + provider.getProviderId());
        }
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        MultiValueMap<String, String> headers = buildOpenAiHeaders(kwargs);
        String completionsPath = resolveOpenAiCompletionsPath(baseUrl, kwargs);
        RestClient.Builder restClientBuilder = applyHttpTimeouts(
                restClientBuilderProvider.getIfAvailable(RestClient::builder), readTimeoutOverride);
        WebClient.Builder webClientBuilder = applyHttpTimeoutsToWebClient(
                webClientBuilderProvider.getIfAvailable(WebClient::builder), readTimeoutOverride);

        // Spring AI OpenAiApi 构造函数会先 set User-Agent 为 "spring-ai"，再 addAll 我们的 headers，
        // 导致自定义 User-Agent 被追加而非覆盖。因此对需要伪装客户端身份的 provider（如 kimi-code），
        // 通过 RestClient/WebClient 拦截器在请求发出前强制覆盖 headers。
        Map<String, String> overrideHeaders = extractOverrideHeaders(kwargs);
        if (!overrideHeaders.isEmpty()) {
            restClientBuilder = restClientBuilder.requestInterceptor((request, body, execution) -> {
                HttpHeaders reqHeaders = request.getHeaders();
                overrideHeaders.forEach(reqHeaders::set);
                return execution.execute(request, body);
            });
            webClientBuilder = webClientBuilder.filter((request, next) -> {
                org.springframework.web.reactive.function.client.ClientRequest modified =
                        org.springframework.web.reactive.function.client.ClientRequest.from(request)
                                .headers(h -> overrideHeaders.forEach(h::set))
                                .build();
                return next.exchange(modified);
            });
        }

        boolean kimiSearchEnabled = isKimiProvider(provider)
                && Boolean.TRUE.equals(kwargs.get("enableSearch"));

        ApiKey apiKeyImpl = (keyRequired && StringUtils.hasText(apiKey))
                ? new SimpleApiKey(apiKey.trim())
                : new NoopApiKey();
        return new OpenAiApi(
                baseUrl,
                apiKeyImpl,
                headers,
                completionsPath,
                "/v1/embeddings",
                restClientBuilder,
                webClientBuilder,
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER) {
            @Override
            public org.springframework.http.ResponseEntity<OpenAiApi.ChatCompletion> chatCompletionEntity(
                    OpenAiApi.ChatCompletionRequest chatRequest,
                    MultiValueMap<String, String> additionalHttpHeader) {
                chatRequest = sanitizeReasoningEffortForProvider(chatRequest, provider);
                chatRequest = patchReasoningContent(chatRequest, provider);
                chatRequest = stripReasoningEffortIfIncompatible(chatRequest);
                chatRequest = stripAutoToolChoice(chatRequest);
                chatRequest = patchVideoMediaContent(chatRequest);
                if (kimiSearchEnabled) {
                    chatRequest = injectKimiWebSearch(chatRequest);
                }
                logOpenAiRequest(provider, chatRequest);
                try {
                    return super.chatCompletionEntity(chatRequest, additionalHttpHeader);
                } catch (WebClientResponseException e) {
                    logOpenAiError(provider, e);
                    throw e;
                }
            }

            @Override
            public Flux<OpenAiApi.ChatCompletionChunk> chatCompletionStream(
                    OpenAiApi.ChatCompletionRequest chatRequest,
                    MultiValueMap<String, String> additionalHttpHeader) {
                chatRequest = sanitizeReasoningEffortForProvider(chatRequest, provider);
                chatRequest = patchReasoningContent(chatRequest, provider);
                chatRequest = stripReasoningEffortIfIncompatible(chatRequest);
                chatRequest = stripAutoToolChoice(chatRequest);
                chatRequest = patchVideoMediaContent(chatRequest);
                if (kimiSearchEnabled) {
                    chatRequest = injectKimiWebSearch(chatRequest);
                }
                logOpenAiRequest(provider, chatRequest);
                return super.chatCompletionStream(chatRequest, additionalHttpHeader)
                        .doOnError(error -> {
                            if (error instanceof WebClientResponseException e) {
                                logOpenAiError(provider, e);
                            }
                        });
            }
        };
    }

    // ==================== DashScope API 构建 ====================

    // PR-0b: buildDashScopeApi moved to AgentDashScopeChatModelBuilder

    // ==================== Anthropic API 构建 ====================

    // PR-0b: buildAnthropicApi + buildAnthropicOptions moved to AgentAnthropicChatModelBuilder

    // ==================== 参数解析辅助方法 ====================

    private Double resolveOpenAiTemperature(String modelName, Double configuredTemperature,
                                               Map<String, Object> kwargs, ModelFamily family) {
        Double overriddenTemperature = resolveDoubleOption("temperature", configuredTemperature, kwargs);
        if (family.fixedTemperatureOne()) {
            if (overriddenTemperature == null || Double.compare(overriddenTemperature, 1.0d) != 0) {
                log.info("ModelFamily {} forced temperature=1.0 for model {}", family, modelName);
            }
            return 1.0d;
        }
        return overriddenTemperature;
    }

    private Double resolveOpenAiTopP(String modelName, Double configuredTopP,
                                     Map<String, Object> kwargs, ModelFamily family) {
        if (family.suppressTopP()) {
            return null;
        }
        return resolveDoubleOption("topP", configuredTopP, kwargs);
    }

    private boolean requiresFixedTemperatureOne(String modelName) {
        return ModelFamily.detect(modelName).fixedTemperatureOne();
    }

    private String resolveReasoningEffort(String modelName, Map<String, Object> kwargs, ModelFamily family) {
        // PR-1.1 (RFC-049 L1-A): Only families that actually accept reasoning_effort may receive
        // it. Previously only the default-inject branch checked capability; the generateKwargs
        // override branch did not, so a provider-level `reasoningEffort: "high"` would leak to
        // deepseek-chat / kimi-k2 / deepseek-reasoner etc., triggering the incident documented
        // in RFC-049 (DeepSeek "reasoning_content missing" 400).
        if (!family.supportsReasoningEffort()) {
            Object overridden = findOptionValue(kwargs, "reasoningEffort");
            if (overridden != null) {
                log.warn("Dropping reasoningEffort='{}' from generateKwargs — model '{}' (family={}) "
                                + "does not accept reasoning_effort. For DeepSeek thinking use "
                                + "extra_body.thinking; for Kimi thinking the model activates it natively.",
                        overridden, modelName, family);
            }
            return null;
        }
        // generateKwargs 显式覆盖始终优先（仅在白名单族内）
        Object value = findOptionValue(kwargs, "reasoningEffort");
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        // 仅支持 reasoning_effort 的模型族才自动注入默认值
        if (family.isThinking()) {
            return "medium";
        }
        return null;
    }

    private boolean isThinkingModel(String modelName) {
        return ModelFamily.detect(modelName).isThinking();
    }

    /**
     * 从 ModelConfigEntity 中解析 reasoningEffort，用于传递给 StepExecutionNode / ReasoningNode。
     * 复用已有的 resolveReasoningEffort + isThinkingModel 逻辑。
     */
    private String resolveReasoningEffortForModel(ModelConfigEntity runtimeModel) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(runtimeModel.getProvider());
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        ModelFamily family = ModelFamily.detect(runtimeModel.getModelName());
        return resolveReasoningEffort(runtimeModel.getModelName(), kwargs, family);
    }

    private Double resolveDoubleOption(String key, Double fallback, Map<String, Object> kwargs) {
        Object value = findOptionValue(kwargs, key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Invalid double generateKwargs value for {}: {}", key, text);
            }
        }
        return fallback;
    }

    private Integer resolveIntegerOption(String key, Integer fallback, Map<String, Object> kwargs) {
        Object value = findOptionValue(kwargs, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Invalid integer generateKwargs value for {}: {}", key, text);
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Object findOptionValue(Map<String, Object> kwargs, String key) {
        Object direct = findKwarg(kwargs, key);
        if (direct != null) {
            return direct;
        }
        String snakeCase = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        if (!snakeCase.equals(key)) {
            return findKwarg(kwargs, snakeCase);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object findKwarg(Map<String, Object> kwargs, String key) {
        if (kwargs == null || kwargs.isEmpty()) {
            return null;
        }
        if (kwargs.containsKey(key)) {
            return kwargs.get(key);
        }
        Object chatOptions = kwargs.get("chatOptions");
        if (chatOptions instanceof Map<?, ?> optionsMap) {
            return ((Map<String, Object>) optionsMap).get(key);
        }
        return null;
    }

    // ==================== URL 规范化 ====================

    // PR-0b: normalizeDashScopeBaseUrl moved to AgentDashScopeChatModelBuilder

    private String normalizeOpenAiBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    // ==================== Kimi 内置搜索 ====================

    private static boolean isKimiProvider(ModelProviderEntity provider) {
        if (provider == null) return false;
        String id = provider.getProviderId();
        return "kimi-cn".equals(id) || "kimi-intl".equals(id);
    }

    /**
     * 为 Kimi 请求注入 $web_search builtin tool。
     * Kimi 的内置搜索通过 tools 数组中声明 {"type":"builtin_function","function":{"name":"$web_search"}} 实现。
     * 由于 Spring AI 的 FunctionTool.Type 只有 FUNCTION，无法直接构造 builtin_function 类型，
     * 因此通过 extraBody 注入原始 JSON 结构覆盖 tools 字段（包含原有 tools + $web_search）。
     */
    private static OpenAiApi.ChatCompletionRequest injectKimiWebSearch(OpenAiApi.ChatCompletionRequest request) {
        // 构造 $web_search entry 作为 Map
        Map<String, Object> webSearchTool = Map.of(
                "type", "builtin_function",
                "function", Map.of("name", "$web_search")
        );

        // 将原有 tools 转为 List<Map> 并追加 $web_search
        List<Map<String, Object>> allTools = new ArrayList<>();
        if (request.tools() != null) {
            for (OpenAiApi.FunctionTool tool : request.tools()) {
                Map<String, Object> toolMap = new LinkedHashMap<>();
                toolMap.put("type", "function");
                if (tool.getFunction() != null) {
                    Map<String, Object> funcMap = new LinkedHashMap<>();
                    funcMap.put("name", tool.getFunction().getName());
                    if (tool.getFunction().getDescription() != null) {
                        funcMap.put("description", tool.getFunction().getDescription());
                    }
                    if (tool.getFunction().getParameters() != null) {
                        funcMap.put("parameters", tool.getFunction().getParameters());
                    }
                    if (tool.getFunction().getStrict() != null) {
                        funcMap.put("strict", tool.getFunction().getStrict());
                    }
                    toolMap.put("function", funcMap);
                }
                allTools.add(toolMap);
            }
        }
        allTools.add(webSearchTool);

        // 通过 extraBody 注入 tools（覆盖原有 tools 字段），同时清空原 tools 避免重复序列化
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (request.extraBody() != null) {
            extraBody.putAll(request.extraBody());
        }
        extraBody.put("tools", allTools);

        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                null,  // tools — 清空，由 extraBody 接管
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                extraBody
        );
    }

    // PR-0b: reflection helpers (readApiKey/BaseUrl/DashScopeApiFromDefaultChatModel)
    //         moved to AgentDashScopeChatModelBuilder

    // ==================== 日志辅助 ====================

    private MultiValueMap<String, String> buildOpenAiHeaders(Map<String, Object> kwargs) {
        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("User-Agent", "MateClaw/1.0");
        Object headerObject = kwargs.get("headers");
        if (headerObject instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    headers.set(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return headers;
    }

    /**
     * RFC-012 M1：给 LLM 调用走的 RestClient 显式配置超时，避免 socket 永久挂起等待。
     * <p>
     * 使用 {@link JdkClientHttpRequestFactory}（基于 Java 11+ {@link HttpClient}），原因：
     * <ul>
     *   <li>原生支持 HTTP/2 / ALPN 协商（Kimi 等现代 LLM provider 默认 HTTP/2）</li>
     *   <li>自动处理 {@code Content-Encoding: gzip} 解压（{@code SimpleClientHttpRequestFactory}
     *       基于旧的 {@code HttpURLConnection}，不会自动解压，会把 gzip 流误标为
     *       {@code application/octet-stream} 导致 RestClient 抛 "Error extracting response"）</li>
     *   <li>对 chunked transfer + 非标准 content-type 的回退处理符合现代 spec</li>
     * </ul>
     * <p>
     * connectTimeout=10s（任何 LLM 提供方都不该超过这个建立连接时间）；
     * readTimeout=180s（覆盖 nginx 60s 网关超时 + 留足真实长响应余量；超时后由上层 retry 接管）。
     */
    private RestClient.Builder applyHttpTimeouts(RestClient.Builder builder) {
        return applyHttpTimeouts(builder, null);
    }

    /**
     * Overload that accepts a per-model read-timeout override (seconds).
     * Null falls back to the default 180s.
     */
    private RestClient.Builder applyHttpTimeouts(RestClient.Builder builder, Integer readTimeoutOverride) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(vip.mate.llm.chatmodel.HttpTimeouts.CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(vip.mate.llm.chatmodel.HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.requestFactory(rf);
    }

    /**
     * Apply equivalent timeouts to the WebClient that backs OpenAI-compatible
     * STREAMING calls (chat completions with {@code stream:true}). The
     * RestClient version above only protects synchronous HTTP — without this,
     * the streaming code path uses the default {@code WebClient} which has
     * neither connect nor read timeout, so a stalled provider can hang the
     * call forever (observed: a single volcengine-plan request held the agent
     * thread for 9+ minutes with no error, until the user manually pressed
     * Stop). That kept the failover chain idle because nothing threw.
     * <p>
     * Uses {@link JdkClientHttpConnector} with the same {@link HttpClient} we
     * already use for the RestClient so the dependency surface stays clean
     * (reactor-netty is not on this project's classpath — Spring's webflux
     * starter is excluded by design).
     */
    private WebClient.Builder applyHttpTimeoutsToWebClient(WebClient.Builder builder) {
        return applyHttpTimeoutsToWebClient(builder, null);
    }

    /**
     * Overload with the same per-model override semantics as
     * {@link #applyHttpTimeouts(RestClient.Builder, Integer)}.
     */
    private WebClient.Builder applyHttpTimeoutsToWebClient(WebClient.Builder builder, Integer readTimeoutOverride) {
        // Pin HTTP/1.1: many self-hosted OpenAI-compatible servers (vLLM, lmstudio,
        // llama.cpp, ollama — all uvicorn/ASGI based) only speak HTTP/1.1 over
        // cleartext and slam the socket on the JDK client's default H2C upgrade
        // probe, surfacing as "header parser received no bytes" with no body sent.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(vip.mate.llm.chatmodel.HttpTimeouts.CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        org.springframework.http.client.reactive.JdkClientHttpConnector connector =
                new org.springframework.http.client.reactive.JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(vip.mate.llm.chatmodel.HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.clientConnector(connector);
    }

    /**
     * 从 generateKwargs.headers 中提取需要强制覆盖的 headers。
     * 用于通过 RestClient/WebClient 拦截器绕过 Spring AI OpenAiApi 的默认 User-Agent。
     */
    private Map<String, String> extractOverrideHeaders(Map<String, Object> kwargs) {
        Map<String, String> result = new java.util.HashMap<>();
        Object headerObject = kwargs.get("headers");
        if (headerObject instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    result.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return result;
    }

    // Trailing "/v{digits}" segment in a base URL — the OpenAI-compatible convention
    // (/v1 OpenAI, /v3 Volcano Ark, /v4 Zhipu). When the baseUrl already carries this
    // segment, the default /v1 prefix on the path must be stripped to avoid building
    // a broken URL like /api/v3/v1/chat/completions.
    private static final java.util.regex.Pattern OPENAI_BASE_URL_VERSION_SUFFIX =
            java.util.regex.Pattern.compile(".*/v\\d+$");

    private String resolveOpenAiCompletionsPath(String baseUrl, Map<String, Object> kwargs) {
        Object raw = kwargs.get("completionsPath");
        boolean explicit = raw instanceof String value && StringUtils.hasText(value);
        String path = explicit ? ((String) raw).trim() : "/v1/chat/completions";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // An explicit completionsPath is honored as-is. Otherwise, dedupe the /v1
        // prefix when the baseUrl already ends with /v{N} (Volcano Engine Ark /v3,
        // Zhipu /v4, etc.).
        if (!explicit
                && baseUrl != null
                && OPENAI_BASE_URL_VERSION_SUFFIX.matcher(baseUrl).matches()
                && path.startsWith("/v1/")) {
            path = path.substring(3);
        }
        return path;
    }

    /**
     * Consume the {@link AssistantThinkingRelay} entry and rebuild the outbound
     * {@link OpenAiApi.ChatCompletionRequest} so that assistant tool-call / thinking
     * messages carry the correct {@code reasoning_content}.
     *
     * <p>PR-2 (RFC-049 §2.3.2): This is the consumer side of the relay.
     * {@code NodeStreamingChatHelper.doStreamCall} stashes per-assistant thinking
     * keyed on a token embedded in {@code request.user()}. Here we:
     * <ol>
     *   <li>{@link AssistantThinkingRelay#take(String)} the entry and restore
     *       {@code request.user()} to {@code entry.originalUser()} (internal token
     *       never reaches the provider).</li>
     *   <li>Compute {@code lastUserIdx} (the boundary of the current user turn),
     *       symmetric to {@code stripThinkingFromPrompt}. Assistant messages at
     *       {@code i <= lastUserIdx} are prior-turn history: their
     *       {@code reasoning_content} must stay null. Only {@code i > lastUserIdx}
     *       messages are eligible for patching.</li>
     *   <li>Select a {@link FallbackPolicy} by {@code providerId}. When relay has
     *       a real value, we use it; when empty, the policy decides whether to
     *       inject {@code " "} (legacy tolerance: KIMI/OPENAI/DEFAULT) or leave
     *       {@code null} to surface an explicit provider error (DEEPSEEK).</li>
     * </ol>
     *
     * <p>The relay iterator advances for every assistant message (including
     * prior-turn ones) to stay positionally aligned with the producer's extraction
     * in {@code NodeStreamingChatHelper.extractAssistantThinkings}.
     */
    static OpenAiApi.ChatCompletionRequest patchReasoningContent(
            OpenAiApi.ChatCompletionRequest request, ModelProviderEntity provider) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        // 1. Consume relay (if any) and compute the sanitized user field.
        AssistantThinkingRelay.RelayEntry entry = AssistantThinkingRelay.take(request.user());
        String sanitizedUser = (entry != null)
                ? entry.originalUser()
                : (AssistantThinkingRelay.isToken(request.user()) ? null : request.user());

        // 2. Detect thinking mode — unchanged from the prior design except that relay
        //    presence is also a trigger.
        boolean thinkingMode = request.reasoningEffort() != null
                || requiresReasoningContentPatch(request.model())
                || request.messages().stream().anyMatch(m ->
                        m.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
                                && m.reasoningContent() != null)
                || entry != null;
        if (!thinkingMode) {
            // Nothing to patch but we may still need to strip a leaked relay token from user.
            return request.user() != null && !request.user().equals(sanitizedUser)
                    ? rebuildWithUser(request, sanitizedUser)
                    : request;
        }

        // 3. Find lastUserIdx so we can skip cross-turn assistants.
        int lastUserIdx = -1;
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            if (request.messages().get(i).role() == OpenAiApi.ChatCompletionMessage.Role.USER) {
                lastUserIdx = i;
                break;
            }
        }

        FallbackPolicy policy = FallbackPolicy.forProvider(provider);
        java.util.Iterator<String> it = (entry != null)
                ? entry.thinkings().iterator()
                : java.util.Collections.emptyIterator();

        // 4. Walk messages, patching only in-turn assistants; always advance iterator
        //    for all assistants so producer/consumer positions stay aligned.
        boolean anyPatched = false;
        List<OpenAiApi.ChatCompletionMessage> patched = new ArrayList<>(request.messages().size());
        for (int i = 0; i < request.messages().size(); i++) {
            OpenAiApi.ChatCompletionMessage msg = request.messages().get(i);
            if (msg.role() != OpenAiApi.ChatCompletionMessage.Role.ASSISTANT) {
                patched.add(msg);
                continue;
            }

            String next = it.hasNext() ? it.next() : null;

            // Already has a real value: leave alone
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                patched.add(msg);
                continue;
            }

            // Cross-turn assistant: usually skip per stripThinkingFromPrompt's
            // "thinking resets across user turns" rule. But DeepSeek (since
            // 2026-04) requires reasoning_content even on prior-turn assistants
            // and rejects requests where any prior assistant has it null. For
            // policies with patchCrossTurn=true, fall through and patch with
            // the empty fallback (" ") so multi-turn conversations don't 400
            // before sanitizeForLlm has a chance to filter the previous error.
            if (i <= lastUserIdx && !policy.patchCrossTurn) {
                patched.add(msg);
                continue;
            }

            boolean hasToolCalls = msg.toolCalls() != null && !msg.toolCalls().isEmpty();
            if (!hasToolCalls && !policy.patchNonToolCall) {
                patched.add(msg);
                continue;
            }

            String injected;
            if (next != null && !next.isEmpty()) {
                injected = next;
            } else {
                injected = policy.emptyFallback;
                if (injected == null && policy.warnOnMissingReal) {
                    log.warn("[patchReasoningContent] provider={} requires real reasoning_content "
                                    + "but relay has no value for assistant message at index {}; "
                                    + "leaving null so provider returns explicit error.",
                            providerIdOrUnknown(provider), i);
                }
            }
            if (injected == null && msg.reasoningContent() == null) {
                // No change — keep original
                patched.add(msg);
                continue;
            }
            patched.add(new OpenAiApi.ChatCompletionMessage(
                    msg.rawContent(), msg.role(), msg.name(), msg.toolCallId(),
                    msg.toolCalls(), msg.refusal(), msg.audioOutput(),
                    msg.annotations(), injected));
            anyPatched = true;
        }

        boolean userChanged = request.user() != null && !request.user().equals(sanitizedUser)
                || (request.user() == null && sanitizedUser != null);
        if (!anyPatched && !userChanged) {
            return request;
        }

        // 5. Rebuild with patched messages + sanitized user.
        return new OpenAiApi.ChatCompletionRequest(
                patched,
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                sanitizedUser,
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * PR-2 (RFC-049 §2.3.2): Provider-keyed policy for how {@code patchReasoningContent}
     * should behave when the relay has no real thinking for an in-turn assistant message.
     *
     * <ul>
     *   <li>{@code emptyFallback}: value to inject when relay has no real value —
     *       {@code null} means leave {@code reasoning_content} null (DeepSeek);
     *       {@code " "} preserves Spring AI 1.1.4 legacy tolerance (Kimi/OpenAI/unknown).</li>
     *   <li>{@code warnOnMissingReal}: emit WARN when {@code emptyFallback==null} fires —
     *       only DeepSeek wants this, because there a missing value means we have a bug.</li>
     *   <li>{@code patchNonToolCall}: whether to patch assistant messages without tool_calls —
     *       DeepSeek's contract applies to all in-turn assistant messages, others only
     *       to tool_call messages (historical behavior).</li>
     * </ul>
     *
     * {@code DEFAULT} intentionally keeps the legacy {@code " "} tolerance rather than
     * going no-op: an unrecognized provider (self-hosted DeepSeek-like backend, custom
     * OpenAI-compatible gateway) might still require the patch — noop would regress
     * those into new 400s.
     */
    private enum FallbackPolicy {
        // RFC-049 follow-up (2026-04-27): DEEPSEEK previously used (null, true, true)
        // to "surface explicit provider error" when the producer-side relay had no
        // captured reasoning_content. In practice this kept failing every multi-tool
        // turn that crossed a summarizing boundary — the summarizer-produced
        // assistant message has no reasoning_content by construction, the relay
        // iterator has no entry for it, and DeepSeek returns 400 inside the same
        // turn (not just multi-turn replay), aborting the whole graph at the
        // reasoning step right after summarizing. Switching to the same " "
        // tolerance KIMI/OPENAI use restores forward progress; the producer-side
        // capture gap remains a real bug to fix in RFC-049 PR-3 but doesn't
        // belong on the user-facing failure path.
        //
        // 2026-04-29 follow-up: DeepSeek tightened thinking-mode validation to
        // require reasoning_content on EVERY assistant message in the request,
        // including prior-turn history. We never persist reasoning_content to
        // mate_message, so any conversation with >=1 prior turn fails with
        // 400 "reasoning_content must be passed back" on the very first reasoning
        // call. patchCrossTurn=true lets us extend the " " fallback to prior-turn
        // assistants too, restoring forward progress for multi-turn IM chats.
        // Real reasoning_content recovery (RFC-049 PR-3) is the proper long-term
        // fix; this keeps users unblocked.
        DEEPSEEK(" ",  false, true,  true),
        KIMI    (" ",  false, false, false),
        OPENAI  (" ",  false, false, false),
        DEFAULT (" ",  false, false, false);

        final String emptyFallback;
        final boolean warnOnMissingReal;
        final boolean patchNonToolCall;
        /** Whether to also patch prior-turn assistants ({@code i <= lastUserIdx}). */
        final boolean patchCrossTurn;

        FallbackPolicy(String emptyFallback, boolean warnOnMissingReal,
                       boolean patchNonToolCall, boolean patchCrossTurn) {
            this.emptyFallback = emptyFallback;
            this.warnOnMissingReal = warnOnMissingReal;
            this.patchNonToolCall = patchNonToolCall;
            this.patchCrossTurn = patchCrossTurn;
        }

        static FallbackPolicy forProvider(ModelProviderEntity provider) {
            if (provider == null || provider.getProviderId() == null) {
                return DEFAULT;
            }
            String id = provider.getProviderId().toLowerCase();
            return switch (id) {
                case "deepseek" -> DEEPSEEK;
                case "kimi-cn", "kimi-intl", "kimi-code" -> KIMI;
                case "openai", "azure-openai" -> OPENAI;
                default -> DEFAULT;
            };
        }
    }

    /**
     * Rebuild a {@link OpenAiApi.ChatCompletionRequest} with only the {@code user} field
     * replaced. Used when {@code patchReasoningContent} has no assistant-message changes
     * but must strip a relay token from the outbound {@code user} field.
     */
    private static OpenAiApi.ChatCompletionRequest rebuildWithUser(
            OpenAiApi.ChatCompletionRequest request, String newUser) {
        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                newUser,
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * PR-1.3 (RFC-049 L1-C): Provider-first sanitization of {@code reasoning_effort}.
     *
     * <p>Authoritative judgement uses the target {@code provider.getProviderId()} as a
     * whitelist (default-deny). Only OpenAI official providers are allowed to carry
     * {@code reasoning_effort}; everything else — known non-supporters (DeepSeek / Kimi /
     * DashScope / Ollama / …) and any unrecognized providerId (self-hosted gateways,
     * OpenRouter / Together / aggregators) — is stripped unconditionally.
     *
     * <p>The reason we intentionally distrust {@code request.model()} here: MateClaw's
     * failover chain (RFC-009) can reuse the same {@code Prompt} and {@code OpenAiChatOptions}
     * across providers, and {@code OpenAiChatOptions.model} was set to the primary's model
     * name (e.g. {@code gpt-5}). If the sanitizer only checked {@code ModelFamily.detect(
     * request.model())}, a failover hop from GPT-5 → DeepSeek would see model name
     * "gpt-5" → OPENAI_REASONING → {@code supportsReasoningEffort == true} and quietly
     * forward the primary's {@code reasoning_effort} to DeepSeek, re-triggering the
     * incident this RFC exists to fix.
     *
     * <p>Only when the provider is on the whitelist do we fall through to the
     * {@link ModelFamily} check (e.g. within OpenAI, {@code gpt-4} still wouldn't support
     * reasoning_effort). Outside the whitelist, no runtime check on model is trusted.
     *
     * <p>Adding a new provider to the whitelist must be an explicit PR with a sanitizer
     * test — do not add a catch-all default-allow branch.
     */
    static OpenAiApi.ChatCompletionRequest sanitizeReasoningEffortForProvider(
            OpenAiApi.ChatCompletionRequest request, ModelProviderEntity provider) {
        if (request == null || request.reasoningEffort() == null) {
            return request;
        }

        if (!isReasoningEffortWhitelistedProvider(provider)) {
            log.warn("[reasoning_effort sanitizer] provider={} is not on the reasoning_effort "
                            + "whitelist (only openai/azure-openai are); stripping value='{}' "
                            + "(request.model()='{}' may be leaked from failover primary).",
                    providerIdOrUnknown(provider), request.reasoningEffort(), request.model());
            return rebuildWithReasoningEffort(request, null);
        }

        ModelFamily targetFamily = ModelFamily.detect(request.model());
        if (!targetFamily.supportsReasoningEffort()) {
            log.warn("[reasoning_effort sanitizer] provider={} model={} family={} does not "
                            + "support reasoning_effort; stripping value='{}'.",
                    provider.getProviderId(), request.model(), targetFamily, request.reasoningEffort());
            return rebuildWithReasoningEffort(request, null);
        }
        return request;
    }

    /**
     * Whitelist of providers known to accept {@code reasoning_effort} on
     * {@code /v1/chat/completions} (or {@code /v1/responses}). Anything else is denied.
     * Adding a provider here must come with a corresponding sanitizer test case.
     */
    static boolean isReasoningEffortWhitelistedProvider(ModelProviderEntity provider) {
        if (provider == null || provider.getProviderId() == null) {
            return false;
        }
        String id = provider.getProviderId().toLowerCase();
        return switch (id) {
            case "openai", "azure-openai" -> true;
            default -> false;
        };
    }

    private static String providerIdOrUnknown(ModelProviderEntity p) {
        return (p == null || p.getProviderId() == null) ? "<unknown>" : p.getProviderId();
    }

    /**
     * Rebuild a {@link OpenAiApi.ChatCompletionRequest} with a new {@code reasoningEffort}
     * value (typically {@code null} to strip). Mirrors the record canonical-constructor
     * pattern used by {@link #stripReasoningEffortIfIncompatible}.
     */
    private static OpenAiApi.ChatCompletionRequest rebuildWithReasoningEffort(
            OpenAiApi.ChatCompletionRequest request, String newReasoningEffort) {
        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                newReasoningEffort,
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * GPT-5 兼容性：在 /v1/chat/completions 路径下，tools 与 reasoning_effort 不可同时存在。
     * <p>
     * 当检测到 gpt-5* 模型同时携带 tools 和 reasoning_effort 时，自动移除 reasoning_effort 并记录警告日志。
     * 若需使用 reasoning_effort，应改用 /v1/responses 接口（通过 generateKwargs 的 completionsPath 配置）。
     */
    private static OpenAiApi.ChatCompletionRequest stripReasoningEffortIfIncompatible(
            OpenAiApi.ChatCompletionRequest request) {
        if (request.reasoningEffort() == null) {
            return request;
        }
        if (request.tools() == null || request.tools().isEmpty()) {
            return request;
        }
        String model = request.model();
        if (model == null || !model.trim().toLowerCase().startsWith("gpt-5")) {
            return request;
        }

        log.warn("[GPT-5 兼容] 模型 {} 在 chat/completions 下同时携带 tools 和 reasoning_effort，"
                        + "自动移除 reasoning_effort 以避免 400 错误。"
                        + "如需 reasoning_effort，请将 completionsPath 配置为 /v1/responses",
                model);

        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                null,  // reasoningEffort — 移除
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    private static boolean requiresReasoningContentPatch(String modelName) {
        ModelFamily family = ModelFamily.detect(modelName);
        return family.isThinking();
    }

    /**
     * Strip {@code tool_choice="auto"} from outbound chat-completion requests.
     *
     * <p>Per the OpenAI spec, omitting {@code tool_choice} when {@code tools} is non-empty
     * is functionally equivalent to {@code "auto"} (the server defaults to auto-pick).
     * Stripping the explicit literal {@code "auto"}:
     * <ul>
     *   <li>does not change behavior on compliant servers (e.g. OpenAI, DashScope) — they
     *       still default to auto when tools are present</li>
     *   <li>unblocks strict OpenAI-compatible self-hosted serving frameworks that reject
     *       {@code tool_choice="auto"} at request validation time unless launched with an
     *       auto-tool-choice opt-in flag, which is a common reason custom endpoints
     *       respond with a generic 400 / "body=None" Pydantic error</li>
     * </ul>
     *
     * <p>Explicit values other than {@code "auto"} ({@code "none"}, {@code "required"},
     * or a specific function descriptor) are passed through unchanged.
     */
    private static OpenAiApi.ChatCompletionRequest stripAutoToolChoice(OpenAiApi.ChatCompletionRequest request) {
        Object tc = request.toolChoice();
        if (tc == null || !"auto".equals(String.valueOf(tc))) {
            return request;
        }
        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                null,  // toolChoice — strip "auto" so strict OpenAI-compatible servers accept the request
                request.parallelToolCalls(),
                request.user(),
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * 将 Spring AI 错误地序列化为 image_url 的视频内容块转换为 video_url 格式。
     * <p>
     * Spring AI 1.x 的 MediaContent 没有 video_url 类型，所有非 audio/pdf 的 Media
     * 都被序列化为 image_url。智谱 GLM-5V 等模型要求视频使用 video_url 格式，
     * 否则会报"图片输入格式/解析错误"。
     * <p>
     * 此方法遍历 user 消息的 rawContent，将 data:video/* 前缀的 image_url 替换为 video_url。
     */
    @SuppressWarnings("unchecked")
    private static OpenAiApi.ChatCompletionRequest patchVideoMediaContent(OpenAiApi.ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        boolean needsPatch = false;
        for (var msg : request.messages()) {
            if (msg.role() == OpenAiApi.ChatCompletionMessage.Role.USER) {
                Object raw = msg.rawContent();
                if (raw instanceof List<?> parts) {
                    for (Object part : parts) {
                        // 检查是否为 MediaContent record
                        if (part instanceof OpenAiApi.ChatCompletionMessage.MediaContent mc
                                && "image_url".equals(mc.type())
                                && mc.imageUrl() != null
                                && mc.imageUrl().url() != null
                                && mc.imageUrl().url().startsWith("data:video/")) {
                            needsPatch = true;
                            break;
                        }
                        // 检查是否为 Map（Spring AI 内部用 LinkedHashMap 表示 content parts）
                        if (part instanceof java.util.Map<?,?> map) {
                            Object type = map.get("type");
                            if ("image_url".equals(type)) {
                                Object imgUrlObj = map.get("image_url");
                                if (imgUrlObj instanceof java.util.Map<?,?> imgUrl) {
                                    Object url = imgUrl.get("url");
                                    if (url instanceof String urlStr && urlStr.startsWith("data:video/")) {
                                        needsPatch = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (needsPatch) break;
        }
        if (!needsPatch) {
            return request;
        }

        List<OpenAiApi.ChatCompletionMessage> patched = request.messages().stream().map(msg -> {
            if (msg.role() != OpenAiApi.ChatCompletionMessage.Role.USER || !(msg.rawContent() instanceof List<?> parts)) {
                return msg;
            }
            List<Object> newParts = new ArrayList<>();
            for (Object part : parts) {
                String videoDataUrl = null;

                // 场景 1：MediaContent record（Spring AI 原生构建）
                if (part instanceof OpenAiApi.ChatCompletionMessage.MediaContent mc
                        && "image_url".equals(mc.type())
                        && mc.imageUrl() != null && mc.imageUrl().url() != null
                        && mc.imageUrl().url().startsWith("data:video/")) {
                    videoDataUrl = mc.imageUrl().url();
                }
                // 场景 2：Map（Jackson 反序列化或 Spring AI 内部用 Map 表示）
                if (videoDataUrl == null && part instanceof java.util.Map<?,?> map
                        && "image_url".equals(map.get("type"))) {
                    Object imgUrlObj = map.get("image_url");
                    if (imgUrlObj instanceof java.util.Map<?,?> imgUrl) {
                        Object url = imgUrl.get("url");
                        if (url instanceof String urlStr && urlStr.startsWith("data:video/")) {
                            videoDataUrl = urlStr;
                        }
                    }
                }

                if (videoDataUrl != null) {
                    // 替换为 video_url 格式
                    newParts.add(Map.of(
                            "type", "video_url",
                            "video_url", Map.of("url", videoDataUrl)
                    ));
                } else {
                    newParts.add(part);
                }
            }
            return new OpenAiApi.ChatCompletionMessage(
                    newParts, msg.role(), msg.name(), msg.toolCallId(),
                    msg.toolCalls(), msg.refusal(), msg.audioOutput(),
                    msg.annotations(), msg.reasoningContent());
        }).toList();

        return new OpenAiApi.ChatCompletionRequest(
                patched,
                request.model(), request.store(), request.metadata(),
                request.frequencyPenalty(), request.logitBias(),
                request.logprobs(), request.topLogprobs(),
                request.maxTokens(), request.maxCompletionTokens(),
                request.n(), request.outputModalities(), request.audioParameters(),
                request.presencePenalty(), request.responseFormat(),
                request.seed(), request.serviceTier(), request.stop(),
                request.stream(), request.streamOptions(),
                request.temperature(), request.topP(),
                request.tools(), request.toolChoice(), request.parallelToolCalls(),
                request.user(), request.reasoningEffort(),
                request.webSearchOptions(), request.verbosity(),
                request.promptCacheKey(), request.safetyIdentifier(),
                request.extraBody()
        );
    }

    private void logOpenAiRequest(ModelProviderEntity provider, OpenAiApi.ChatCompletionRequest chatRequest) {
        try {
            log.info("OpenAI-compatible request: provider={}, body={}",
                    provider.getProviderId(), objectMapper.writeValueAsString(chatRequest));
        } catch (Exception e) {
            log.warn("Failed to serialize OpenAI-compatible request for {}: {}",
                    provider.getProviderId(), e.getMessage());
        }
    }

    private void logOpenAiError(ModelProviderEntity provider, WebClientResponseException e) {
        log.error("OpenAI-compatible error: provider={}, status={}, body={}",
                provider.getProviderId(), e.getStatusCode(), e.getResponseBodyAsString());
    }
}
