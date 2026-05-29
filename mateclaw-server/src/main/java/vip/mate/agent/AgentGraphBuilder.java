package vip.mate.agent;

// PR-0b: DashScope imports moved with the construction code into DashScopeChatModelBuilder.
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.agent.graph.StateGraphReActAgent;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.edge.ObservationDispatcher;
import vip.mate.agent.graph.edge.ReasoningDispatcher;
import vip.mate.agent.graph.lifecycle.ReActLifecycleListener;
import vip.mate.agent.graph.node.*;
import vip.mate.agent.graph.state.MateClawStateAccessor;
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
import vip.mate.llm.chatmodel.OpenAiCompatibleChatModelBuilder;
import vip.mate.llm.chatmodel.ReasoningEffortResolver;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelFamily;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.routing.ProviderRouter;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.planning.service.PlanningService;
import vip.mate.skill.runtime.SkillCatalogRenderer;
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
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final vip.mate.tool.disclosure.ToolDisclosureService toolDisclosureService;
    private final vip.mate.agent.progress.ProgressLedgerService progressLedgerService;

    /** Escape hatch: when false, the load_skill meta tool is not advertised. */
    @org.springframework.beans.factory.annotation.Value(
            "${mateclaw.skill.disclosure.load-skill-tool.enabled:true}")
    private boolean loadSkillToolEnabled;
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
    // PR-0b: dashScopeChatModel + dashScopeConnectionProperties live on DashScopeChatModelBuilder now.
    private final RetryTemplate retryTemplate;
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
    /** DashScope-specific construction lives here; only called for the built-in-search log. */
    private final vip.mate.llm.chatmodel.DashScopeChatModelBuilder dashScopeBuilder;
    private final vip.mate.llm.routing.MultimodalRouter multimodalRouter;
    private final vip.mate.llm.routing.MediaCaptionService mediaCaptionService;
    private final vip.mate.goal.service.GoalService goalService;
    private final vip.mate.goal.service.GoalEvaluationService goalEvaluationService;
    private final vip.mate.goal.service.GoalFollowupService goalFollowupService;
    private final vip.mate.goal.config.GoalProperties goalProperties;

    /**
     * Auto-grant resolver wired into the executor so an active
     * {@code mate_approval_grant} row can skip {@code createPending()} for matching
     * tool calls. Together with {@link #workspaceLookupCache}, these two deps form
     * the auto-grant entry point; the executor's null-guard turns the feature off
     * cleanly if either is missing.
     */
    private final vip.mate.approval.grant.service.ApprovalGrantResolver approvalGrantResolver;

    /** Conversation→workspaceId lookup cache; see {@link #approvalGrantResolver}. */
    private final vip.mate.approval.grant.WorkspaceLookupCache workspaceLookupCache;

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
     * 根据 AgentEntity 构建完整的 Agent 实例（沿用 Agent / 全局默认模型）。
     */
    public BaseAgent build(AgentEntity entity) {
        return build(entity, null, null);
    }

    /**
     * Resolve the model the runtime should use, honouring the precedence
     * <em>conversation pin &gt; Agent model override &gt; global default</em>.
     * A conversation pin that no longer resolves to an enabled model (the model
     * was disabled or deleted after it was picked) silently degrades to the
     * Agent / global default rather than failing the chat.
     */
    private ModelConfigEntity resolveRuntimeBaseModel(String modelProvider, String modelName,
                                                      String agentModelName) {
        if (modelProvider != null && !modelProvider.isBlank()
                && modelName != null && !modelName.isBlank()) {
            ModelConfigEntity pinned = modelConfigService.findEnabledModel(modelProvider, modelName);
            if (pinned != null) {
                return pinned;
            }
            log.info("Conversation model pin {}/{} is no longer an enabled model — "
                    + "falling back to the Agent / global default", modelProvider, modelName);
        }
        return modelConfigService.resolveModel(agentModelName);
    }

    /**
     * True iff the caller passed a complete (provider, model) pin AND that
     * pair resolves to an enabled model row. Used by {@link #build} to decide
     * whether the explicit pick should bypass capability-driven routing.
     */
    private boolean pinResolvesToEnabledModel(String modelProvider, String modelName) {
        if (modelProvider == null || modelProvider.isBlank()
                || modelName == null || modelName.isBlank()) {
            return false;
        }
        try {
            return modelConfigService.findEnabledModel(modelProvider, modelName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据 AgentEntity 构建完整的 Agent 实例。
     *
     * <p>{@code modelProvider} / {@code modelName} carry an optional
     * per-conversation model pin; when both are blank the build falls back to
     * the Agent's model override, then the global default.</p>
     */
    public BaseAgent build(AgentEntity entity, String modelProvider, String modelName) {
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

        // Issue #184 follow-up: an agent that opted out of skills must not be
        // able to circle back and discover/load them via the meta tools. Strip
        // the skill-discovery surface (listAvailableSkills / load_skill /
        // readSkillFile / runSkillScript / listSkillFiles) here. This runs as a
        // separate deny layer so the allowlist matrix in getEffectiveToolNames
        // stays untouched — in particular, the (skillsDisabled, !toolsDisabled,
        // no tool bindings) cell still returns null so non-skill global tools
        // continue to flow through.
        toolSet = toolSet.withDeniedToolsFiltered(
                agentBindingService.getSkillDiscoveryDeniedTools(entity.getId()));

        // Escape hatch: drop the load_skill meta tool entirely when disabled, so
        // it isn't advertised regardless of binding (the catalog guidance falls
        // back to readSkillFile — see SkillRuntimeService).
        if (!loadSkillToolEnabled) {
            toolSet = toolSet.excluding(java.util.Set.of("load_skill"));
        }

        // Resolve the base model with the precedence: per-conversation pin >
        // per-Agent model override > global default. resolveRuntimeBaseModel
        // looks up enabled-only models and silently degrades an unmatched pin /
        // override to the global default, preserving the legacy behaviour for
        // Agents and conversations without an explicit choice.
        ModelConfigEntity globalDefault;
        boolean explicitPinHonoured;
        try {
            explicitPinHonoured = pinResolvesToEnabledModel(modelProvider, modelName);
            globalDefault = resolveRuntimeBaseModel(modelProvider, modelName, entity.getModelName());
        } catch (Exception e) {
            throw new MateClawException("err.agent.no_default_model", "无法构建 Agent：请先在「设置 → 模型」中配置并启用默认模型");
        }
        ModelConfigEntity runtimeModel;
        if (explicitPinHonoured) {
            // The caller (admin UI / chat console) handed us a concrete
            // (provider, model) pin and it points to an enabled row. Honour
            // it verbatim — running providerRouter.selectPrimary here would
            // silently swap to a different model whenever a bound skill
            // advertised a capability gap, which is exactly the "I switched
            // model but the agent kept using the old one" surface. The
            // diagnostic below still surfaces capability gaps in the logs
            // so operators can see if the pinned model misses a need.
            runtimeModel = globalDefault;
        } else {
            try {
                runtimeModel = providerRouter.selectPrimary(entity.getId(), globalDefault);
                if (runtimeModel == null) runtimeModel = globalDefault;
            } catch (Exception e) {
                log.debug("[ProviderRouter] primary selection failed, falling back to global default: {}",
                        e.getMessage());
                runtimeModel = globalDefault;
            }
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
        } else if (OpenAiCompatibleChatModelBuilder.isKimiProvider(provider)
                && Boolean.TRUE.equals(providerKwargs.get("enableSearch"))) {
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

        String enhancedPrompt = buildEnhancedPrompt(entity, builtinSearchEnabled);

        // Runtime skill-catalog renderer — captures this agent's bound skills,
        // effective tool allowlist, model window and workspace; invoked each
        // turn by the reasoning / step-execution nodes with the skills loaded
        // so far this run so load_skill pins float to the top of the catalog.
        SkillCatalogRenderer skillCatalogRenderer = buildSkillCatalogRenderer(
                entity, boundTools, runtimeModel.getMaxInputTokens());

        // Extension-tool catalog — only for ReAct. The dynamic tool split runs
        // in ReasoningNode; Plan-Execute keeps advertising every tool (it has no
        // action node to record enable_tool), so baking the catalog there would
        // describe an enable_tool flow that can never take effect.
        boolean isPlanExecute = "plan_execute".equals(entity.getAgentType());
        if (!isPlanExecute) {
            String extensionCatalog = toolDisclosureService.renderExtensionCatalog(
                    toolSet, runtimeModel.getMaxInputTokens());
            if (extensionCatalog != null && !extensionCatalog.isBlank()) {
                enhancedPrompt = enhancedPrompt + extensionCatalog;
            }
        }

        // 当前仅支持 DashScope 和 OpenAI-compatible，其他协议直接拒绝
        if (!supportsStateGraph(protocol)) {
            throw new MateClawException("err.agent.protocol_not_supported", "当前不支持协议 " + protocol.getId()
                    + "，请切换到 DashScope 或 OpenAI-compatible 模型");
        }

        BaseAgent agent;
        boolean toolCallingEnabled;
        if ("plan_execute".equals(entity.getAgentType())) {
            agent = buildPlanExecuteAgent(toolSet, runtimeModel, maxIter, entity.getId(), skillCatalogRenderer);
            toolCallingEnabled = true;
            log.info("Built StateGraph Plan-Execute agent: {} (maxIterations={}, tools={}, protocol={})",
                    entity.getName(), maxIter, toolSet.size(), protocol.getId());
        } else {
            agent = buildReActAgent(toolSet, runtimeModel, maxIter, entity.getId(), skillCatalogRenderer);
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
        // RFC 48 — wire the goal lookup so buildInitialState can inject
        // ACTIVE_GOAL. The node itself stays inert until goalProperties.enabled
        // flips true, but tests need findActiveByConversation to work even
        // when the runtime path is disabled.
        agent.goalService = goalService;
        agent.multimodalRouter = multimodalRouter;
        agent.mediaCaptionService = mediaCaptionService;
        agent.userLocale = resolveLocale();
        agent.temperature = runtimeModel.getTemperature();
        agent.maxTokens = runtimeModel.getMaxTokens();
        agent.maxInputTokens = runtimeModel.getMaxInputTokens();
        agent.topP = runtimeModel.getTopP();
        agent.toolCallingEnabled = toolCallingEnabled;

        // Agent-level override takes priority; a relative override is resolved
        // under the workspace basePath so admins can express agent directories
        // relative to the workspace root (matching the UI hint).
        String workspaceBase = null;
        if (entity.getWorkspaceId() != null) {
            try {
                var workspace = workspaceService.getById(entity.getWorkspaceId());
                if (workspace != null) {
                    workspaceBase = workspace.getBasePath();
                }
            } catch (Exception e) {
                log.warn("Failed to lookup workspace basePath for agent {}: {}",
                        entity.getName(), e.getMessage());
            }
        }
        String resolvedBase;
        try {
            resolvedBase = resolveAgentBasePath(entity.getWorkspaceBasePath(), workspaceBase);
        } catch (IllegalArgumentException e) {
            // Override violates the workspace-scoping rule (e.g. admin tried to
            // set an absolute path outside the workspace root). Fall back to
            // inheriting the workspace basePath so chat stays available, but
            // surface the violation in logs so the admin can fix it.
            log.warn("Agent {} workspaceBasePath override rejected, falling back to workspace: {}",
                    entity.getName(), e.getMessage());
            resolvedBase = workspaceBase;
        }
        if (resolvedBase != null && !resolvedBase.isBlank()) {
            agent.workspaceBasePath = resolvedBase;
            boolean fromOverride = entity.getWorkspaceBasePath() != null
                    && !entity.getWorkspaceBasePath().isBlank()
                    && resolvedBase.equals(entity.getWorkspaceBasePath());
            log.info("Agent {} basePath = {} (source: {})",
                    entity.getName(), resolvedBase, fromOverride ? "agent-override" : "workspace");
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
        return buildReActAgent(toolSet, runtimeModel, maxIter, agentId, null);
    }

    StateGraphReActAgent buildReActAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel,
                                         int maxIter, Long agentId, SkillCatalogRenderer skillCatalogRenderer) {
        ChatModel chatModel = buildRuntimeChatModel(runtimeModel);
        ChatClient chatClient = ChatClient.create(chatModel);
        String reasoningEffort = resolveReasoningEffortForModel(runtimeModel);
        CompiledGraph compiledGraph = buildReActGraph(toolSet, chatModel, maxIter, reasoningEffort,
                runtimeModel, agentId, skillCatalogRenderer);
        return new StateGraphReActAgent(chatClient, conversationService, compiledGraph,
                chatModel, conversationWindowManager, toolSet);
    }

    StateGraphPlanExecuteAgent buildPlanExecuteAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel, int maxIter) {
        return buildPlanExecuteAgent(toolSet, runtimeModel, maxIter, null);
    }

    StateGraphPlanExecuteAgent buildPlanExecuteAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel,
                                                     int maxIter, Long agentId) {
        return buildPlanExecuteAgent(toolSet, runtimeModel, maxIter, agentId, null);
    }

    StateGraphPlanExecuteAgent buildPlanExecuteAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel,
                                                     int maxIter, Long agentId,
                                                     SkillCatalogRenderer skillCatalogRenderer) {
        ChatModel chatModel = buildRuntimeChatModel(runtimeModel);
        ChatClient chatClient = ChatClient.create(chatModel);
        String reasoningEffort = resolveReasoningEffortForModel(runtimeModel);
        CompiledGraph graph = buildPlanExecuteGraph(toolSet, chatModel, maxIter, reasoningEffort,
                runtimeModel, agentId, skillCatalogRenderer);
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
        return buildPlanExecuteGraph(toolSet, chatModel, maxIterations, reasoningEffort,
                primaryModelConfig, agentId, null);
    }

    CompiledGraph buildPlanExecuteGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                         String reasoningEffort, ModelConfigEntity primaryModelConfig,
                                         Long agentId, SkillCatalogRenderer skillCatalogRenderer) {
        try {
            List<vip.mate.llm.failover.FallbackEntry> fallbackChain = buildFallbackChain(primaryModelConfig, agentId);
            NodeStreamingChatHelper streamingHelper = new NodeStreamingChatHelper(
                    streamTracker, fallbackChain, llmCacheMetricsAggregator, providerHealthTracker,
                    primaryModelConfig != null ? primaryModelConfig.getProvider() : null,
                    providerPool);
            ToolExecutionExecutor executor = new ToolExecutionExecutor(
                    toolSet, toolGuardService, approvalService, streamTracker,
                    toolTimeoutProperties, toolResultStorage, toolConcurrencyRegistry,
                    workspaceLookupCache, approvalGrantResolver);
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
            StepExecutionNode stepExecutionNode = new StepExecutionNode(chatModel, toolSet, executor, planningService, streamTracker, reasoningEffort, streamingHelper, conversationWindowManager, skillCatalogRenderer);
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
                    .addStrategy(MateClawStateKeys.LLM_CALL_COUNT, KeyStrategy.REPLACE)
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
                    // RFC 48 — persistent goal state keys must be registered in
                    // BOTH graph KeyStrategyFactory blocks. The architecture
                    // coverage test only checks "appears somewhere"; the
                    // GoalStateKeyDoubleRegistrationTest below verifies the
                    // double registration explicitly.
                    .addStrategy(MateClawStateKeys.ACTIVE_GOAL, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_EVALUATION_RESULT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_FOLLOWUP_INJECTED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_FOLLOWUP_PROMPT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_EVALUATED_THIS_RUN, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_FOLLOWUP_COUNT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_ACCOUNTED_LLM_CALL_COUNT, KeyStrategy.REPLACE)
                    // Skill progressive disclosure — pinned skills loaded this
                    // run. Registered in BOTH graphs so the read-merge-write in
                    // ActionNode is not dropped on multi-node merges.
                    .addStrategy(MateClawStateKeys.LOADED_SKILLS, KeyStrategy.REPLACE)
                    // Tool progressive disclosure — extensions enabled this run.
                    // Registered in BOTH graphs for the same merge-safety reason.
                    .addStrategy(MateClawStateKeys.ENABLED_EXTENSION_TOOLS, KeyStrategy.REPLACE)
                    .build();

            // Graph 拓扑：
            // START → PLAN_GENERATION → (PlanGenerationDispatcher)
            //   ├→ DIRECT_ANSWER_NODE → END
            //   └→ STEP_EXECUTION → (StepProgressDispatcher)
            //       ├→ STEP_EXECUTION (loop)
            //       └→ PLAN_SUMMARY → (active goal?)
            //                          ├→ GOAL_EVALUATION → (followup?)
            //                          │                     ├→ PLAN_GENERATION (re-plan)
            //                          │                     └→ END
            //                          └→ END

            GoalEvaluationNode goalEvalNode = new GoalEvaluationNode(
                    goalEvaluationService, goalFollowupService, goalService, goalProperties,
                    conversationWindowManager, conversationService,
                    vip.mate.goal.service.GraphFlavor.PLAN_EXECUTE);

            StateGraph graph = new StateGraph("plan-execute-agent", keyStrategyFactory)
                    .addNode(PlanStateKeys.PLAN_GENERATION_NODE,
                            AsyncNodeAction.node_async(planGenerationNode))
                    .addNode(PlanStateKeys.STEP_EXECUTION_NODE,
                            AsyncNodeAction.node_async(stepExecutionNode))
                    .addNode(PlanStateKeys.PLAN_SUMMARY_NODE,
                            AsyncNodeAction.node_async(planSummaryNode))
                    .addNode(PlanStateKeys.DIRECT_ANSWER_NODE,
                            AsyncNodeAction.node_async(directAnswerNode))
                    .addNode(MateClawStateKeys.GOAL_EVALUATION_NODE,
                            AsyncNodeAction.node_async(goalEvalNode))
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
                    .addConditionalEdges(PlanStateKeys.PLAN_SUMMARY_NODE,
                            AsyncEdgeAction.edge_async(state -> {
                                MateClawStateAccessor a = new MateClawStateAccessor(state);
                                boolean hasGoal = a.hasActiveGoal();
                                boolean already = a.goalEvaluatedThisRun();
                                return (hasGoal && !already)
                                        ? MateClawStateKeys.GOAL_EVALUATION_NODE
                                        : StateGraph.END;
                            }),
                            Map.of(
                                    MateClawStateKeys.GOAL_EVALUATION_NODE, MateClawStateKeys.GOAL_EVALUATION_NODE,
                                    StateGraph.END, StateGraph.END))
                    .addConditionalEdges(MateClawStateKeys.GOAL_EVALUATION_NODE,
                            AsyncEdgeAction.edge_async(new vip.mate.agent.graph.edge.GoalEvaluationDispatcher(
                                    PlanStateKeys.PLAN_GENERATION_NODE, StateGraph.END)),
                            Map.of(
                                    PlanStateKeys.PLAN_GENERATION_NODE, PlanStateKeys.PLAN_GENERATION_NODE,
                                    StateGraph.END, StateGraph.END))
                    // DIRECT_ANSWER_NODE handles trivial requests that bypass the
                    // multi-step plan. For active goals, the direct answer is still
                    // a turn — without this edge, turns_used / score / completion
                    // would never tick on plan-execute conversations whose every
                    // reply happened to be simple enough to short-circuit through
                    // the direct path. Mirror PLAN_SUMMARY_NODE's gate so non-goal
                    // turns still go straight to END (no goal node invocation).
                    .addConditionalEdges(PlanStateKeys.DIRECT_ANSWER_NODE,
                            AsyncEdgeAction.edge_async(state -> {
                                MateClawStateAccessor a = new MateClawStateAccessor(state);
                                boolean hasGoal = a.hasActiveGoal();
                                boolean already = a.goalEvaluatedThisRun();
                                return (hasGoal && !already)
                                        ? MateClawStateKeys.GOAL_EVALUATION_NODE
                                        : StateGraph.END;
                            }),
                            Map.of(
                                    MateClawStateKeys.GOAL_EVALUATION_NODE, MateClawStateKeys.GOAL_EVALUATION_NODE,
                                    StateGraph.END, StateGraph.END));

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
        return buildReActGraph(toolSet, chatModel, maxIterations, reasoningEffort,
                primaryModelConfig, agentId, null);
    }

    CompiledGraph buildReActGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                   String reasoningEffort, ModelConfigEntity primaryModelConfig,
                                   Long agentId, SkillCatalogRenderer skillCatalogRenderer) {
        try {
            List<vip.mate.llm.failover.FallbackEntry> fallbackChain = buildFallbackChain(primaryModelConfig, agentId);
            NodeStreamingChatHelper streamingHelper = new NodeStreamingChatHelper(
                    streamTracker, fallbackChain, llmCacheMetricsAggregator, providerHealthTracker,
                    primaryModelConfig != null ? primaryModelConfig.getProvider() : null,
                    providerPool);
            ToolExecutionExecutor executor = new ToolExecutionExecutor(
                    toolSet, toolGuardService, approvalService, streamTracker,
                    toolTimeoutProperties, toolResultStorage, toolConcurrencyRegistry,
                    workspaceLookupCache, approvalGrantResolver);
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
                    streamingHelper, conversationWindowManager, streamTracker, 0, wikiContextService,
                    skillCatalogRenderer, toolDisclosureService, progressLedgerService);
            ActionNode actionNode = new ActionNode(executor, streamTracker);
            ObservationProcessor observationProcessor = new ObservationProcessor(graphObservationProperties);
            ObservationNode observationNode = new ObservationNode(observationProcessor, streamTracker);
            SummarizingNode summarizingNode = new SummarizingNode(chatModel, streamingHelper, streamTracker);
            LimitExceededNode limitExceededNode = new LimitExceededNode(
                    chatModel, observationProcessor, streamingHelper, i18nService, progressLedgerService);
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
                    // RFC 48 — persistent goal state keys must be registered in
                    // BOTH graph KeyStrategyFactory blocks. See
                    // GoalStateKeyDoubleRegistrationTest for the strict
                    // double-registration check.
                    .addStrategy(MateClawStateKeys.ACTIVE_GOAL, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_EVALUATION_RESULT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_FOLLOWUP_INJECTED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_FOLLOWUP_PROMPT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_EVALUATED_THIS_RUN, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_FOLLOWUP_COUNT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.GOAL_ACCOUNTED_LLM_CALL_COUNT, KeyStrategy.REPLACE)
                    // Skill progressive disclosure — pinned skills loaded this
                    // run. Registered in BOTH graphs so the read-merge-write in
                    // ActionNode is not dropped on multi-node merges.
                    .addStrategy(MateClawStateKeys.LOADED_SKILLS, KeyStrategy.REPLACE)
                    // Tool progressive disclosure — extensions enabled this run.
                    // Registered in BOTH graphs for the same merge-safety reason.
                    .addStrategy(MateClawStateKeys.ENABLED_EXTENSION_TOOLS, KeyStrategy.REPLACE)
                    .build();

            GoalEvaluationNode goalEvalNode = new GoalEvaluationNode(
                    goalEvaluationService, goalFollowupService, goalService, goalProperties,
                    conversationWindowManager, conversationService,
                    vip.mate.goal.service.GraphFlavor.REACT);

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
                    .addNode(MateClawStateKeys.GOAL_EVALUATION_NODE,
                            AsyncNodeAction.node_async(goalEvalNode))
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
                    // FinalAnswer -> (active goal && not yet evaluated this run) ? GoalEvaluation : END
                    .addConditionalEdges(MateClawStateKeys.FINAL_ANSWER_NODE,
                            AsyncEdgeAction.edge_async(state -> {
                                MateClawStateAccessor a = new MateClawStateAccessor(state);
                                boolean hasGoal = a.hasActiveGoal();
                                boolean already = a.goalEvaluatedThisRun();
                                return (hasGoal && !already)
                                        ? MateClawStateKeys.GOAL_EVALUATION_NODE
                                        : StateGraph.END;
                            }),
                            Map.of(
                                    MateClawStateKeys.GOAL_EVALUATION_NODE, MateClawStateKeys.GOAL_EVALUATION_NODE,
                                    StateGraph.END, StateGraph.END))
                    // GoalEvaluation -> (followup injected) ? Reasoning : END
                    .addConditionalEdges(MateClawStateKeys.GOAL_EVALUATION_NODE,
                            AsyncEdgeAction.edge_async(new vip.mate.agent.graph.edge.GoalEvaluationDispatcher(
                                    MateClawStateKeys.REASONING_NODE, StateGraph.END)),
                            Map.of(
                                    MateClawStateKeys.REASONING_NODE, MateClawStateKeys.REASONING_NODE,
                                    StateGraph.END, StateGraph.END));

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
                || protocol == ModelProtocol.OPENAI_CHATGPT
                // Gemini native generateContent — GeminiChatModel exposes the same
                // streaming + tool-calling surface the StateGraph nodes rely on.
                || protocol == ModelProtocol.GEMINI_NATIVE;
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
     * Resolve the effective working directory for an agent.
     * <p>Precedence:
     * <ol>
     *   <li>When the agent-level override is set, it wins.</li>
     *   <li>An absolute override is used verbatim, but only when it sits
     *       inside the workspace basePath (or when the workspace has no
     *       basePath of its own). An absolute path that points outside a
     *       configured workspace root is rejected — otherwise a less-trusted
     *       user with agent-edit access could set
     *       {@code workspaceBasePath="/"} and bypass workspace scoping.</li>
     *   <li>A relative override is resolved <em>under</em> the workspace basePath
     *       when the workspace has one, matching the UI hint that agent paths
     *       are relative to the workspace root.</li>
     *   <li>A relative override with no workspace basePath is used as-is
     *       (resolves against the JVM working directory at file-tool time).</li>
     *   <li>With no override, the workspace basePath is inherited verbatim;
     *       returns {@code null} when neither side has a value.</li>
     * </ol>
     *
     * @throws IllegalArgumentException when an absolute override escapes the
     *         workspace root
     */
    static String resolveAgentBasePath(String agentOverride, String workspaceBase) {
        boolean hasOverride = agentOverride != null && !agentOverride.isBlank();
        boolean hasWorkspace = workspaceBase != null && !workspaceBase.isBlank();
        if (!hasOverride) {
            return hasWorkspace ? workspaceBase : null;
        }
        Path overridePath = Paths.get(agentOverride);
        if (overridePath.isAbsolute()) {
            if (hasWorkspace) {
                Path wsRoot = Paths.get(workspaceBase).toAbsolutePath().normalize();
                Path absOverride = overridePath.toAbsolutePath().normalize();
                if (!absOverride.startsWith(wsRoot)) {
                    throw new IllegalArgumentException(
                            "Agent workspaceBasePath override must be inside the workspace root: "
                                    + absOverride + " is not under " + wsRoot);
                }
            }
            return agentOverride;
        }
        if (hasWorkspace) {
            return Paths.get(workspaceBase).resolve(agentOverride).toString();
        }
        return agentOverride;
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
    // PR-0b: isDashScopeSearchEnabled moved to DashScopeChatModelBuilder.

    // ==================== Prompt 构建 ====================

    private String buildEnhancedPrompt(AgentEntity entity, boolean builtinSearchEnabled) {
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

        // The skill catalog (## Skills) is NOT baked here. It is rendered at
        // runtime by the reasoning / step-execution nodes via
        // SkillCatalogRenderer so its ordering can react to skills loaded this
        // run (load_skill pins). Keeping it out of the baked system prompt also
        // keeps the prompt-cache prefix stable across turns.

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

        return basePrompt + toolGuidance + searchGuidance + wikiContext;
    }

    /**
     * Build the per-agent {@link SkillCatalogRenderer}. Captures the agent's
     * bound skills, effective tool allowlist, model window and workspace once;
     * the returned renderer is invoked each turn with the skills loaded so far
     * this run so {@code load_skill} pins float to the top of the catalog.
     */
    private SkillCatalogRenderer buildSkillCatalogRenderer(AgentEntity entity, Set<String> boundTools,
                                                           Integer maxInputTokens) {
        Set<Long> boundSkillIds = agentBindingService.getBoundSkillIds(entity.getId());
        Long agentId = entity.getId();
        Long workspaceId = entity.getWorkspaceId();
        return loaded -> skillRuntimeService.buildSkillPromptEnhancement(
                boundSkillIds, boundTools, maxInputTokens, agentId, workspaceId, loaded);
    }

    /**
     * Resolve the {@code reasoning_effort} to pass to the reasoning /
     * step-execution nodes for the given model.
     */
    private String resolveReasoningEffortForModel(ModelConfigEntity runtimeModel) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(runtimeModel.getProvider());
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        ModelFamily family = ModelFamily.detect(runtimeModel.getModelName());
        return ReasoningEffortResolver.resolveReasoningEffort(runtimeModel.getModelName(), kwargs, family);
    }
}
