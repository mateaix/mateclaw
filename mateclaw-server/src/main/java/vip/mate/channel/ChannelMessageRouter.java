package vip.mate.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.ResolveOutcome;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.event.ChannelMessageReceivedEvent;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.tts.TtsService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 渠道消息路由器
 * <p>
 * 采用每渠道独立队列架构：
 * - 每渠道一个 BlockingQueue，N 个消费线程从队列取消息处理
 * - 会话级锁保证同一 conversationId 串行处理
 * - 500ms 防抖：同一会话的连续消息合并为一条
 * - Web 渠道不走队列（有自己的 SSE 流程）
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class ChannelMessageRouter {

    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ChannelService channelService;
    private final ChannelSessionStore channelSessionStore;
    private final ApprovalWorkflowService approvalService;
    private final ApprovalNotificationService approvalNotificationService;
    private final ConversationCompletionPublisher completionPublisher;
    private final TtsService ttsService;
    private final ObjectMapper objectMapper;
    private final ChatStreamTracker streamTracker;
    private final ChannelChatOriginFactory chatOriginFactory;
    private final ChannelErrorClassifier errorClassifier;
    /** Field-injected (rather than constructor) to avoid a signature
     *  change that would ripple through every test that constructs the
     *  router directly. Spring's stock publisher is always available. */
    @Autowired(required = false)
    private ApplicationEventPublisher events;

    /** 队列条目：封装消息及其路由上下文 */
    private record QueueEntry(ChannelMessage message, ChannelAdapter adapter, ChannelEntity channelEntity) {}

    /** 每个渠道类型的消息队列 */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<QueueEntry>> channelQueues = new ConcurrentHashMap<>();

    /** 每个渠道类型的消费线程池 */
    private final ConcurrentHashMap<String, ExecutorService> channelExecutors = new ConcurrentHashMap<>();

    /** 会话级别的锁：保证同一 conversationId 串行处理 */
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /** 防抖调度器 */
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "channel-debounce-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** 防抖缓冲区：conversationId -> 待合并消息 */
    private final ConcurrentHashMap<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();

    /** 每个渠道的消费线程数 */
    private static final int CONSUMERS_PER_CHANNEL = 4;

    /** 每个渠道的队列容量 */
    private static final int QUEUE_CAPACITY = 1000;

    /** 防抖等待时间（毫秒） */
    private static final long DEBOUNCE_MS = 500;

    /**
     * Plan-Execute SSE events that the Web Console mirror needs to see when
     * a conversation runs through an IM channel.
     * <p>
     * The agent emits these via {@code GraphEventPublisher} and they ride on
     * the {@code chatStructuredStream} Flux as {@code StreamDelta.event(...)}.
     * Web direct chats already broadcast them via the ChatController
     * accumulator. IM channels (DingTalk + the seven sync-path adapters)
     * historically dropped them — DingTalk's {@code processStreamAsText}
     * only consumes {@code delta.content()}, and the sync {@code chat()}
     * collector explicitly filters {@code delta.isEvent()} out. The whitelist
     * is applied in the IM stream path so PlanStepsPanel renders correctly
     * when an operator monitors an IM conversation in the Web Console.
     * <p>
     * Whitelist (not pass-through) so Web-side accumulator-internal events
     * like {@code _usage_final} or future agent-internal markers don't leak
     * to subscribers.
     */
    private static final Set<String> MIRRORED_PLAN_EVENTS = Set.of(
            "plan_created",
            "plan_step_started",
            "plan_step_completed"
    );

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    public ChannelMessageRouter(AgentService agentService,
                                ConversationService conversationService,
                                ChannelService channelService,
                                ChannelSessionStore channelSessionStore,
                                ApprovalWorkflowService approvalService,
                                ApprovalNotificationService approvalNotificationService,
                                ConversationCompletionPublisher completionPublisher,
                                TtsService ttsService,
                                ObjectMapper objectMapper,
                                ChatStreamTracker streamTracker,
                                ChannelChatOriginFactory chatOriginFactory,
                                ChannelErrorClassifier errorClassifier) {
        this.agentService = agentService;
        this.conversationService = conversationService;
        this.channelService = channelService;
        this.channelSessionStore = channelSessionStore;
        this.approvalService = approvalService;
        this.approvalNotificationService = approvalNotificationService;
        this.completionPublisher = completionPublisher;
        this.ttsService = ttsService;
        this.objectMapper = objectMapper;
        this.streamTracker = streamTracker;
        this.chatOriginFactory = chatOriginFactory;
        this.errorClassifier = errorClassifier;
    }

    // ==================== 防抖辅助类 ====================

    /**
     * 防抖待合并消息
     */
    private static class PendingMessage {
        final ChannelAdapter adapter;
        final ChannelEntity channelEntity;
        final ChannelMessage firstMessage;
        final StringBuilder mergedContent;
        volatile ScheduledFuture<?> timer;

        PendingMessage(ChannelMessage message, ChannelAdapter adapter, ChannelEntity channelEntity) {
            this.firstMessage = message;
            this.adapter = adapter;
            this.channelEntity = channelEntity;
            this.mergedContent = new StringBuilder(message.getContent() != null ? message.getContent() : "");
        }

        synchronized void appendContent(String content) {
            if (content != null && !content.isBlank()) {
                if (!mergedContent.isEmpty()) {
                    mergedContent.append('\n');
                }
                mergedContent.append(content);
            }
        }

        synchronized String getMergedContent() {
            return mergedContent.toString();
        }
    }

    // ==================== 入队（替代原 route 方法） ====================

    /**
     * 将渠道消息入队到对应渠道的处理队列（防抖后入队）。
     * <p>
     * Webhook 调用此方法后立即返回，不阻塞。
     *
     * @param message       入站消息
     * @param adapter       来源渠道适配器（用于回复）
     * @param channelEntity 渠道配置（含关联 agentId）
     */
    public void enqueue(ChannelMessage message, ChannelAdapter adapter, ChannelEntity channelEntity) {
        // Fan out to the trigger pipeline FIRST — channel_message and
        // content_match triggers fire on every received message regardless
        // of whether the channel has an agent attached. If we returned
        // early on a missing agent below without publishing, the workflow
        // side would silently lose every channel-event that doesn't also
        // route to a chat agent.
        publishChannelEvent(message, adapter, channelEntity);

        Long agentId = channelEntity.getAgentId();
        if (agentId == null) {
            log.warn("Channel {} has no associated agent, ignoring message from {}",
                    channelEntity.getName(), message.getSenderId());
            return;
        }

        if (shutdown) {
            log.warn("Router is shutting down, rejecting message from {}", message.getSenderId());
            return;
        }

        String channelType = adapter.getChannelType();
        String conversationId = buildConversationId(message);

        log.info("[{}] Enqueuing message: sender={}, conversationId={}, agentId={}",
                channelType, message.getSenderId(), conversationId, agentId);

        // 防抖：同一会话 500ms 内的连续消息合并
        synchronized (pendingMessages) {
            PendingMessage existing = pendingMessages.get(conversationId);
            if (existing != null) {
                // 合并到已有的 pending 消息
                if (existing.timer != null) {
                    existing.timer.cancel(false);
                }
                existing.appendContent(message.getContent());
                existing.timer = debounceScheduler.schedule(
                        () -> flushPending(conversationId), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                log.debug("[{}] Message merged with pending (debounce): conversationId={}",
                        channelType, conversationId);
                return;
            }

            // 首条消息，创建 PendingMessage 并设定防抖定时器
            PendingMessage pending = new PendingMessage(message, adapter, channelEntity);
            pendingMessages.put(conversationId, pending);
            pending.timer = debounceScheduler.schedule(
                    () -> flushPending(conversationId), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Publish a {@link ChannelMessageReceivedEvent} so the trigger module's
     * bridge can fan the message out to channel_message + content_match
     * triggers. Best-effort — a publish failure must never block the
     * primary chat-routing path. {@code messageId} is used as the dedup
     * key downstream so repeated webhook deliveries can't double-fire
     * the same trigger.
     */
    private void publishChannelEvent(ChannelMessage message, ChannelAdapter adapter,
                                     ChannelEntity channelEntity) {
        if (events == null || message == null || adapter == null || channelEntity == null) return;
        try {
            long ws = channelEntity.getWorkspaceId() == null ? 0L : channelEntity.getWorkspaceId();
            String channelType = adapter.getChannelType();
            // messageId may be null for adapters that don't surface one;
            // fall back to a sender+timestamp composite so the dedup key
            // is at least deterministic-ish per webhook delivery.
            String messageId = message.getMessageId();
            if (messageId == null || messageId.isBlank()) {
                messageId = channelType + ":" + message.getSenderId() + ":"
                        + (message.getTimestamp() == null ? System.currentTimeMillis()
                                                          : message.getTimestamp());
            }
            events.publishEvent(new ChannelMessageReceivedEvent(
                    ws,
                    channelType,
                    messageId,
                    message.getSenderId(),
                    message.getSenderName(),
                    message.getChatId(),
                    message.getContent()));
        } catch (Exception e) {
            log.warn("[ChannelMessageRouter] event publish failed for sender {}: {}",
                    message.getSenderId(), e.getMessage());
        }
    }

    /**
     * 防抖到期：将合并后的消息真正放入渠道队列
     */
    private void flushPending(String conversationId) {
        PendingMessage pending;
        synchronized (pendingMessages) {
            pending = pendingMessages.remove(conversationId);
        }
        if (pending == null) return;

        // 更新消息内容为合并后的文本
        pending.firstMessage.setContent(pending.getMergedContent());

        String channelType = pending.adapter.getChannelType();
        LinkedBlockingQueue<QueueEntry> queue = channelQueues.computeIfAbsent(channelType, this::createChannelQueue);

        boolean offered = queue.offer(new QueueEntry(pending.firstMessage, pending.adapter, pending.channelEntity));
        if (!offered) {
            log.error("[{}] Message queue full (capacity={}), dropping message from {}",
                    channelType, QUEUE_CAPACITY, pending.firstMessage.getSenderId());
            try {
                String replyTarget = resolveReplyTarget(pending.firstMessage);
                pending.adapter.sendMessage(replyTarget, "系统繁忙，请稍后再试");
            } catch (Exception e) {
                log.error("[{}] Failed to send busy message: {}", channelType, e.getMessage());
            }
        } else {
            log.debug("[{}] Message flushed to queue: conversationId={}, queueSize={}",
                    channelType, conversationId, queue.size());
        }
    }

    // ==================== 消费线程 ====================

    /**
     * 为渠道类型创建队列并启动消费线程
     */
    private LinkedBlockingQueue<QueueEntry> createChannelQueue(String channelType) {
        LinkedBlockingQueue<QueueEntry> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

        ExecutorService executor = Executors.newFixedThreadPool(CONSUMERS_PER_CHANNEL, new ThreadFactory() {
            private int counter = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "channel-consumer-" + channelType + "-" + (counter++));
                t.setDaemon(true);
                return t;
            }
        });

        for (int i = 0; i < CONSUMERS_PER_CHANNEL; i++) {
            executor.execute(() -> consumeLoop(channelType, queue));
        }

        channelExecutors.put(channelType, executor);
        log.info("[{}] Created message queue (capacity={}) with {} consumer threads",
                channelType, QUEUE_CAPACITY, CONSUMERS_PER_CHANNEL);
        return queue;
    }

    /**
     * 消费线程循环：从队列取消息，加会话锁后串行处理
     */
    private void consumeLoop(String channelType, LinkedBlockingQueue<QueueEntry> queue) {
        log.info("[{}] Consumer thread started: {}", channelType, Thread.currentThread().getName());
        while (!shutdown) {
            try {
                QueueEntry entry = queue.poll(1, TimeUnit.SECONDS);
                if (entry == null) {
                    continue; // 超时，重新检查 shutdown 标志
                }

                String conversationId = buildConversationId(entry.message());
                ReentrantLock lock = sessionLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());

                lock.lock();
                try {
                    processMessage(entry.message(), entry.adapter(), entry.channelEntity(), conversationId);
                } finally {
                    lock.unlock();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[{}] Unexpected error in consumer loop: {}", channelType, e.getMessage(), e);
            }
        }
        log.info("[{}] Consumer thread stopped: {}", channelType, Thread.currentThread().getName());
    }

    // ==================== 审批命令识别 ====================

    private static final java.util.Set<String> APPROVE_COMMANDS = java.util.Set.of(
            "approve", "/approve", "批准", "/批准");
    private static final java.util.Set<String> DENY_COMMANDS = java.util.Set.of(
            "deny", "/deny", "拒绝", "/拒绝");
    /** 带 pendingId 的审批命令格式：/approve a1b2c3 */
    private static final java.util.regex.Pattern APPROVE_WITH_ID =
            java.util.regex.Pattern.compile("^/?(approve|批准)\\s+([a-f0-9]{6,16})$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DENY_WITH_ID =
            java.util.regex.Pattern.compile("^/?(deny|拒绝)\\s+([a-f0-9]{6,16})$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean isApproveCommand(String text) {
        String t = text.toLowerCase().strip();
        return APPROVE_COMMANDS.contains(t) || APPROVE_WITH_ID.matcher(t).matches();
    }

    private boolean isDenyCommand(String text) {
        String t = text.toLowerCase().strip();
        return DENY_COMMANDS.contains(t) || DENY_WITH_ID.matcher(t).matches();
    }

    /**
     * 从审批命令中提取 shortId（如 "/approve a1b2c3" → "a1b2c3"），无 id 则返回 null
     */
    private String extractShortId(String text) {
        java.util.regex.Matcher m = APPROVE_WITH_ID.matcher(text.strip());
        if (m.matches()) return m.group(2);
        m = DENY_WITH_ID.matcher(text.strip());
        if (m.matches()) return m.group(2);
        return null;
    }

    // ==================== 消息处理（原 route 逻辑 + 审批拦截层） ====================

    /**
     * 处理单条消息：保存 -> 调用 Agent -> 保存回复 -> 发送回复
     * <p>
     * 当钉钉渠道启用 AI Card 时，走流式卡片路径。
     */
    private void processMessage(ChannelMessage message, ChannelAdapter adapter,
                                ChannelEntity channelEntity, String conversationId) {
        Long agentId = channelEntity.getAgentId();
        log.info("[{}] Processing message: sender={}, conversationId={}, agentId={}",
                adapter.getChannelType(), message.getSenderId(), conversationId, agentId);

        try {
            // ======= 审批拦截层 =======
            String userText = message.getContent() != null ? message.getContent().trim() : "";
            PendingApproval pending = approvalService.findPendingByConversation(conversationId);

            if (pending != null) {
                String replyTarget = resolveReplyTarget(message);

                if (isApproveCommand(userText)) {
                    // pendingId 校验：如果命令包含 shortId，验证是否匹配当前 pending
                    String shortId = extractShortId(userText);
                    if (shortId != null && !pending.getPendingId().startsWith(shortId)) {
                        adapter.sendMessage(replyTarget, "⚠️ 审批ID不匹配。当前待审批: "
                                + pending.getPendingId().substring(0, Math.min(6, pending.getPendingId().length())));
                        return;
                    }
                    // 身份校验：只有原始请求者可以审批（群聊安全）
                    String originalRequester = pending.getUserId();
                    if (originalRequester != null && !"system".equals(originalRequester)
                            && !originalRequester.equals(message.getSenderId())) {
                        adapter.sendMessage(replyTarget, "⚠️ 只有原始请求者可以审批此操作。");
                        log.warn("[{}] Approval rejected: sender={} != requester={}",
                                adapter.getChannelType(), message.getSenderId(), originalRequester);
                        return;
                    }
                    // Approve via IM: workflow.resolveAndConsume runs DB + metadata + memory atomically.
                    ResolveOutcome consumeOutcome = approvalService.resolveAndConsume(
                            pending.getPendingId(), message.getSenderId());
                    if (consumeOutcome.isAlreadyResolved()) {
                        adapter.sendMessage(replyTarget, "⚠️ 审批记录已过期或已被处理。");
                        return;
                    }
                    PendingApproval consumed = consumeOutcome.consumedSnapshot();
                    log.info("[{}] Approval APPROVED via IM command: pendingId={}, tool={}, msgRewritten={}",
                            adapter.getChannelType(), consumed.getPendingId(), consumed.getToolName(),
                            consumeOutcome.messagesRewritten());

                    replayApprovedToolCall(consumed, conversationId, adapter, message, channelEntity);
                    return;

                } else if (isDenyCommand(userText)) {
                    // Deny via IM: workflow.resolve owns the full state-machine transition.
                    ResolveOutcome denyOutcome = approvalService.resolve(
                            pending.getPendingId(), message.getSenderId(), "denied");
                    conversationService.removeApprovalPlaceholders(conversationId);
                    adapter.sendMessage(replyTarget, "⛔ 已拒绝执行工具: " + pending.getToolName());
                    log.info("[{}] Approval DENIED via IM command: pendingId={}, tool={}, msgRewritten={}",
                            adapter.getChannelType(), pending.getPendingId(), pending.getToolName(),
                            denyOutcome.messagesRewritten());
                    return;

                } else {
                    // Non-approval message while a pending exists → treat as implicit deny.
                    approvalService.resolve(pending.getPendingId(), message.getSenderId(), "denied");
                    conversationService.removeApprovalPlaceholders(conversationId);
                    adapter.sendMessage(replyTarget, "⛔ 审批已取消。将继续处理您的新消息。");
                    log.info("[{}] Approval auto-cancelled (non-approval message): pendingId={}",
                            adapter.getChannelType(), pending.getPendingId());
                    // Fall through to process the new message normally.
                }
            }
            // ======= 审批拦截层结束 =======

            // 确保会话存在（workspace 感知）
            conversationService.getOrCreateSharedConversation(conversationId, agentId, channelEntity.getWorkspaceId());

            // 更新渠道会话存储（用于主动推送）
            String replyTarget = resolveReplyTarget(message);
            if (replyTarget != null) {
                channelSessionStore.saveOrUpdate(
                        conversationId,
                        adapter.getChannelType(),
                        replyTarget,
                        message.getSenderId(),
                        message.getSenderName(),
                        channelEntity.getId()
                );
            } else {
                log.warn("[{}] No reply target resolved for sender={}, skipping session store update",
                        adapter.getChannelType(), message.getSenderId());
            }

            // 保存用户消息（带 contentParts）
            List<MessageContentPart> parts = message.getContentParts();
            conversationService.saveMessage(conversationId, "user", message.getContent(), parts);

            // 构建 prompt（语音输入时注入场景提示词）
            String promptText = buildPromptFromParts(message.getContent(), parts, message.getInputMode());

            // 注册到 ChatStreamTracker：让 graph 节点广播的事件（phase / content_delta / tool_call_* 等）
            // 能被 ChatConsole observer 订阅到。不注册 → broadcast() 会因 state==null 短路丢弃。
            // 同步 DB stream_status 让侧栏列表可以识别"该渠道对话正在运行"，从而触发 selectConversation 的 reconnect 分支。
            streamTracker.register(conversationId);
            streamTracker.incrementFlux(conversationId);
            conversationService.updateStreamStatus(conversationId, "running");
            // 捕获已保存 assistant 的 DB id，供 finally 的 done 事件带出来 —— 前端 useChat.done 处理器
            // 依赖 data.assistantMessageId 把本地流式 placeholder 的 client-uuid 升级为 DB id，这样
            // 紧接着 refreshCurrentConversationMessages 的 reconcile 能按 id 干净匹配，不会走"认领"兜底
            // 导致气泡丢失。
            Long savedAssistantId = null;
            try {
                // 流式路径：渠道实现了 StreamingChannelAdapter 则委托渠道渲染流式事件
                // RFC-063r §2.5: build the ChatOrigin once per channel-message
                // so cron jobs created during this conversation inherit the
                // channel binding (Issue #25 root path).
                ChatOrigin chatOrigin = chatOriginFactory.from(
                        channelEntity, message, conversationId, /* workspaceBasePath */ null);

                if (adapter instanceof StreamingChannelAdapter streamingAdapter) {
                    savedAssistantId = processWithStreaming(message, streamingAdapter, conversationId, agentId, promptText, channelEntity, chatOrigin);
                } else {
                    // Sync path for non-streaming IM adapters (feishu / wecom / weixin /
                    // slack / discord / qq / telegram). We can't use agentService.chat()
                    // because its collector filters out `delta.isEvent()` deltas — that
                    // would silently drop plan_created / plan_step_* events that the Web
                    // Console mirror needs to render PlanStepsPanel. Instead we consume
                    // chatStructuredStream directly: content gets accumulated for the IM
                    // reply, and whitelisted plan events are mirrored to ChatStreamTracker
                    // for any Web SSE viewer of the same conversationId.
                    StringBuilder replyAccumulator = new StringBuilder();
                    final String channelType = adapter.getChannelType();
                    agentService.chatStructuredStream(agentId, promptText, conversationId,
                                    message.getSenderId(), chatOrigin)
                            .doOnNext(delta -> {
                                if (delta.isEvent()) {
                                    mirrorPlanEventToTracker(conversationId, delta, channelType);
                                } else if (delta.content() != null) {
                                    // Match the legacy agentService.chat() behavior: include
                                    // persistOnly deltas too. DirectAnswerNode-routed answers
                                    // arrive as persistOnly when CONTENT_STREAMED=true and IM
                                    // channels still need the text for the outgoing reply.
                                    replyAccumulator.append(delta.content());
                                }
                            })
                            .blockLast(Duration.ofMinutes(10));
                    String reply = replyAccumulator.toString();

                    // 检查 chat 过程中是否产生了审批 pending
                    PendingApproval newPending = approvalService.findPendingByConversation(conversationId);
                    if (newPending != null) {
                        // Channel-specific approval rendering: WeCom overrides
                        // sendApprovalNotice to post a button_interaction card;
                        // every other adapter falls back to the markdown-text path
                        // on AbstractChannelAdapter (preserves PR-0 behavior for
                        // non-WeCom channels).
                        var notice = approvalNotificationService.buildNotice(newPending);
                        adapter.sendApprovalNotice(replyTarget, notice);
                        log.info("[{}] Approval triggered during chat, sent notice (NOT saved to DB): tool={}",
                                adapter.getChannelType(), newPending.getToolName());
                    } else {
                        // Tag error replies (matched by ChannelErrorClassifier — the
                        // "[错误]" content prefix / Bad request: / LLM error templates)
                        // with status='error' so BaseAgent.sanitizeForLlm drops them
                        // from the next turn's LLM history, breaking the self-replicating
                        // 400 loop. Only successful replies fire the ConversationCompletedEvent —
                        // error turns must not pollute memory extraction.
                        boolean isError = errorClassifier.isErrorReply(reply);
                        String status = isError ? "error" : "completed";
                        MessageEntity saved = conversationService.saveMessage(
                                conversationId, "assistant", reply, null, status);
                        savedAssistantId = saved != null ? saved.getId() : null;
                        if (!isError) {
                            publishConversationCompletedEvent(agentId, conversationId, message.getContent(), reply);
                        }
                        adapter.renderAndSend(replyTarget, reply);
                        log.info("[{}] Reply sent to {}: {}chars",
                                adapter.getChannelType(), replyTarget, reply.length());

                        // 给 observer 推送完整的 assistant 消息以便前端一次性渲染为气泡
                        // （在 content_delta 事件可能没开的同步路径下作为兜底）
                        Map<String, Object> msgCompletePayload = new HashMap<>();
                        msgCompletePayload.put("conversationId", conversationId);
                        msgCompletePayload.put("content", reply);
                        if (savedAssistantId != null) {
                            msgCompletePayload.put("assistantMessageId", savedAssistantId);
                        }
                        msgCompletePayload.put("timestamp", System.currentTimeMillis());
                        streamTracker.broadcastObject(conversationId, "message_complete", msgCompletePayload);

                        // 语音回复：异步 TTS 合成并追加发送（先文本后语音，不阻塞）
                        maybeGenerateVoiceReply(message, adapter, replyTarget, conversationId, reply, channelEntity);
                    }
                }
            } finally {
                // 广播 done 事件让 observer 前端收尾（HashMap 允许 null 值缺失，Map.of 不允许）
                Map<String, Object> donePayload = new HashMap<>();
                donePayload.put("conversationId", conversationId);
                donePayload.put("status", "completed");
                if (savedAssistantId != null) {
                    donePayload.put("assistantMessageId", savedAssistantId);
                }
                donePayload.put("timestamp", System.currentTimeMillis());
                streamTracker.broadcastObject(conversationId, "done", donePayload);
                streamTracker.completeAndConsumeIfLast(conversationId);
                try {
                    conversationService.updateStreamStatus(conversationId, "idle");
                } catch (Exception e) {
                    log.debug("Failed to reset stream_status for {}: {}", conversationId, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[{}] Failed to process message from {}: {}",
                    adapter.getChannelType(), message.getSenderId(), e.getMessage(), e);

            // 尝试发送错误提示
            try {
                String errorTarget = resolveReplyTarget(message);
                adapter.sendMessage(errorTarget, "抱歉，处理消息时出现错误：" + e.getMessage());
            } catch (Exception sendErr) {
                log.error("[{}] Failed to send error message: {}",
                        adapter.getChannelType(), sendErr.getMessage());
            }
        }
    }

    /**
     * 流式处理路径（渠道无关）
     * <p>
     * 事件流与渲染分离：
     * - Router 负责产生 StreamDelta 流（调用 AgentService）
     * - StreamingChannelAdapter 负责渲染（AI Card / 卡片更新 / 文本累积等）
     * - Router 负责后续的审批检查、消息持久化、事件发布
     */
    /**
     * Forward whitelisted Plan-Execute SSE events to ChatStreamTracker so a
     * Web Console viewer of an IM-routed conversation sees PlanStepsPanel.
     * <p>
     * Bounded to {@link #MIRRORED_PLAN_EVENTS} — see the constant's javadoc
     * for why this is a whitelist rather than a pass-through. Failures here
     * are best-effort and never propagate, since dropping a UI update is
     * preferable to derailing the channel reply.
     */
    private void mirrorPlanEventToTracker(String conversationId,
                                          AgentService.StreamDelta delta,
                                          String channelTypeForLog) {
        String eventType = delta.eventType();
        if (eventType == null || !MIRRORED_PLAN_EVENTS.contains(eventType)) {
            return;
        }
        try {
            streamTracker.broadcastObject(conversationId, eventType, delta.eventData());
        } catch (Exception ex) {
            log.debug("[{}] Failed to mirror plan event {}: {}",
                    channelTypeForLog, eventType, ex.getMessage());
        }
    }

    private Long processWithStreaming(ChannelMessage message, StreamingChannelAdapter streamingAdapter,
                                      String conversationId, Long agentId, String promptText,
                                      ChannelEntity channelEntity, ChatOrigin chatOrigin) {
        String channelType = streamingAdapter.getChannelType();
        log.info("[{}] Streaming processing started: conversationId={}", channelType, conversationId);

        try {
            // Step 1: 产生事件流（RFC-063r §2.5: forward ChatOrigin so tools see channelId）
            Flux<AgentService.StreamDelta> stream = agentService.chatStructuredStream(
                    agentId, promptText, conversationId, message.getSenderId(), chatOrigin);

            // Mirror plan-execute SSE events to ChatStreamTracker before the
            // adapter consumes the Flux. DingTalkChannelAdapter.processStreamAsText
            // only reads `delta.content()` and would otherwise eat plan_created /
            // plan_step_* events, leaving the Web Console mirror with no
            // PlanStepsPanel for IM-routed conversations.
            Flux<AgentService.StreamDelta> mirroredStream = stream.doOnNext(delta ->
                    mirrorPlanEventToTracker(conversationId, delta, channelType));

            // Step 2: 委托渠道渲染（渠道内部消费 Flux 并处理 UI 更新）
            String finalContent = streamingAdapter.processStream(mirroredStream, message, conversationId);

            // Step 3: 审批检查 + 持久化（渠道无关逻辑，由 Router 统一处理）
            PendingApproval newPending = approvalService.findPendingByConversation(conversationId);
            if (newPending != null) {
                String replyTarget = resolveReplyTarget(message);
                // Same polymorphic dispatch as the non-streaming path — WeCom
                // renders a card, others render text. See the buildNotice +
                // sendApprovalNotice pair at the non-streaming call site above.
                var notice = approvalNotificationService.buildNotice(newPending);
                streamingAdapter.sendApprovalNotice(replyTarget, notice);
                log.info("[{}] Approval triggered during streaming (NOT saved to DB): tool={}",
                        channelType, newPending.getToolName());
            } else if (finalContent != null && !finalContent.isBlank()) {
                boolean isError = errorClassifier.isErrorReply(finalContent);
                String status = isError ? "error" : "completed";
                MessageEntity saved = conversationService.saveMessage(
                        conversationId, "assistant", finalContent, null, status);
                if (!isError) {
                    publishConversationCompletedEvent(agentId, conversationId, promptText, finalContent);
                }
                log.info("[{}] Streaming completed: contentLen={}, isError={}",
                        channelType, finalContent.length(), isError);

                // 流式回复完成后也触发语音回复
                String replyTarget = resolveReplyTarget(message);
                if (replyTarget != null) {
                    maybeGenerateVoiceReply(message, streamingAdapter, replyTarget,
                            conversationId, finalContent, channelEntity);
                }
                return saved != null ? saved.getId() : null;
            }

        } catch (Exception e) {
            log.error("[{}] Streaming processing failed: {}", channelType, e.getMessage(), e);
            // Persist an error placeholder (status='error') so that the next
            // turn's history does not show a user → user sequence (which some
            // providers reject with 400). sanitizeForLlm filters this row out
            // before the LLM sees it, so it costs nothing at the prompt layer.
            try {
                conversationService.saveMessage(conversationId, "assistant",
                        "[错误] " + e.getMessage(), null, "error");
            } catch (Exception persistErr) {
                log.warn("[{}] Failed to persist error placeholder: {}",
                        channelType, persistErr.getMessage());
            }
            try {
                String errorTarget = resolveReplyTarget(message);
                streamingAdapter.sendMessage(errorTarget, "抱歉，流式处理失败：" + e.getMessage());
            } catch (Exception sendErr) {
                log.error("[{}] Failed to send streaming error message: {}", channelType, sendErr.getMessage());
            }
        }
        return null;
    }


    // ==================== 审批重放 ====================

    /**
     * 重放被审批阻塞的工具调用
     * <p>
     * 接收已消费的审批记录（由 resolveAndConsume 原子获取），通过 AgentService.chatWithReplay 重新执行工具。
     * 重放前清理 DB 中的审批占位消息，防止 LLM 看到残留文本后重新发起工具调用（死循环根因）。
     */
    private void replayApprovedToolCall(PendingApproval consumed, String conversationId,
                                         ChannelAdapter adapter, ChannelMessage triggerMessage,
                                         ChannelEntity channelEntity) {
        String replyTarget = resolveReplyTarget(triggerMessage);
        Long agentId = channelEntity.getAgentId();

        // 通知用户审批已通过
        adapter.sendMessage(replyTarget, "✅ 已批准执行工具: " + consumed.getToolName());

        // 清理 DB 中残留的审批占位消息
        conversationService.removeApprovalPlaceholders(conversationId);

        // 简化 replay prompt（不重复工具名，防止 LLM 误解）
        String replayPrompt = "继续执行已批准的工具调用。";

        try {
            // RFC-063r §2.12: prefer the persisted Memento (covers
            // cross-restart approval where the channel session changed) and
            // only fall back to rebuilding from the current inbound message
            // when no snapshot was captured (legacy rows from before this PR).
            ChatOrigin replayOrigin = approvalService.restoreChatOrigin(consumed.getChatOrigin());
            if (replayOrigin == ChatOrigin.EMPTY) {
                replayOrigin = chatOriginFactory.from(
                        channelEntity, triggerMessage, conversationId, /* workspaceBasePath */ null);
            }
            String reply = agentService.chatWithReplay(
                    agentId, replayPrompt, conversationId, consumed.getToolCallPayload(), replayOrigin);

            // Persist the replay result. If the LLM 400'd during replay,
            // the error reply must also get status='error' — otherwise the
            // next turn's history would re-feed the error placeholder back
            // into the prompt and re-trigger the same failure.
            boolean isError = errorClassifier.isErrorReply(reply);
            conversationService.saveMessage(conversationId, "assistant", reply, null,
                    isError ? "error" : "completed");

            // 发送回复
            adapter.renderAndSend(replyTarget, reply);

            log.info("[{}] Replay completed: tool={}, replyLen={}",
                    adapter.getChannelType(), consumed.getToolName(), reply.length());
        } catch (Exception e) {
            log.error("[approval-replay] Replay failed: {}", e.getMessage(), e);
            adapter.sendMessage(replyTarget, "❌ 工具执行失败: " + e.getMessage());
        }
    }

    /**
     * 从 PendingApproval 元数据构建 IM 友好的审批通知（委托给 ApprovalNotificationService）
     */
    private String buildApprovalNotice(PendingApproval pending) {
        return approvalNotificationService.buildApprovalText(pending);
    }

    /**
     * Publish the conversation-completed event (triggers async memory extraction).
     * Delegates to {@link ConversationCompletionPublisher} so the try/catch and
     * messageCount lookup no longer live here.
     */
    private void publishConversationCompletedEvent(Long agentId, String conversationId,
                                                    String userMessage, String assistantReply) {
        completionPublisher.publish(agentId, conversationId, userMessage, assistantReply, "channel");
    }

    // ==================== 流式处理（Web 渠道专用，不走队列） ====================

    /**
     * 路由消息并使用流式处理（用于支持流式的渠道，如 Web）
     */
    public Flux<String> routeStream(ChannelMessage message, ChannelEntity channelEntity) {
        Long agentId = channelEntity.getAgentId();
        if (agentId == null) {
            return Flux.error(new IllegalStateException("Channel has no associated agent"));
        }

        String conversationId = buildConversationId(message);
        String username = message.getSenderName() != null ? message.getSenderName() : message.getSenderId();

        conversationService.getOrCreateConversation(conversationId, agentId, username, channelEntity.getWorkspaceId());
        List<MessageContentPart> parts = message.getContentParts();
        conversationService.saveMessage(conversationId, "user", message.getContent(), parts);

        String promptText = buildPromptFromParts(message.getContent(), parts, message.getInputMode());
        // RFC-063r §2.5: forward ChatOrigin so tools created during this
        // streaming conversation inherit channel binding.
        ChatOrigin origin = chatOriginFactory.from(
                channelEntity, message, conversationId, /* workspaceBasePath */ null);
        return agentService.chatStream(agentId, promptText, conversationId, origin);
    }

    // ==================== 优雅关闭 ====================

    /**
     * 优雅关闭：停止防抖调度器和所有消费线程
     */
    public void shutdown() {
        log.info("Shutting down ChannelMessageRouter...");
        shutdown = true;

        // 1. 关闭防抖调度器
        debounceScheduler.shutdownNow();

        // 2. 清理残留的 pending 消息
        synchronized (pendingMessages) {
            pendingMessages.forEach((convId, pending) -> {
                if (pending.timer != null) {
                    pending.timer.cancel(false);
                }
                log.warn("Dropping pending debounced message for conversation: {}", convId);
            });
            pendingMessages.clear();
        }

        // 3. 关闭每个渠道的消费线程池：shutdown -> 等待 5 秒 -> shutdownNow
        channelExecutors.forEach((channelType, executor) -> {
            log.info("[{}] Shutting down consumer threads...", channelType);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("[{}] Consumer threads did not terminate in 5s, forcing shutdown", channelType);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });

        channelExecutors.clear();
        channelQueues.clear();
        sessionLocks.clear();

        // 4. 关闭语音回复线程池
        voiceReplyExecutor.shutdownNow();

        log.info("ChannelMessageRouter shutdown complete");
    }

    // ==================== 工具方法 ====================

    /**
     * 构建会话 ID
     * 格式：{channelType}:{chatId 或 senderId}
     * 格式采用 {channelType}:{identifier} 命名规则
     */
    private String buildConversationId(ChannelMessage message) {
        String identifier = message.getChatId() != null ? message.getChatId() : message.getSenderId();
        return message.getChannelType() + ":" + identifier;
    }

    /**
     * 确定回复目标
     * 优先使用 replyToken（渠道特有的回复标识），其次 chatId，最后 senderId
     */
    private String resolveReplyTarget(ChannelMessage message) {
        if (message.getReplyToken() != null) {
            return message.getReplyToken();
        }
        return message.getChatId() != null ? message.getChatId() : message.getSenderId();
    }

    /**
     * 从 contentParts 构建完整 prompt 文本。
     * 文本直接拼接；媒体类型生成描述性占位符，让 Agent 知道用户发送了什么。
     * 语音输入时注入场景提示词，引导 Agent 用简短口语化方式回复。
     */
    private String buildPromptFromParts(String fallbackContent, List<MessageContentPart> parts, String inputMode) {
        if (parts == null || parts.isEmpty()) {
            return fallbackContent != null ? fallbackContent : "";
        }
        StringBuilder sb = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null || part.getType() == null) continue;
            switch (part.getType()) {
                case "text" -> appendLine(sb, part.getText());
                case "image" -> appendLine(sb, "[用户发送了图片" + descMedia(part) + "]");
                case "file" -> appendLine(sb, "[用户发送了文件: " + safe(part.getFileName()) + "]");
                case "audio" -> appendLine(sb, "[用户发送了音频" + descMedia(part) + "]");
                case "video" -> appendLine(sb, "[用户发送了视频" + descMedia(part) + "]");
                default -> appendLine(sb, part.getText());
            }
        }
        String result = sb.toString().trim();
        if (result.isEmpty()) {
            return fallbackContent != null ? fallbackContent : "";
        }
        // 语音场景：注入提示词让 Agent 回复更简短口语化
        if ("voice".equals(inputMode)) {
            result = "[用户通过语音输入，请用简短口语化的方式回复]\n" + result;
        }
        return result;
    }

    private void appendLine(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) return;
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(text);
    }

    private String descMedia(MessageContentPart part) {
        if (part.getFileName() != null && !part.getFileName().isBlank()) {
            return ": " + part.getFileName();
        }
        return "";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    // ==================== 语音回复（TTS）====================

    /** TTS 异步工作线程池 */
    private final ExecutorService voiceReplyExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "voice-reply-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 根据消息上下文和渠道配置，判断是否需要生成语音回复。
     * 若需要，异步合成 TTS 并通过渠道发送音频文件。
     * <p>
     * 设计原则（借鉴 OpenClaw）：
     * - 文本先行，语音异步追加，不阻塞用户体验
     * - 短回复（<10字）跳过 TTS（不值得合成）
     * - TTS 失败静默降级，不影响已发出的文本回复
     */
    private void maybeGenerateVoiceReply(ChannelMessage message, ChannelAdapter adapter,
                                          String replyTarget, String conversationId,
                                          String replyText, ChannelEntity channelEntity) {
        if (!shouldGenerateVoiceReply(message, channelEntity, replyText)) {
            return;
        }

        voiceReplyExecutor.submit(() -> {
            try {
                // 读取渠道级语音配置
                Map<String, Object> channelConfig = parseChannelConfig(channelEntity.getConfigJson());
                String voiceName = channelConfig.getOrDefault("voice_name", "").toString();
                double voiceSpeed = 1.0;
                Object speedObj = channelConfig.get("voice_speed");
                if (speedObj instanceof Number n) voiceSpeed = n.doubleValue();

                // 调用 TtsService 合成
                Map<String, Object> result = ttsService.synthesize(
                        conversationId, replyText,
                        voiceName.isBlank() ? null : voiceName,
                        voiceSpeed, "mp3");

                if (!Boolean.TRUE.equals(result.get("success"))) {
                    log.debug("[voice-reply] TTS synthesis failed: {}", result.get("error"));
                    return;
                }

                // 构建音频 MessageContentPart
                String audioUrl = (String) result.get("audioUrl");
                String fileName = Paths.get(audioUrl).getFileName().toString();
                Path audioPath = Paths.get("data", "chat-uploads", conversationId, fileName);

                if (!Files.exists(audioPath)) {
                    log.warn("[voice-reply] TTS output file not found: {}", audioPath);
                    return;
                }

                MessageContentPart audioPart = new MessageContentPart();
                audioPart.setType("audio");
                audioPart.setFileName(fileName);
                audioPart.setPath(audioPath.toString());
                audioPart.setContentType("audio/mpeg");

                adapter.sendContentParts(replyTarget, List.of(audioPart));
                log.info("[voice-reply] Sent to {} via {}: {} ({}KB)",
                        replyTarget, adapter.getChannelType(), fileName,
                        Files.size(audioPath) / 1024);

            } catch (Exception e) {
                // 静默降级：TTS 失败不影响已发出的文本回复
                log.warn("[voice-reply] Failed for conversation {}: {}", conversationId, e.getMessage());
            }
        });
    }

    /**
     * 判断是否需要为此消息生成语音回复
     */
    private boolean shouldGenerateVoiceReply(ChannelMessage message, ChannelEntity channelEntity,
                                              String replyText) {
        // 1. TTS 全局开关
        if (!ttsService.isTtsEnabled()) return false;

        // 2. 回复内容过短或为空，跳过 TTS（借鉴 OpenClaw: <10字不合成）
        if (replyText == null || replyText.trim().length() < 10) return false;

        // 3. 读取渠道级语音回复模式
        Map<String, Object> channelConfig = parseChannelConfig(channelEntity.getConfigJson());
        String voiceMode = channelConfig.getOrDefault("voice_reply_mode", "off").toString();

        if ("off".equals(voiceMode)) return false;
        if ("always".equals(voiceMode)) return true;

        // 4. auto 模式：仅当用户通过语音输入时回语音
        return "auto".equals(voiceMode) && "voice".equals(message.getInputMode());
    }

    /**
     * 解析 Channel 的 configJson 为 Map
     */
    private Map<String, Object> parseChannelConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("[voice-reply] Failed to parse channel config: {}", e.getMessage());
            return Map.of();
        }
    }
}
