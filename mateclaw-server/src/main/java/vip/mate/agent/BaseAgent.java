package vip.mate.agent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import vip.mate.approval.ApprovalPlaceholderUtil;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.llm.routing.model.MultimodalRoutingDecision;
import vip.mate.llm.service.ModelCapabilityService;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 抽象基类
 * 定义所有 Agent 的基础行为与状态管理
 *
 * @author MateClaw Team
 */
@Slf4j
public abstract class BaseAgent {

    protected final ChatClient chatClient;
    protected final ConversationService conversationService;
    protected final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.IDLE);

    /** Agent 唯一标识 */
    protected String agentId;

    /** Agent 名称 */
    protected String agentName;

    /** 系统提示词 */
    protected String systemPrompt;

    /**
     * Max ReAct iterations (one reasoning + action + observation step counts as one).
     * Default 100, hard ceiling 100 (enforced in AgentGraphBuilder so per-agent DB
     * overrides cannot exceed it).
     */
    public static final int MAX_ITERATIONS_HARD_CEILING = 100;
    protected int maxIterations = 100;

    /** 工作区活动目录（限制文件工具访问范围，为空不限制） */
    protected String workspaceBasePath;

    /** 模型名称 */
    protected String modelName;

    /**
     * Modalities the chat model can natively consume (resolved at agent build time
     * by {@link vip.mate.llm.service.ModelCapabilityService}). Empty set = unknown model,
     * fall back to text-only behavior. See issue #44.
     */
    protected Set<ModelCapabilityService.Modality> modelCapabilities = EnumSet.noneOf(ModelCapabilityService.Modality.class);

    /** 采样温度 */
    protected Double temperature;

    /** 最大输出 token */
    protected Integer maxTokens;

    /** 最大输入 token（上下文窗口） */
    protected Integer maxInputTokens;

    /** Top P */
    protected Double topP;

    /** 当前运行时是否启用工具调用 */
    protected boolean toolCallingEnabled = true;

    /** 构建时使用的 provider ID（运行时快照） */
    protected String runtimeProviderId;

    /**
     * Full runtime model configuration used by the multimodal router to
     * decide whether the primary model can handle attachments natively.
     * Set by {@code AgentGraphBuilder} alongside {@link #modelCapabilities}.
     */
    protected ModelConfigEntity runtimeModelConfig;

    /**
     * The agent's effective tool set. Lifted from subclasses so
     * {@link #buildUserMessage} can ask whether the agent has any media-capable
     * tool when the primary model rejects an attachment.
     */
    protected vip.mate.agent.AgentToolSet toolSet;

    /**
     * Optional sidecar routing services. Null when not wired (e.g. tests with
     * minimal builders); the routing path then degrades to the legacy
     * skip-with-text-hint behavior without any extra LLM calls.
     */
    protected MultimodalRouter multimodalRouter;
    protected MediaCaptionService mediaCaptionService;

    /** Locale used when prompting the vision sidecar. Defaults to zh-CN when unset. */
    protected java.util.Locale userLocale = java.util.Locale.SIMPLIFIED_CHINESE;


    protected BaseAgent(ChatClient chatClient, ConversationService conversationService) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
    }

    /**
     * 同步对话接口
     *
     * @param userMessage    用户消息
     * @param conversationId 会话ID
     * @return 助手回复
     */
    public abstract String chat(String userMessage, String conversationId);

    /**
     * 流式对话接口（SSE）
     *
     * @param userMessage    用户消息
     * @param conversationId 会话ID
     * @return 流式文本 Flux
     */
    public abstract Flux<String> chatStream(String userMessage, String conversationId);

    /**
     * 执行复杂任务（Plan-and-Execute 模式）
     *
     * @param goal           任务目标
     * @param conversationId 会话ID
     * @return 执行结果摘要
     */
    public abstract String execute(String goal, String conversationId);

    /**
     * 带工具重放的对话接口（审批通过后调用）
     * <p>
     * 默认实现退化为普通 chat，子类可覆盖注入 forced_tool_call。
     *
     * @param userMessage      用户消息
     * @param conversationId   会话 ID
     * @param toolCallPayload  要重放的工具调用 JSON
     * @return 助手回复
     */
    public String chatWithReplay(String userMessage, String conversationId, String toolCallPayload) {
        return chat(userMessage, conversationId);
    }

    /**
     * 带工具重放的流式对话接口（Web 端审批通过后调用）
     */
    public Flux<AgentService.StreamDelta> chatWithReplayStream(String userMessage, String conversationId,
                                                                String toolCallPayload) {
        return chatWithReplayStream(userMessage, conversationId, toolCallPayload, "");
    }

    public Flux<AgentService.StreamDelta> chatWithReplayStream(String userMessage, String conversationId,
                                                                String toolCallPayload, String requesterId) {
        if (this instanceof StructuredStreamCapable capable) {
            return capable.chatStructuredStream(userMessage, conversationId, requesterId);
        }
        return chatStream(userMessage, conversationId)
                .map(chunk -> new AgentService.StreamDelta(chunk, null));
    }

    /**
     * 获取当前 Agent 状态
     */
    public AgentState getState() {
        return state.get();
    }

    /**
     * 设置 Agent 状态
     */
    protected void setState(AgentState newState) {
        AgentState old = state.getAndSet(newState);
        log.debug("[{}] Agent state: {} -> {}", agentName, old, newState);
    }

    /**
     * 判断 Agent 是否空闲
     */
    public boolean isIdle() {
        return AgentState.IDLE.equals(state.get());
    }

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getSystemPrompt() { return systemPrompt; }

    protected ChatClient.ChatClientRequestSpec createConversationRequest(String userMessage, String conversationId) {
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(systemPrompt != null ? systemPrompt : "你是一个有帮助的AI助手。");

        List<Message> historyMessages = buildConversationHistory(conversationId, userMessage);
        if (!historyMessages.isEmpty()) {
            request = request.messages(historyMessages);
        }
        return request.user(userMessage);
    }

    protected List<Message> buildConversationHistory(String conversationId, String currentUserMessage) {
        // ===== 两阶段加载：短对话全量，长对话分页（递进式） =====
        long totalCount = conversationService.countMessages(conversationId);
        if (totalCount <= 0) {
            return List.of();
        }

        int windowSize = getEffectiveWindowSize();
        List<MessageEntity> history;

        if (totalCount <= windowSize) {
            // 短对话：全量加载（与旧逻辑一致）
            history = conversationService.listMessages(conversationId);
        } else {
            // 长对话：只加载最近 windowSize 条
            history = conversationService.listRecentMessages(conversationId, windowSize);
            log.info("[{}] Progressive load: {} of {} messages (window={})",
                    agentName, history.size(), totalCount, windowSize);
        }

        // ===== Slice from the LATEST compression boundary, not the first =====
        // A long-running conversation can accumulate several boundaries; the
        // newest one is the only relevant cut-off because every earlier
        // boundary's content is already folded into the newer summary. Walking
        // forward and breaking on the first boundary kept everything between
        // boundaries — the very redundancy compaction was supposed to remove.
        boolean boundaryFoundInWindow = false;
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageEntity msg = history.get(i);
            if ("system".equals(msg.getRole()) && isCompressionSummary(msg)) {
                history = new ArrayList<>(history.subList(i, history.size()));
                boundaryFoundInWindow = true;
                log.info("[{}] Found latest compression boundary at index {}; loading {} messages forward",
                        agentName, i, history.size());
                break;
            }
        }

        // ===== Latest boundary may live OUTSIDE the recent window =====
        // On a long conversation that compacted hours/days ago and has paged
        // fewer than `windowSize` new messages since, `listRecentMessages`
        // returns only the raw tail — the boundary sat at index 0 of the
        // original list and never made it into `history`. Without prepending
        // it, the model would forget the original goal even though we already
        // paid the LLM cost to produce a structured summary.
        if (!boundaryFoundInWindow && totalCount > windowSize) {
            try {
                MessageEntity latestBoundary = conversationService.findLatestCompressionBoundary(conversationId);
                if (latestBoundary != null) {
                    history = new ArrayList<>(history);
                    history.add(0, latestBoundary);
                    log.info("[{}] Prepended out-of-window compression boundary id={} so the model keeps the summary context",
                            agentName, latestBoundary.getId());
                }
            } catch (Exception e) {
                log.warn("[{}] findLatestCompressionBoundary failed; loading recent window without boundary: {}",
                        agentName, e.getMessage());
            }
        }

        // ===== 转换为 Spring AI Message 对象 =====
        int limit = history.size();
        if (limit > 0) {
            MessageEntity last = history.get(limit - 1);
            if ("user".equals(last.getRole()) && currentUserMessage.equals(last.getContent())) {
                limit -= 1;
            }
        }

        if (limit <= 0) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(limit);
        for (int i = 0; i < limit; i += 1) {
            messages.addAll(expandToSpringMessages(history.get(i)));
        }

        // Tail guard — orphan-user strip (issue #47).
        //
        // Invariant: every caller of buildConversationHistory appends the
        // current user message AFTER this history (BaseAgent.buildClient
        // via .user(), StateGraphReActAgent / StateGraphPlanExecuteAgent
        // via messages.add(buildCurrentUserMessage)). So the final prompt is
        //   [system, ...history, current_user]
        // and is *always* terminated by a user message. That means a trailing
        // assistant in history is FINE for every provider we support — it
        // produces the correct [..., user, assistant, current_user] alternation
        // (OpenAI, Anthropic, DeepSeek regular/thinking, Gemini, Qwen, …).
        //
        // The actual hazard is the opposite: a trailing USER in history.
        // That happens when the immediately-prior turn's assistant message
        // was dropped by Stage 1 (approval placeholder) or Stage 1.5 (errored
        // turn / "[错误] " row), or never persisted at all (turn interrupted
        // before doOnComplete saved the assistant). In that case the history
        // ends with an orphan unanswered user, and appending the current user
        // produces TWO consecutive user messages. Most providers concatenate
        // those and answer both — leaking the orphan question's answer
        // alongside the current answer. (This was the symptom reported in
        // issue #47, originally caused by a tail guard that stripped trailing
        // ASSISTANT messages instead of trailing USER ones — a direction-
        // reversed version of this loop.)
        //
        // Stripping orphan users is safe: the user re-asked or asked a new
        // question; the orphan turn produced no answer the model can build
        // on. We lose a small amount of conversational context in exchange
        // for clean alternation across every provider.
        while (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof UserMessage) {
            messages.remove(messages.size() - 1);
        }

        // Head guard — orphan tool-response strip.
        //
        // Independent of the compaction pair-safe boundary in
        // ConversationWindowManager: that one protects the *compaction* cut,
        // this one protects the *pagination* cut. listRecentMessages returns
        // the last N rows verbatim, and the first row of that page can be a
        // ToolResponseMessage whose owning AssistantMessage sat one row
        // earlier — i.e. outside the page. Sending such a sequence to any
        // OpenAI-compatible provider returns 400 because every tool response
        // must be preceded by an assistant message issuing that tool_call_id.
        //
        // The boundary prepend earlier inserts a SystemMessage at the head;
        // the orphan, if present, sits at index 1 in that case. Skip leading
        // SystemMessages and drop any leading ToolResponseMessage whose
        // response ids are not all issued by a *preceding* AssistantMessage
        // — i.e. one we have already walked past in this scan. Provider
        // validity is order-sensitive; a later same-id assistant deeper in
        // the window does NOT redeem an earlier orphan. See
        // stripHeadOrphanToolResponses below for the forward-scan details.
        //
        // Dropping is correct rather than expanding backward to fetch the
        // missing assistant: if the AssistantMessage is outside the window,
        // its content is already lost to the model anyway, and the boundary
        // summary (if any) covers it. Keeping the orphan would just trade a
        // dropped row for a 400.
        stripHeadOrphanToolResponses(messages, agentName);
        return messages;
    }

    /**
     * Drop leading {@link ToolResponseMessage}s whose owning
     * {@link AssistantMessage} sits <em>before</em> them in this list. Provider
     * validity is order-sensitive: a tool response must follow the assistant
     * that issued the tool_call_id; an unrelated later AssistantMessage that
     * happens to carry the same id does not redeem an earlier orphan.
     *
     * <p>Algorithm: forward scan with a {@code seenIssuedIds} set. Leading
     * {@link SystemMessage}s (boundary rows, system prompts) pass through
     * untouched but contribute no ids. The first {@link AssistantMessage} or
     * {@link UserMessage} we hit stops the repair walk — by that point we're
     * out of head-orphan territory. Every {@link ToolResponseMessage} we
     * encounter before that stop is checked against {@code seenIssuedIds};
     * if every response id is unseen, the message is dropped and the scan
     * re-examines the new head. A response whose ids are all in the seen
     * set (e.g. {@code [system, assistant(X), toolResponse(X), ...]} when
     * the assistant fell at index 1 of the slice) is left in place.
     *
     * <p>Mixed responses (some ids matched, some not) inside a single
     * leading {@code ToolResponseMessage} are dropped wholesale rather than
     * surgically rewritten — the provider would reject partially-broken
     * sequences anyway, and the mixed case implies an upstream invariant
     * violation that surfaces in logs.
     *
     * <p>Package-private + static so unit tests can drive it without standing
     * up a full BaseAgent subclass.
     */
    static int stripHeadOrphanToolResponses(List<Message> messages, String agentName) {
        if (messages.isEmpty()) return 0;

        // Built up as we walk; only assistants we've already passed count
        // toward "preceding". An assistant that sits behind a head orphan is
        // irrelevant: provider order-validity asks "was this tool_call id
        // issued BEFORE this response?", not "anywhere in the prompt".
        Set<String> seenIssuedIds = new HashSet<>();

        int dropped = 0;
        int i = 0;
        while (i < messages.size()) {
            Message m = messages.get(i);
            if (m instanceof SystemMessage) {
                // Boundary rows / system prompts pass through; advance and
                // keep looking for orphan tool responses that sit behind them.
                i++;
                continue;
            }
            if (m instanceof AssistantMessage am) {
                // Reached a preceding assistant — head danger is over. The
                // tool_call ids it issued are valid for any tool responses
                // that follow, but we stop the repair walk here either way.
                if (am.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        if (tc.id() != null && !tc.id().isEmpty()) {
                            seenIssuedIds.add(tc.id());
                        }
                    }
                }
                break;
            }
            if (m instanceof ToolResponseMessage trm) {
                // Every response id must have been issued by a preceding
                // assistant we already walked through. If any single id is
                // missing from seenIssuedIds, the message is invalid in
                // place. Empty / null ids don't count for or against.
                boolean anyUnmatched = trm.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::id)
                        .filter(id -> id != null && !id.isEmpty())
                        .anyMatch(id -> !seenIssuedIds.contains(id));
                if (anyUnmatched) {
                    messages.remove(i);
                    dropped++;
                    continue; // re-examine the new messages[i]
                }
                // All ids match a preceding assistant — keep, and stop the
                // repair walk. Anything past here is well-formed by
                // construction (provider validates each subsequent pair as
                // we go).
                break;
            }
            // UserMessage (or anything else) — past the head danger. Stop.
            break;
        }
        if (dropped > 0) {
            log.info("[{}] Stripped {} leading orphan ToolResponseMessage(s) — no preceding AssistantMessage in scope",
                    agentName, dropped);
        }
        return dropped;
    }

    /**
     * History sanitization entry point. Encapsulates *all* steps applied to a
     * persisted message before it reaches an LLM prompt. Returns {@code null}
     * to drop the message, or a Spring AI {@link Message} (possibly with
     * rewritten content) to keep it.
     *
     * <p>Design philosophy (OpenClaw-inspired): keep the conversion + every
     * sanitization stage centralized here so future steps (RFC-052 §9 PII
     * field-level redaction, RFC-049 thinking-block replay strategy, image
     * compression for vision models, etc.) plug in as additional inline
     * stages with clear ordering rather than scattering across the loop.
     *
     * <p>Current stages (in order):
     * <ol>
     *   <li><b>Drop approval placeholders</b> — assistant messages whose
     *       content is a "[等待审批]" stub from the approval flow are removed
     *       entirely so they don't pollute the LLM context.</li>
     *   <li><b>Render content</b> — convert {@code MessageEntity} to a string
     *       via {@link ConversationService#renderMessageContent}.</li>
     *   <li><b>Direct-tool scrub (RFC-052)</b> — assistant messages produced
     *       by a returnDirect tool path get their content replaced with a
     *       tool-named placeholder; the original DB content is unchanged.</li>
     *   <li><b>Type dispatch</b> — wrap into {@code AssistantMessage},
     *       {@code SystemMessage}, or {@code UserMessage} (with multimodal
     *       Media for image/video parts).</li>
     * </ol>
     */
    private Message sanitizeForLlm(MessageEntity entity) {
        if (entity == null) {
            return null;
        }

        // Stage 0: drop cron-run header rows (system role + "📋 " prefix)
        // inserted by CronJobLifecycleService.startRun. These are UI dividers
        // for the unified tasks_<wsId> view and the IM channel-session
        // mirror — they carry no semantic context for the LLM. Without this
        // skip, every subsequent IM turn would feed the model unsolicited
        // SystemMessage rows like "📋 每日新闻 · 定时触发 · 2026-04-30T10:55"
        // and bloat the prompt with scheduler metadata.
        if ("system".equals(entity.getRole())
                && entity.getContent() != null
                && entity.getContent().startsWith("📋 ")) {
            return null;
        }

        // Stage 1: drop approval-placeholder assistant messages
        if ("assistant".equals(entity.getRole()) && isApprovalPlaceholder(entity.getContent())) {
            log.debug("[{}] Filtering approval placeholder from history: msgId={}",
                    agentName, entity.getId());
            return null;
        }

        // Stage 1.5: drop typed-error assistant messages. These are persisted
        // by ChatController.doOnComplete with status='error' (or carry the
        // "[错误] " prefix injected by NodeStreamingChatHelper for legacy
        // rows). Re-sending them as multi-turn context drives a self-replicating
        // failure loop:
        //   - DeepSeek thinking mode → 400 "reasoning_content must be passed back"
        //     (we never captured a real reasoning_content for the failed turn)
        //   - Anthropic Claude → 400 "does not support assistant message prefill"
        //     (the trailing-user-dedup at the call site can leave an assistant
        //      tail when the prior turn errored)
        // Both providers' 400 then re-persist a fresh "[错误] " row, repeat.
        if ("assistant".equals(entity.getRole())
                && ("error".equals(entity.getStatus())
                        || (entity.getContent() != null && entity.getContent().startsWith("[错误] ")))) {
            log.debug("[{}] Filtering error assistant message from history: msgId={} status={}",
                    agentName, entity.getId(), entity.getStatus());
            return null;
        }

        // Delegate stages 2-4 to toSpringMessage; the stage 3 scrub is applied
        // there so the rendered content is replaced before the typed Message
        // wrapper is constructed.
        return toSpringMessage(entity);
    }

    /**
     * Replay a persisted message as 1..N Spring AI {@link Message}s.
     *
     * <p>Plain user/system/assistant rows expand to a single message via
     * {@link #sanitizeForLlm}. Assistant rows that issued tool calls during
     * the original turn expand to TWO messages:
     * <ol>
     *   <li>{@link AssistantMessage} carrying the persisted narration <em>and</em>
     *       the {@link AssistantMessage.ToolCall} list reconstructed from
     *       {@code metadata.toolCalls}.</li>
     *   <li>A single {@link ToolResponseMessage} bundling one
     *       {@link ToolResponseMessage.ToolResponse} per completed tool call,
     *       with the persisted {@code result} string as content.</li>
     * </ol>
     *
     * <p>Without this expansion the next turn would see a bare assistant text
     * row containing chain-of-thought like "Let me try the browser..." but no
     * tool calls and no observations. The LLM concludes the action wasn't
     * actually performed and retries the same tool, looping until the iteration
     * cap. Issuing the structured tool_call + tool_response pair lets the model
     * see what already ran and reason from the result instead.
     *
     * <p>Only completed tool calls (status=completed AND result present) are
     * replayed. Calls left in awaiting_approval or running state are dropped
     * — replaying them without a paired response would produce a sequence the
     * provider rejects (every tool_call_id must have a matching tool_response).
     *
     * <p>RFC-052 direct-tool rows take the existing scrub path unchanged; the
     * placeholder text already names the originating tool and instructs the
     * model to re-call if needed, which is the correct multi-turn signal for
     * returnDirect tools.
     */
    List<Message> expandToSpringMessages(MessageEntity entity) {
        if (entity == null) return List.of();
        // Stages 0/1/1.5 in sanitizeForLlm drop cron-header system rows,
        // approval-placeholder assistants, and error-status assistants. Those
        // rows must NOT replay structured tool exchanges even if metadata still
        // carries them — the underlying turn is broken or synthetic. Centralize
        // that decision so we can apply it before the empty-content check.
        if (shouldFullyFilter(entity)) return List.of();

        // Non-assistant rows expand to exactly one message via the existing
        // conversion path (which also handles user-message media injection).
        if (!"assistant".equals(entity.getRole())) {
            Message m = toSpringMessage(entity);
            return m == null ? List.of() : List.of(m);
        }

        // RFC-052 direct-tool rows: keep the existing placeholder-only path.
        // The placeholder names the originating tool and tells the model to
        // re-call it; resurrecting the original tool_call/tool_response pair
        // would leak the very content RFC-052 elides.
        if (!directToolNamesIn(entity).isEmpty()) {
            Message m = toSpringMessage(entity);
            return m == null ? List.of() : List.of(m);
        }

        String renderedContent = conversationService.renderMessageContent(entity);
        List<PersistedToolCall> persisted = extractCompletedToolCalls(entity);

        // No tool calls to replay → fall back to the legacy single-message
        // path, which preserves the "drop on blank content" behavior for
        // narration-only assistants.
        if (persisted.isEmpty()) {
            Message m = toSpringMessage(entity);
            return m == null ? List.of() : List.of(m);
        }

        // Have completed tool calls → emit the structured pair regardless of
        // whether the rendered content is blank. A pure tool-call turn (LLM
        // returned tool_calls with no preamble text) is the canonical case
        // here: previously the row was dropped entirely because
        // renderMessageContent collapsed to "" once the tool_call part was
        // skipped, leaving the next turn with no record that the tools ran.
        return buildToolExchange(entity, renderedContent == null ? "" : renderedContent, persisted);
    }

    /**
     * True when the persisted row must be dropped before any history reaches
     * the LLM, irrespective of its tool-call payload. Mirrors stages 0/1/1.5
     * of {@link #sanitizeForLlm}: cron-run header system rows, approval
     * placeholders, and error-status / "[错误] " assistants. Replaying tool
     * exchanges from these would resurface UI scaffolding or self-replicate
     * provider failures.
     */
    static boolean shouldFullyFilter(MessageEntity entity) {
        if (entity == null) return true;
        String role = entity.getRole();
        if ("system".equals(role)
                && entity.getContent() != null
                && entity.getContent().startsWith("📋 ")) return true;
        if ("assistant".equals(role)
                && isApprovalPlaceholder(entity.getContent())) return true;
        if ("assistant".equals(role)
                && ("error".equals(entity.getStatus())
                        || (entity.getContent() != null
                                && entity.getContent().startsWith("[错误] ")))) return true;
        return false;
    }

    /**
     * Build the structured {@code [AssistantMessage(toolCalls), ToolResponseMessage]}
     * pair from a persisted row. Pure: depends only on its arguments, so it
     * can be exercised directly from unit tests without a BaseAgent fixture.
     *
     * <p>Both sides reuse the same id for each call so the in-prompt sequence
     * validates with every provider's tool_call_id pairing rule. For legacy
     * rows persisted before {@code toolCallId} was captured, synthesize a
     * stable id from {@code entity.id + index}. The id never escapes this
     * prompt; synthetic and real ids cannot collide downstream.
     */
    static List<Message> buildToolExchange(MessageEntity entity, String content,
                                            List<PersistedToolCall> persisted) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(persisted.size());
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(persisted.size());
        for (int i = 0; i < persisted.size(); i++) {
            PersistedToolCall p = persisted.get(i);
            String id = (p.toolCallId() == null || p.toolCallId().isEmpty())
                    ? "legacy-" + entity.getId() + "-" + i
                    : p.toolCallId();
            toolCalls.add(new AssistantMessage.ToolCall(id, "function", p.name(), p.arguments()));
            responses.add(new ToolResponseMessage.ToolResponse(id, p.name(), p.result()));
        }
        AssistantMessage rebuilt = AssistantMessage.builder()
                .content(content == null ? "" : content)
                .toolCalls(toolCalls)
                .build();
        ToolResponseMessage toolResponses = ToolResponseMessage.builder()
                .responses(responses)
                .build();
        return List.of(rebuilt, toolResponses);
    }

    /**
     * Parse {@code metadata.toolCalls} into a list of completed entries that
     * are safe to replay. Returns empty if metadata is missing, malformed, or
     * carries no entry with both {@code status='completed'} and a non-null
     * {@code result}.
     *
     * <p>Handles H2's JSON-column double-wrap (the column read can produce a
     * JSON-encoded string of JSON) the same way
     * {@code ConversationService#reconcileResolvedMessages} does.
     */
    static List<PersistedToolCall> extractCompletedToolCalls(MessageEntity entity) {
        if (entity == null) return List.of();
        String raw = entity.getMetadata();
        if (raw == null || raw.isBlank() || !raw.contains("toolCalls")) return List.of();
        try {
            String json = raw.trim();
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = HISTORY_METADATA_MAPPER.readValue(json, String.class);
            }
            Map<String, Object> meta = HISTORY_METADATA_MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object tc = meta.get("toolCalls");
            if (!(tc instanceof List<?> list)) return List.of();
            List<PersistedToolCall> result = new ArrayList<>(list.size());
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> raw2)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> call = (Map<String, Object>) raw2;
                if (!"completed".equals(String.valueOf(call.get("status")))) continue;
                Object resultField = call.get("result");
                if (resultField == null) continue;
                String name = String.valueOf(call.getOrDefault("name", ""));
                if (name.isBlank()) continue;
                String args = String.valueOf(call.getOrDefault("arguments", ""));
                String toolCallId = String.valueOf(call.getOrDefault("toolCallId", ""));
                result.add(new PersistedToolCall(toolCallId, name, args, String.valueOf(resultField)));
            }
            return result;
        } catch (Exception e) {
            log.warn("[BaseAgent] Failed to parse metadata.toolCalls for replay (msgId={}): {}",
                    entity.getId(), e.getMessage());
            return List.of();
        }
    }

    private static final ObjectMapper HISTORY_METADATA_MAPPER = new ObjectMapper();

    /**
     * A completed tool call recovered from {@code mate_message.metadata}, ready
     * to be replayed as an {@link AssistantMessage.ToolCall} / matching
     * {@link ToolResponseMessage.ToolResponse} pair.
     */
    record PersistedToolCall(String toolCallId, String name, String arguments, String result) {}

    /**
     * 判断消息是否为持久化的压缩摘要。
     */
    private boolean isCompressionSummary(MessageEntity msg) {
        return msg.getMetadata() != null && msg.getMetadata().contains("compression_summary");
    }

    /**
     * 动态计算窗口大小：基于模型上下文长度估算能容纳多少条消息。
     * 保守估算：每条消息平均 200 token，预留 30% 给系统提示词和当前消息。
     */
    private int getEffectiveWindowSize() {
        int contextTokens = maxInputTokens != null && maxInputTokens > 0
                ? maxInputTokens : 128000;
        int window = (int) (contextTokens * 0.7) / 200;
        return Math.max(20, Math.min(window, 500));
    }

    /**
     * 判断是否为审批占位消息（委托给共享工具类）
     */
    static boolean isApprovalPlaceholder(String content) {
        return ApprovalPlaceholderUtil.isApprovalPlaceholder(content);
    }

    /**
     * RFC-052: regex matching {@code "directToolNames":["a","b",...]} in the
     * metadata JSON and capturing every tool name in group(1) iterations. The
     * {@code \\s*} guards keep us robust to pretty-printed JSON.
     *
     * <p>Design note (OpenClaw-inspired): rather than a one-shot "is this a
     * direct turn?" boolean we extract the actual tool names and weave them
     * into the placeholder, so the next LLM turn can reason about *which* tool
     * answered (e.g. "the user just asked their salary; you used
     * query_employee_salary; if they ask follow-up questions, call it again").
     * This preserves conversational continuity that a generic placeholder
     * destroys.
     */
    private static final java.util.regex.Pattern DIRECT_TOOL_NAMES_ARRAY =
            java.util.regex.Pattern.compile(
                    "\"directToolNames\"\\s*:\\s*\\[(\\s*\"[^\"]*\"\\s*(?:,\\s*\"[^\"]*\"\\s*)*)\\]");
    private static final java.util.regex.Pattern DIRECT_TOOL_NAMES_INNER =
            java.util.regex.Pattern.compile("\"([^\"]+)\"");

    /**
     * RFC-052: returns the list of returnDirect tool names recorded in the
     * persisted assistant message's metadata. Empty list means this is NOT a
     * direct-tool message and the content is safe for the LLM.
     *
     * <p>Allocates only when a non-empty {@code directToolNames} array is
     * actually present (the common case — normal assistant turns — exits at
     * the first {@code contains} check with zero allocations).
     */
    static List<String> directToolNamesIn(MessageEntity msg) {
        if (msg == null) return List.of();
        String metadata = msg.getMetadata();
        if (metadata == null || metadata.isEmpty()) return List.of();
        if (!metadata.contains("\"directToolNames\"")) return List.of();
        java.util.regex.Matcher arrayMatcher = DIRECT_TOOL_NAMES_ARRAY.matcher(metadata);
        if (!arrayMatcher.find()) return List.of();
        String inner = arrayMatcher.group(1);
        java.util.regex.Matcher nameMatcher = DIRECT_TOOL_NAMES_INNER.matcher(inner);
        List<String> names = new ArrayList<>(2);
        while (nameMatcher.find()) {
            names.add(nameMatcher.group(1));
        }
        return names;
    }

    /**
     * Convenience wrapper preserved for callers that only need the boolean.
     * Keeps the original test surface stable.
     */
    static boolean isDirectToolMessage(MessageEntity msg) {
        return !directToolNamesIn(msg).isEmpty();
    }

    /**
     * RFC-052: build the placeholder text used to replace a direct-tool
     * assistant message in next-turn prompts. Includes the originating tool
     * names so the model retains conversational structure (it knows *why*
     * the content is redacted and *which* tool would re-fetch it). The
     * original message stays unchanged in {@code mate_message.content}.
     *
     * <p>Worded as a neutral status line, not as a faux assistant utterance —
     * the model treats it as a system-level note, not as previous output to
     * be continued.
     */
    static String directToolHistoryPlaceholder(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "[Previous answer was tool data returned directly to the user. " +
                    "Content withheld from model context per tool policy.]";
        }
        String joined = toolNames.size() == 1
                ? "'" + toolNames.get(0) + "'"
                : toolNames.stream()
                        .map(n -> "'" + n + "'")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
        return "[Previous turn used direct-return tool(s) " + joined + " to deliver " +
                "data straight to the user. Content withheld from model context per tool " +
                "policy. If the user asks a follow-up that requires that data, call the " +
                "tool again.]";
    }

    private Message toSpringMessage(MessageEntity message) {
        if (message == null) {
            return null;
        }
        String renderedContent = conversationService.renderMessageContent(message);
        if (renderedContent == null || renderedContent.isBlank()) {
            return null;
        }
        // RFC-052: scrub direct-tool content from any subsequent LLM prompt.
        // The DB content stays unchanged; only the in-memory Message handed to
        // the model gets replaced. This is MateClaw's persistence-aware analog
        // of joyagent-jdgenie's Memory.clearToolContext (purely in-memory) and
        // OpenClaw's stripToolResultDetails (structural strip per replay).
        //
        // Unlike a generic "withheld" placeholder, we name the originating
        // tool(s) so the model retains the dialog structure: it knows what
        // kind of data was withheld and which tool would fetch it again. This
        // preserves multi-turn coherence without leaking the payload itself.
        if ("assistant".equals(message.getRole())) {
            List<String> directNames = directToolNamesIn(message);
            if (!directNames.isEmpty()) {
                log.debug("[{}] Scrubbing direct-tool content from history msgId={} tools={} (RFC-052)",
                        agentName, message.getId(), directNames);
                renderedContent = directToolHistoryPlaceholder(directNames);
            }
        }
        return switch (message.getRole()) {
            case "assistant" -> new AssistantMessage(renderedContent);
            case "system" -> new SystemMessage(renderedContent);
            // History user messages: text only. Re-injecting Media on every replay
            // accumulates attachments across turns — many providers cap at 1 video
            // per request (e.g. Zhipu GLM-5V returns code 1210). The current turn
            // gets Media via buildCurrentUserMessage, which is the only path that
            // should send raw bytes to the model.
            case "user" -> buildUserMessage(message, renderedContent, false);
            default -> null;
        };
    }

    private static final long MAX_VIDEO_SIZE_BYTES = 20 * 1024 * 1024; // 20MB

    /**
     * 判断当前模型是否支持视频输入。
     * 由 {@link ModelCapabilityService} 在 agent 构建时解析并注入到
     * {@link #modelCapabilities}，per-model 粒度（区分如 glm-4v vs glm-4v-plus）。
     */
    private boolean modelSupportsVideo() {
        return modelCapabilities.contains(ModelCapabilityService.Modality.VIDEO);
    }

    private boolean modelSupportsVision() {
        return modelCapabilities.contains(ModelCapabilityService.Modality.VISION);
    }

    /**
     * Build a {@link UserMessage} for the current turn, including any image/video
     * media the agent's primary model can handle natively. Returns the message
     * paired with the {@link MultimodalRoutingDecision} taken so the caller can
     * persist it as message metadata and emit a routing event.
     */
    protected CurrentTurnUserMessage buildUserMessageForCurrentTurn(MessageEntity message, String renderedContent) {
        return buildUserMessageInternal(message, renderedContent, true);
    }

    /**
     * History-replay variant: text-only, no media reinjected, no routing decision.
     * Many providers cap at one video per request, so re-injecting old attachments
     * on every replay would break the call.
     */
    protected UserMessage buildUserMessage(MessageEntity message, String renderedContent) {
        return buildUserMessageInternal(message, renderedContent, true).userMessage();
    }

    protected UserMessage buildUserMessage(MessageEntity message, String renderedContent, boolean injectMedia) {
        return buildUserMessageInternal(message, renderedContent, injectMedia).userMessage();
    }

    private CurrentTurnUserMessage buildUserMessageInternal(MessageEntity message, String renderedContent, boolean injectMedia) {
        if (!injectMedia) {
            return new CurrentTurnUserMessage(
                    new UserMessage(renderedContent == null ? "" : renderedContent),
                    null);
        }
        List<MessageContentPart> parts = conversationService.parseMessageParts(message);

        // Sidecar routing — runs first so caption text gets folded into finalText
        // before native media injection considers the same parts again.
        MultimodalRoutingDecision decision = multimodalRouter != null
                ? multimodalRouter.route(parts, runtimeModelConfig)
                : MultimodalRoutingDecision.none();

        StringBuilder textBuilder = new StringBuilder(renderedContent == null ? "" : renderedContent);
        java.util.Set<String> sidecarHandledIdentifiers = new java.util.HashSet<>();
        if (decision.strategy() == MultimodalRoutingDecision.Strategy.SIDECAR
                && mediaCaptionService != null
                && decision.sidecarModel() != null) {
            for (MessageContentPart part : parts) {
                if (part == null) continue;
                String contentType = part.getContentType();
                boolean isImage = ("image".equals(part.getType()) || "file".equals(part.getType()))
                        && contentType != null && contentType.startsWith("image/")
                        && !contentType.contains("svg");
                if (!isImage) continue;
                MediaCaptionService.CaptionResult result = mediaCaptionService.caption(
                        decision.sidecarModel(), part, userLocale);
                if (result.isFailure()) {
                    log.warn("[{}] Sidecar caption failed for {}: {}",
                            agentName, part.getFileName(), result.failure().getMessage());
                    textBuilder.append("\n\n[系统提示] 视觉模型未能解析附件 ")
                            .append(part.getFileName())
                            .append("，请稍后重试或在「设置 → 模型」检查视觉模型配置。");
                    continue;
                }
                textBuilder.append("\n\n[图片附件描述: ")
                        .append(part.getFileName() == null ? "image" : part.getFileName())
                        .append("]\n")
                        .append(result.description())
                        .append("\n[/图片附件描述]");
                String identifier = identifyPart(part);
                if (identifier != null) sidecarHandledIdentifiers.add(identifier);
            }
        }

        List<Media> mediaList = new ArrayList<>();
        List<String> skippedAttachments = new ArrayList<>();
        boolean videoSupported = modelSupportsVideo();
        boolean visionSupported = modelSupportsVision();

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            // Sidecar already produced text for this image; never inject the
            // raw bytes — the primary model would receive them and try to
            // process natively, defeating the cost-saving purpose.
            String identifier = identifyPart(part);
            if (identifier != null && sidecarHandledIdentifiers.contains(identifier)) continue;

            String partType = part.getType();
            String contentType = part.getContentType();
            // image 类型的 part 可能没有精确 contentType，补全为 image/jpeg
            if ("image".equals(partType) && (contentType == null || "image/*".equals(contentType))) {
                contentType = "image/jpeg";
            }
            if (contentType == null) continue;

            boolean isImage = ("image".equals(partType) || "file".equals(partType)) && contentType.startsWith("image/");
            boolean isVideo = ("video".equals(partType) || "file".equals(partType)) && contentType.startsWith("video/");

            if (!isImage && !isVideo) continue;

            // SVG 是 XML 文本，不是光栅图片，LLM multimodal API 不支持
            if (isImage && contentType.contains("svg")) {
                log.debug("[{}] Skipping SVG attachment (not supported by multimodal API): {}",
                        agentName, part.getFileName());
                skippedAttachments.add(part.getFileName() + "(SVG 格式，多模态 API 不支持)");
                continue;
            }

            // 图片仅在模型支持视觉时注入；纯文本模型（如 GLM-5-Turbo / DeepSeek-V3）会被跳过
            if (isImage && !visionSupported) {
                log.debug("[{}] Skipping image attachment (model '{}' does not support vision): {}",
                        agentName, modelName, part.getFileName());
                skippedAttachments.add(part.getFileName() + "(当前模型 " + modelName + " 不支持图片输入)");
                continue;
            }

            // 视频仅在模型支持时注入，否则跳过（避免发送给非视觉模型导致 400 错误）
            if (isVideo && !videoSupported) {
                log.debug("[{}] Skipping video attachment (model '{}' does not support video): {}",
                        agentName, modelName, part.getFileName());
                skippedAttachments.add(part.getFileName() + "(当前模型 " + modelName + " 不支持视频输入)");
                continue;
            }

            // 视频文件大小保护
            if (isVideo && part.getFileSize() != null && part.getFileSize() > MAX_VIDEO_SIZE_BYTES) {
                log.warn("[{}] Skipping oversized video attachment ({}MB > 20MB): {}",
                        agentName, part.getFileSize() / (1024 * 1024), part.getFileName());
                skippedAttachments.add(part.getFileName() + "(视频超过 20MB 大小限制)");
                continue;
            }

            // 解析媒体文件路径：先尝试 path，再尝试 mediaId（IM 渠道下载后存在 mediaId 中），再拼接工作目录
            Path mediaPath = resolveImagePath(part.getPath());
            if (mediaPath == null && part.getMediaId() != null) {
                mediaPath = resolveImagePath(part.getMediaId());
            }
            if (mediaPath == null) {
                log.warn("[{}] {} file not found for attachment: {}, path: {}, mediaId: {}",
                        agentName, isVideo ? "Video" : "Image", part.getFileName(), part.getPath(), part.getMediaId());
                skippedAttachments.add(part.getFileName() + "(文件未找到)");
                continue;
            }
            try {
                MimeType mimeType = MimeType.valueOf(contentType);
                Media media = new Media(mimeType, new FileSystemResource(mediaPath));
                mediaList.add(media);
                log.debug("[{}] Injected {} into prompt: {} ({})",
                        agentName, isVideo ? "video" : "image", part.getFileName(), mediaPath);
            } catch (Exception e) {
                log.warn("[{}] Failed to create Media for {} {}: {}",
                        agentName, isVideo ? "video" : "image", part.getFileName(), e.getMessage());
                skippedAttachments.add(part.getFileName() + "(媒体加载失败)");
            }
        }

        if (!skippedAttachments.isEmpty()) {
            textBuilder.append("\n\n[系统提示] 以下附件未能传入当前模型：")
                    .append(String.join("、", skippedAttachments))
                    .append("。");
            // Only suggest switching models when no media-capable tool is bound
            // either. With a media tool the LLM may legitimately choose to
            // delegate to the tool — never instruct it not to use tools.
            if (!hasMediaCapableTools()) {
                textBuilder.append("\n请用对话语言清晰、友好地告诉用户：当前模型无法处理这类附件，建议切换到具备相应能力的多模态模型，或在「设置 → 模型」中配置视觉/视频模型作为旁路。");
            }
        }

        String finalText = textBuilder.toString();
        UserMessage built = mediaList.isEmpty()
                ? new UserMessage(finalText)
                : UserMessage.builder().text(finalText).media(mediaList).build();
        return new CurrentTurnUserMessage(built, decision);
    }

    /**
     * Stable identifier for de-duplicating parts already handled by the sidecar
     * pass. Falls back across {@code path → mediaId → fileName} since not every
     * channel populates the same field.
     */
    private static String identifyPart(MessageContentPart part) {
        if (part == null) return null;
        if (part.getPath() != null && !part.getPath().isBlank()) return "p:" + part.getPath();
        if (part.getMediaId() != null && !part.getMediaId().isBlank()) return "m:" + part.getMediaId();
        if (part.getFileName() != null && !part.getFileName().isBlank()) return "f:" + part.getFileName();
        return null;
    }

    /**
     * True if the agent has at least one tool whose name or description
     * suggests it can read images / video / audio. The check is intentionally
     * loose — false positives just mean the agent is allowed to attempt media
     * processing on its own, which is the safer default.
     */
    private static final Set<String> MEDIA_TOOL_KEYWORDS = Set.of(
            "image", "图片", "vision", "视觉",
            "video", "视频", "ffmpeg",
            "ocr", "caption", "media", "audio", "音频");

    private boolean hasMediaCapableTools() {
        if (toolSet == null) return false;
        var callbacks = toolSet.callbacks();
        if (callbacks == null || callbacks.isEmpty()) return false;
        return callbacks.stream().anyMatch(cb -> {
            try {
                String name = String.valueOf(cb.getToolDefinition().name()).toLowerCase();
                String desc = String.valueOf(cb.getToolDefinition().description()).toLowerCase();
                return MEDIA_TOOL_KEYWORDS.stream().anyMatch(k -> name.contains(k) || desc.contains(k));
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Pair returned from the current-turn user message build path: the assembled
     * {@link UserMessage} and the routing decision the caller should persist as
     * {@code metadata.routing} and surface to the SSE consumer.
     */
    public record CurrentTurnUserMessage(UserMessage userMessage, MultimodalRoutingDecision routingDecision) {}

    /**
     * Extract a routing-decision payload from the graph input map (placed there
     * by {@code buildInitialState}) and turn it into a startup
     * {@link vip.mate.agent.AgentService.StreamDelta} the SSE accumulator can
     * persist. Returns an empty Flux when no routing happened this turn so we
     * don't emit zero-value events.
     */
    @SuppressWarnings("unchecked")
    public static reactor.core.publisher.Flux<vip.mate.agent.AgentService.StreamDelta> routingStartupDelta(
            java.util.Map<String, Object> inputs) {
        Object decision = inputs.get(vip.mate.agent.graph.state.MateClawStateKeys.ROUTING_DECISION);
        if (decision instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
            return reactor.core.publisher.Flux.just(vip.mate.agent.AgentService.StreamDelta.event(
                    vip.mate.agent.GraphEventPublisher.EVENT_ROUTING_DECISION,
                    (java.util.Map<String, Object>) map));
        }
        return reactor.core.publisher.Flux.empty();
    }

    /**
     * 解析图片文件的绝对路径。
     * <p>
     * 上传文件存储在 data/chat-uploads/ 下，是相对于 Spring Boot 工作目录的路径。
     * MCP 工具的工作目录可能不同，所以这里直接解析为绝对路径。
     */
    /**
     * 构建当前用户消息的 UserMessage（含 multimodal 图片注入）。
     * <p>
     * 从 DB 读取最后一条 user 消息的 contentParts，提取图片附件并注入 Media。
     * 不依赖文本相等匹配（避免重复文本误绑定到错误轮次），而是直接取最后一条 user 消息，
     * 因为 buildInitialState 在 saveMessage 之后调用，最后一条 user 消息就是当前消息。
     *
     * @param conversationId 会话 ID
     * @param userMessageText 用户消息文本（作为 fallback 内容）
     * @return 带图片 Media 的 UserMessage（如果有图片附件），否则纯文本 UserMessage
     */
    protected UserMessage buildCurrentUserMessage(String conversationId, String userMessageText) {
        return buildCurrentUserMessageWithRouting(conversationId, userMessageText).userMessage();
    }

    /**
     * Same as {@link #buildCurrentUserMessage} but also returns the multimodal
     * routing decision taken for this turn so the caller can persist it as
     * {@code metadata.routing} and emit a SSE-side event for the chat UI.
     * Returns a decision with NONE strategy when the message has no attachments
     * the primary model can't already handle.
     */
    protected CurrentTurnUserMessage buildCurrentUserMessageWithRouting(String conversationId, String userMessageText) {
        try {
            List<MessageEntity> history = conversationService.listMessages(conversationId);
            // 倒序取最后一条 user 消息（buildInitialState 在 saveMessage 后调用，所以最后一条就是当前消息）
            for (int i = history.size() - 1; i >= 0; i--) {
                MessageEntity msg = history.get(i);
                if ("user".equals(msg.getRole())) {
                    // 用 DB 中的实际内容（可能包含 contentParts），不用传入的 text
                    String content = conversationService.renderMessageContent(msg);
                    return buildUserMessageForCurrentTurn(msg, content != null && !content.isBlank() ? content : userMessageText);
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to load current user message parts for multimodal: {}",
                    agentName, e.getMessage());
        }
        return new CurrentTurnUserMessage(new UserMessage(userMessageText), null);
    }

    protected Path resolveImagePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        // 1. 如果已经是绝对路径且存在，直接用
        Path path = Paths.get(relativePath);
        if (path.isAbsolute() && Files.exists(path)) {
            return path;
        }
        // 2. 相对于 Spring Boot 工作目录解析
        Path resolved = Paths.get(System.getProperty("user.dir")).resolve(relativePath);
        if (Files.exists(resolved)) {
            return resolved;
        }
        // 3. 都找不到
        log.debug("[{}] Image path not found: tried {} and {}", agentName, path, resolved);
        return null;
    }

}
