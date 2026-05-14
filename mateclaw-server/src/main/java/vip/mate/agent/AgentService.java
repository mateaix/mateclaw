package vip.mate.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.agent.event.AgentLifecycleEvent;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.lifecycle.MemoryLifecycleMediator;
import vip.mate.memory.lifecycle.TurnContext;
import vip.mate.memory.service.MemoryRecallTracker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Agent 业务服务
 * <p>
 * 负责 Agent 的 CRUD 管理和运行时实例管理。
 * 构建逻辑委托给 {@link AgentGraphBuilder}。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryRecallTracker memoryRecallTracker;
    private final MemoryLifecycleMediator lifecycleMediator;
    private final MemoryProperties memoryProperties;

    /** Field-injected publisher for agent_lifecycle trigger events; the
     *  trigger module's bridge listens and forwards into ingest. */
    @Autowired(required = false)
    private ApplicationEventPublisher events;

    /** 运行时 Agent 实例缓存（agentId -> BaseAgent） */
    private final Map<Long, BaseAgent> agentInstances = new ConcurrentHashMap<>();

    // ==================== CRUD ====================

    public List<AgentEntity> listAgents() {
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .orderByDesc(AgentEntity::getCreateTime));
    }

    /**
     * 按工作区列出 Agent
     */
    public List<AgentEntity> listAgentsByWorkspace(Long workspaceId) {
        return listAgentsByWorkspace(workspaceId, null);
    }

    /**
     * 按工作区列出 Agent，可选过滤启用状态。
     *
     * @param enabled non-null restricts the result set to agents whose
     *                {@code enabled} column matches the given value.
     *                Pass {@code true} from chat selectors so disabled
     *                agents disappear from the picker; the admin
     *                management page passes {@code null} to keep
     *                disabled rows visible for re-enabling.
     */
    public List<AgentEntity> listAgentsByWorkspace(Long workspaceId, Boolean enabled) {
        LambdaQueryWrapper<AgentEntity> q = new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId);
        if (enabled != null) {
            q.eq(AgentEntity::getEnabled, enabled);
        }
        return agentMapper.selectList(q.orderByDesc(AgentEntity::getCreateTime));
    }

    public AgentEntity getAgent(Long id) {
        AgentEntity entity = agentMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.agent.not_found", "Agent不存在: " + id);
        }
        return entity;
    }

    public AgentEntity createAgent(AgentEntity agent) {
        agent.setEnabled(true);
        if (agent.getAgentType() == null) {
            agent.setAgentType("react");
        }
        requireUniqueName(agent, null);
        agentMapper.insert(agent);
        publishLifecycle(agent, "spawned");
        return agent;
    }

    public AgentEntity updateAgent(AgentEntity agent) {
        // Detect enabled-flag flip so the lifecycle event reflects the
        // intent rather than every metadata edit. Reading the prior row
        // is cheap and gives us a clean diff source.
        AgentEntity prior = agentMapper.selectById(agent.getId());
        // Only re-validate uniqueness when the name actually changes —
        // a pure metadata edit (icon, prompt, ...) shouldn't pay the
        // SELECT cost or risk a false positive against the row itself.
        if (prior != null
                && agent.getName() != null
                && !agent.getName().equals(prior.getName())) {
            // Workspace cannot be moved (Controller pins it to prior.workspaceId),
            // so reuse it for the lookup even if the incoming DTO left it null.
            if (agent.getWorkspaceId() == null) {
                agent.setWorkspaceId(prior.getWorkspaceId());
            }
            requireUniqueName(agent, agent.getId());
        }
        agentMapper.updateById(agent);
        agentInstances.remove(agent.getId());
        if (prior != null && prior.getEnabled() != null
                && !prior.getEnabled().equals(agent.getEnabled())) {
            publishLifecycle(agent,
                    Boolean.TRUE.equals(agent.getEnabled()) ? "enabled" : "disabled");
        }
        return agent;
    }

    /**
     * Friendly business-code surface for the {@code (workspace_id, name)}
     * unique index added in V102.
     *
     * <p>The wire shape is the project-wide R&lt;T&gt; envelope: HTTP status
     * stays 200 (per the convention in {@code R.fail} and the axios
     * interceptor in {@code mateclaw-ui/src/api/index.ts}); the 409 lives in
     * the response body's {@code code} field so the front-end can branch
     * without breaking on an axios error. Without this pre-check the
     * duplicate save would surface as an opaque
     * {@code DataIntegrityViolation} stack trace.
     *
     * @param excludeId when non-null, skip this row in the lookup so
     *                  {@link #updateAgent} doesn't mistake the row for its
     *                  own duplicate.
     */
    private void requireUniqueName(AgentEntity agent, Long excludeId) {
        if (agent.getName() == null || agent.getName().isBlank()) {
            throw new MateClawException("err.agent.name_required", 400, "Agent 名称不能为空");
        }
        Long workspaceId = agent.getWorkspaceId() == null ? 1L : agent.getWorkspaceId();
        LambdaQueryWrapper<AgentEntity> q = new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .eq(AgentEntity::getName, agent.getName());
        if (excludeId != null) {
            q.ne(AgentEntity::getId, excludeId);
        }
        Long count = agentMapper.selectCount(q);
        if (count != null && count > 0) {
            throw new MateClawException("err.agent.duplicate_name", 409,
                    "工作区内已存在同名 Agent: " + agent.getName());
        }
    }

    public void deleteAgent(Long id) {
        AgentEntity prior = agentMapper.selectById(id);
        agentMapper.deleteById(id);
        agentInstances.remove(id);
        if (prior != null) publishLifecycle(prior, "terminated");
    }

    /**
     * Best-effort publish of an {@link AgentLifecycleEvent}. A publish
     * failure must never roll back the agent CRUD that just succeeded —
     * the agent_lifecycle trigger surface is observability, not the
     * canonical record.
     */
    private void publishLifecycle(AgentEntity agent, String phase) {
        if (events == null || agent == null) return;
        try {
            events.publishEvent(new AgentLifecycleEvent(
                    agent.getWorkspaceId() == null ? 0L : agent.getWorkspaceId(),
                    agent.getId() == null ? 0L : agent.getId(),
                    agent.getName(),
                    phase,
                    System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("[AgentService] lifecycle publish failed for agent {} ({}): {}",
                    agent.getId(), phase, e.getMessage());
        }
    }

    /**
     * 清除 Agent 运行时缓存（绑定变更后需调用，使下次对话重新构建 Agent）
     */
    public void invalidateAgentCache(Long agentId) {
        agentInstances.remove(agentId);
    }

    // ==================== 运行时入口 ====================

    public String chat(Long agentId, String message, String conversationId) {
        return chat(agentId, message, conversationId, ChatOrigin.EMPTY);
    }

    /**
     * RFC-063r §2.5: preferred entry — accepts the originating
     * {@link ChatOrigin} so channel binding and workspace context propagate
     * down to {@code @Tool} methods via Spring AI {@link org.springframework.ai.chat.model.ToolContext}.
     */
    public String chat(Long agentId, String message, String conversationId, ChatOrigin origin) {
        memoryRecallTracker.trackRecalls(agentId, message);
        BaseAgent agent = getOrBuildAgent(agentId);
        ChatOriginHolder.set(origin != null ? origin : ChatOrigin.EMPTY);
        try {
            return withLifecycleSync(agentId, message, conversationId,
                    (msg, convId) -> agent.chat(msg, convId));
        } finally {
            ChatOriginHolder.clear();
        }
    }

    public Flux<String> chatStream(Long agentId, String message, String conversationId) {
        return chatStream(agentId, message, conversationId, ChatOrigin.EMPTY);
    }

    public Flux<String> chatStream(Long agentId, String message, String conversationId, ChatOrigin origin) {
        memoryRecallTracker.trackRecalls(agentId, message);
        BaseAgent agent = getOrBuildAgent(agentId);
        // Capture the origin into a request-scoped holder; cleared on Flux
        // termination so the next reactive subscriber doesn't inherit stale state.
        ChatOrigin captured = origin != null ? origin : ChatOrigin.EMPTY;
        return Flux.defer(() -> {
            ChatOriginHolder.set(captured);
            return withLifecycleFlux(agentId, message, conversationId,
                    (msg, convId) -> agent.chatStream(msg, convId),
                    chunk -> chunk);
        }).doFinally(signal -> ChatOriginHolder.clear());
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId) {
        return chatStructuredStream(agentId, message, conversationId, "", null, ChatOrigin.EMPTY);
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId,
                                                   String requesterId) {
        return chatStructuredStream(agentId, message, conversationId, requesterId, null, ChatOrigin.EMPTY);
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId,
                                                   String requesterId, ChatOrigin origin) {
        return chatStructuredStream(agentId, message, conversationId, requesterId, null, origin);
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId,
                                                   String requesterId, String thinkingLevel) {
        return chatStructuredStream(agentId, message, conversationId, requesterId, thinkingLevel,
                ChatOrigin.EMPTY);
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId,
                                                   String requesterId, String thinkingLevel,
                                                   ChatOrigin origin) {
        memoryRecallTracker.trackRecalls(agentId, message);
        BaseAgent agent = getOrBuildAgent(agentId);

        // 设置请求级思考深度（通过 ThreadLocal 传递到 StateGraph 执行）
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            ThinkingLevelHolder.set(thinkingLevel);
        } else {
            // 尝试从 Agent 默认配置读取
            AgentEntity entity = getAgent(agentId);
            if (entity != null && entity.getDefaultThinkingLevel() != null) {
                ThinkingLevelHolder.set(entity.getDefaultThinkingLevel());
            } else {
                ThinkingLevelHolder.clear();
            }
        }

        ChatOrigin captured = origin != null ? origin : ChatOrigin.EMPTY;
        if (agent instanceof StructuredStreamCapable capable) {
            return Flux.defer(() -> {
                        ChatOriginHolder.set(captured);
                        return withLifecycleFlux(agentId, message, conversationId,
                                (msg, convId) -> capable.chatStructuredStream(msg, convId,
                                                requesterId != null ? requesterId : "")
                                        .doFinally(signal -> ThinkingLevelHolder.clear()),
                                StreamDelta::content);
                    })
                    .doFinally(signal -> ChatOriginHolder.clear());
        }

        // 降级：不支持结构化流的 Agent，包装为纯内容流
        ThinkingLevelHolder.clear();
        return Flux.defer(() -> {
                    ChatOriginHolder.set(captured);
                    return withLifecycleFlux(agentId, message, conversationId,
                            (msg, convId) -> agent.chatStream(msg, convId)
                                    .map(chunk -> new StreamDelta(chunk, null)),
                            StreamDelta::content);
                })
                .doFinally(signal -> ChatOriginHolder.clear());
    }

    public String execute(Long agentId, String goal, String conversationId) {
        return execute(agentId, goal, conversationId, ChatOrigin.EMPTY);
    }

    public String execute(Long agentId, String goal, String conversationId, ChatOrigin origin) {
        memoryRecallTracker.trackRecalls(agentId, goal);
        BaseAgent agent = getOrBuildAgent(agentId);
        ChatOriginHolder.set(origin != null ? origin : ChatOrigin.EMPTY);
        try {
            return withLifecycleSync(agentId, goal, conversationId,
                    (msg, convId) -> agent.execute(msg, convId));
        } finally {
            ChatOriginHolder.clear();
        }
    }

    /**
     * 带工具重放的 chat 调用（审批通过后由 ChannelMessageRouter 或 ApprovalController 调用）
     *
     * @param agentId          Agent ID
     * @param userMessage      用户消息（如"继续执行已批准的工具"）
     * @param conversationId   会话 ID
     * @param toolCallPayload  要重放的工具调用 JSON
     * @return Agent 回复
     */
    public String chatWithReplay(Long agentId, String userMessage, String conversationId,
                                  String toolCallPayload) {
        return chatWithReplay(agentId, userMessage, conversationId, toolCallPayload, ChatOrigin.EMPTY);
    }

    public String chatWithReplay(Long agentId, String userMessage, String conversationId,
                                  String toolCallPayload, ChatOrigin origin) {
        memoryRecallTracker.trackRecalls(agentId, userMessage);
        BaseAgent agent = getOrBuildAgent(agentId);
        ChatOriginHolder.set(origin != null ? origin : ChatOrigin.EMPTY);
        try {
            return withLifecycleSync(agentId, userMessage, conversationId,
                    (msg, convId) -> agent.chatWithReplay(msg, convId, toolCallPayload));
        } finally {
            ChatOriginHolder.clear();
        }
    }

    /**
     * 带工具重放的流式调用（Web 端审批通过后使用，通过 SSE 推送结果）
     */
    public Flux<StreamDelta> chatWithReplayStream(Long agentId, String userMessage, String conversationId,
                                                   String toolCallPayload) {
        return chatWithReplayStream(agentId, userMessage, conversationId, toolCallPayload, "", ChatOrigin.EMPTY);
    }

    public Flux<StreamDelta> chatWithReplayStream(Long agentId, String userMessage, String conversationId,
                                                   String toolCallPayload, String requesterId) {
        return chatWithReplayStream(agentId, userMessage, conversationId, toolCallPayload, requesterId,
                ChatOrigin.EMPTY);
    }

    public Flux<StreamDelta> chatWithReplayStream(Long agentId, String userMessage, String conversationId,
                                                   String toolCallPayload, String requesterId,
                                                   ChatOrigin origin) {
        memoryRecallTracker.trackRecalls(agentId, userMessage);
        BaseAgent agent = getOrBuildAgent(agentId);
        ChatOrigin captured = origin != null ? origin : ChatOrigin.EMPTY;
        return Flux.defer(() -> {
                    ChatOriginHolder.set(captured);
                    return withLifecycleFlux(agentId, userMessage, conversationId,
                            (msg, convId) -> agent.chatWithReplayStream(msg, convId, toolCallPayload,
                                    requesterId != null ? requesterId : ""),
                            StreamDelta::content);
                })
                .doFinally(signal -> ChatOriginHolder.clear());
    }

    public AgentState getAgentState(Long agentId) {
        BaseAgent agent = agentInstances.get(agentId);
        return agent != null ? agent.getState() : AgentState.IDLE;
    }

    // ==================== 缓存管理 ====================

    public void refreshAgent(Long agentId) {
        agentInstances.remove(agentId);
        log.info("Agent instance cache cleared: {}", agentId);
    }

    public void refreshAllAgents() {
        agentInstances.clear();
        log.info("All agent instance caches cleared");
    }

    @EventListener
    public void onModelConfigChanged(ModelConfigChangedEvent event) {
        refreshAllAgents();
        log.info("Agent caches refreshed after model config change: {}", event.reason());
    }

    @EventListener
    public void onToolGuardConfigChanged(vip.mate.tool.guard.service.ToolGuardConfigService.ToolGuardConfigChangedEvent event) {
        refreshAllAgents();
        log.info("Agent caches refreshed after tool guard config change (denied tools may have changed)");
    }

    // ==================== Lifecycle helpers ====================

    /**
     * Wraps a synchronous agent call with lifecycle mediator hooks.
     * When lifecycleMediatorEnabled is off, runs plainInvoke directly (Phase 0 behavior).
     *
     * P1-1 fix: prefetchAll result is now prepended to userMessage as &lt;memory-context&gt; block.
     * P1-4 fix: N/A for sync (no cancel/error signal issue).
     */
    private String withLifecycleSync(Long agentId, String message, String conversationId,
                                     java.util.function.BiFunction<String, String, String> invoke) {
        if (!memoryProperties.isLifecycleMediatorEnabled()) {
            return invoke.apply(message, conversationId);
        }
        TurnContext ctx = new TurnContext(agentId, conversationId, conversationId, 0, message);
        String memoryContext = lifecycleMediator.beforeLlmCall(ctx);
        // Inject memory context into the user message (RFC-037 §3.3)
        String enrichedMessage = injectMemoryContext(message, memoryContext);
        String result = invoke.apply(enrichedMessage, conversationId);
        lifecycleMediator.afterLlmCall(ctx, result != null ? result : "");
        return result;
    }

    /**
     * Wraps a streaming agent call with lifecycle mediator hooks.
     * When lifecycleMediatorEnabled is off, runs plainInvoke directly (Phase 0 behavior).
     *
     * P1-1 fix: prefetchAll result is now prepended to userMessage.
     * P1-4 fix: afterLlmCall only fires on COMPLETE signal, not on cancel/error.
     */
    private <T> Flux<T> withLifecycleFlux(Long agentId, String message, String conversationId,
                                          java.util.function.BiFunction<String, String, Flux<T>> invoke,
                                          Function<T, String> contentExtractor) {
        if (!memoryProperties.isLifecycleMediatorEnabled()) {
            return invoke.apply(message, conversationId);
        }
        TurnContext ctx = new TurnContext(agentId, conversationId, conversationId, 0, message);
        String memoryContext = lifecycleMediator.beforeLlmCall(ctx);
        String enrichedMessage = injectMemoryContext(message, memoryContext);
        StringBuilder reply = new StringBuilder();
        return invoke.apply(enrichedMessage, conversationId)
                .doOnNext(item -> {
                    String text = contentExtractor.apply(item);
                    if (text != null) {
                        reply.append(text);
                    }
                })
                .doOnComplete(() -> lifecycleMediator.afterLlmCall(ctx, reply.toString()))
                .doOnError(e -> log.debug("[Memory] Stream error, skipping afterLlmCall: {}", e.getMessage()));
    }

    /**
     * Prepend memory-context block to user message if non-empty.
     * Does not pollute build-time system prompt snapshot.
     */
    private String injectMemoryContext(String message, String memoryContext) {
        if (memoryContext == null || memoryContext.isBlank()) return message;
        return memoryContext + "\n\n" + message;
    }

    // ==================== 内部方法 ====================

    private BaseAgent getOrBuildAgent(Long agentId) {
        return agentInstances.computeIfAbsent(agentId, id -> {
            AgentEntity entity = getAgent(id);
            if (!Boolean.TRUE.equals(entity.getEnabled())) {
                throw new MateClawException("err.agent.disabled", "Agent 已禁用: " + entity.getName());
            }
            return agentGraphBuilder.build(entity);
        });
    }

    // ==================== StreamDelta ====================

    public record StreamDelta(String content, String thinking, String eventType, Map<String, Object> eventData,
                              boolean persistenceOnly, boolean segmentOnly) {

        // 兼容构造器（广播+持久化）
        public StreamDelta(String content, String thinking) {
            this(content, thinking, null, null, false, false);
        }

        // 显式 5-参构造器：保留旧调用点对 (content, thinking, eventType, eventData, persistenceOnly) 的兼容
        public StreamDelta(String content, String thinking, String eventType,
                           Map<String, Object> eventData, boolean persistenceOnly) {
            this(content, thinking, eventType, eventData, persistenceOnly, false);
        }

        /** 仅用于持久化，不再广播（内容已由 NodeStreamingChatHelper 实时广播过） */
        public static StreamDelta persistOnly(String content, String thinking) {
            return new StreamDelta(content, thinking, null, null, true, false);
        }

        /**
         * Per-iteration narrative routing for ReasoningNode / SummarizingNode output.
         *
         * <p>The accumulator should:
         * <ul>
         *   <li>append the text to the in-flight {@code segments} entry so the UI's
         *       segmented view still renders the intermediate "I'll look it up…"
         *       narration between tool cards;</li>
         *   <li>NOT broadcast — already broadcast live by NodeStreamingChatHelper;</li>
         *   <li>NOT append to the top-level {@code content} StringBuilder, which is
         *       what gets persisted as {@code mate_message.content}. That field
         *       should hold the final-answer span only — otherwise multiple
         *       iterations stack into "我来…让我…然后…" walls that next-turn replay
         *       sees as unanswered chain-of-thought (issue #120 narration leg).</li>
         * </ul>
         *
         * <p>Implies {@code persistenceOnly} (no broadcast) at the accumulator
         * layer, but is a stricter promise: <em>nothing</em> reaches the top-level
         * persisted content field via this flavor.
         */
        public static StreamDelta segmentOnly(String content, String thinking) {
            return new StreamDelta(content, thinking, null, null, true, true);
        }

        public static StreamDelta empty() {
            return new StreamDelta(null, null, null, null, false, false);
        }

        public static StreamDelta event(String type, Map<String, Object> data) {
            return new StreamDelta(null, null, type, data, false, false);
        }

        public boolean isEvent() {
            return eventType != null;
        }

        public boolean hasPayload() {
            return StringUtils.hasText(content) || StringUtils.hasText(thinking);
        }

        public int contentLength() {
            return content != null ? content.length() : 0;
        }

        public int thinkingLength() {
            return thinking != null ? thinking.length() : 0;
        }
    }
}
