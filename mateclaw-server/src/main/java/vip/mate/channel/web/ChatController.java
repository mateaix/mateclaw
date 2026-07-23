package vip.mate.channel.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.common.result.R;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.MetadataDecision;
import vip.mate.approval.PendingApproval;
import vip.mate.approval.ResolveOutcome;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web 渠道聊天接口
 * 提供 SSE 流式对话和同步对话能力
 *
 * @author MateClaw Team
 */
@Tag(name = "Web聊天")
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ApprovalWorkflowService approvalService;
    private final ChatStreamTracker streamTracker;
    private final ObjectMapper objectMapper;
    private final ConversationCompletionPublisher completionPublisher;
    private final vip.mate.memory.identity.MemoryOwnerResolver memoryOwnerResolver;
    private final vip.mate.workspace.core.service.ChatUploadLocationResolver uploadLocationResolver;
    private final vip.mate.tool.document.preview.OfficePreviewService officePreviewService;

    // Virtual thread per SSE task: matches the app-wide virtual-thread model
    // (spring.threads.virtual.enabled=true) and, unlike a cached platform-thread
    // pool, never reuses a thread across tasks, so no ThreadLocal state can leak
    // from one stream into another.
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * SSE 流式对话（支持断线重连）
     * <p>
     * 正常请求：保存用户消息，启动 Flux 生产者，通过 StreamTracker 广播事件。
     * 重连请求（reconnect=true）：附着到仍在运行的流，回放已缓冲事件后接收实时增量。
     */
    @Operation(summary = "结构化 SSE 流式对话（支持重连）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestBody ChatStreamRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {

        String conversationId = request.getConversationId() != null ? request.getConversationId() : "default";
        // SSE 超时设为 10 分钟，覆盖 servlet 默认的 30s，避免长回答被中断
        // RFC-058 PR-1: Utf8SseEmitter 显式声明 charset=UTF-8，防止中文在 Windows 中文 Chrome / 部分代理处乱码
        SseEmitter emitter = new Utf8SseEmitter(10 * 60 * 1000L);

        // Resolve the public base URL on THIS (request) thread. Every agent run
        // below is dispatched to sseExecutor / reactive callbacks that run off
        // the request thread, where the request is no longer bound and
        // ServletUriComponentsBuilder would yield null. Capturing it here lets
        // tool-generated download links carry an absolute host on the streaming,
        // approval-replay, and queued-message paths alike.
        final String requestBaseUrl = resolveRequestBaseUrl();

        // ---- 分支 A：断线重连 ----
        if (Boolean.TRUE.equals(request.getReconnect())) {
            String reconnectUser = auth != null ? auth.getName() : "anonymous";
            log.info("SSE reconnect: conversationId={}, user={}", conversationId, reconnectUser);

            // 校验会话归属
            if (!conversationService.isConversationOwner(conversationId, reconnectUser)) {
                try {
                    sendEvent(emitter, "error", Map.of("message", "无权访问该会话"));
                } catch (IOException e) {
                    log.warn("SSE reconnect auth error send failed: {}", e.getMessage());
                }
                emitter.complete();
                return emitter;
            }

            registerEmitterCallbacks(emitter, conversationId);

            // Issue #17 — distinguish "stream truly completed on this node"
            // from "stream is running on another node (multi-node deployment
            // without sticky session)". They look identical from attach()'s
            // boolean return, but the user-facing remediation is different.
            boolean existsLocally = streamTracker.streamExistsOnThisNode(conversationId);
            long lastEventId = request.getLastEventId() == null ? 0L : request.getLastEventId();
            boolean attached = streamTracker.attach(conversationId, emitter, lastEventId);
            if (!attached) {
                try {
                    if (existsLocally) {
                        // RunState exists here but is done — stream finished normally
                        sendEvent(emitter, "done", Map.of("status", "completed"));
                    } else {
                        // No RunState on this node. Either:
                        //  (a) The stream finished long ago and was cleaned up, OR
                        //  (b) Multi-node deployment routed this reconnect to a
                        //      DIFFERENT node than the originating one. The CE
                        //      build assumes single-instance (see ChatStreamTracker
                        //      class javadoc and rfc-054 §0); LB must be configured
                        //      for sticky session by conversationId.
                        // We can't tell (a) from (b) at this layer, so emit an
                        // explicit code the front-end can surface to operators.
                        log.info("SSE reconnect: no RunState locally for conversationId={} — " +
                                "either completed-and-cleaned or running on another node", conversationId);
                        sendEvent(emitter, "done", Map.of(
                                "status", "stream_not_local",
                                "message", "Stream is not active on this node. " +
                                        "If you're running a multi-node deployment, " +
                                        "verify the load balancer is configured for sticky " +
                                        "session by conversationId. See deploy/multi-node-deployment.md."
                        ));
                    }
                } catch (IOException e) {
                    log.warn("SSE reconnect done send error: {}", e.getMessage());
                }
                emitter.complete();
            }
            return emitter;
        }

        // ---- 分支 B：正常请求 ----
        Long agentId = request.getAgentId();
        String requestMessage = request.getMessage() != null ? request.getMessage() : "";
        if (auth == null) {
            try {
                sendEvent(emitter, "error", Map.of("message", "未登录，请先登录"));
            } catch (IOException e) {
                log.warn("SSE auth error send failed: {}", e.getMessage());
            }
            emitter.complete();
            return emitter;
        }
        String username = auth.getName();
        log.info("SSE chat: agentId={}, conversationId={}, user={}", agentId, conversationId, username);

        // ---- Workspace 边界校验：确保 agent 属于当前 workspace ----
        if (agentId != null) {
            AgentEntity agent = agentService.getAgent(agentId);
            if (agent != null && agent.getWorkspaceId() != null) {
                long wsId = workspaceId != null ? workspaceId : 1L;
                if (!agent.getWorkspaceId().equals(wsId)) {
                    log.warn("Chat workspace mismatch: agent {} belongs to workspace {}, request workspace {}",
                            agentId, agent.getWorkspaceId(), wsId);
                    try {
                        sendEvent(emitter, "error", Map.of("message", "Agent 不属于当前工作区"));
                        sendEvent(emitter, "done", Map.of("status", "completed"));
                    } catch (IOException e) {
                        log.warn("SSE workspace error send failed: {}", e.getMessage());
                    }
                    emitter.complete();
                    return emitter;
                }
            }
        }

        // ---- 审批命令拦截：/approve、/deny 走 SSE 流式 replay ----
        String normalizedMsg = requestMessage.trim().toLowerCase();
        boolean isApprovalCommand = "/approve".equals(normalizedMsg) || "approve".equals(normalizedMsg);
        boolean isDenyCommand = "/deny".equals(normalizedMsg) || "deny".equals(normalizedMsg);

        if (isApprovalCommand || isDenyCommand) {
            PendingApproval pending = approvalService.findPendingByConversation(conversationId);
            if (pending == null) {
                try {
                    sendEvent(emitter, "error", Map.of("message", "当前没有待审批的工具调用"));
                    sendEvent(emitter, "done", Map.of("status", "completed"));
                } catch (IOException e) { /* ignore */ }
                emitter.complete();
                return emitter;
            }

            // deny: workflow.resolve handles DB + metadata + memory atomically.
            if (isDenyCommand) {
                ResolveOutcome denyOutcome = approvalService.resolve(pending.getPendingId(), username, "denied");
                conversationService.removeApprovalPlaceholders(conversationId);
                log.info("[Approval-Stream] User {} denied pending {} for conversation {} (dbSynced={}, msgRewritten={})",
                        username, pending.getPendingId(), conversationId,
                        denyOutcome.dbSynced(), denyOutcome.messagesRewritten());
            }

            // approve: atomic resolveAndConsume; workflow handles DB + metadata + memory.
            PendingApproval consumed = null;
            if (isApprovalCommand) {
                ResolveOutcome consumeOutcome = approvalService.resolveAndConsume(pending.getPendingId(), username);
                if (consumeOutcome.isAlreadyResolved()) {
                    try {
                        sendEvent(emitter, "error", Map.of("message", "审批记录已过期或已被处理"));
                        sendEvent(emitter, "done", Map.of("status", "completed"));
                    } catch (IOException e2) { /* ignore */ }
                    emitter.complete();
                    return emitter;
                }
                consumed = consumeOutcome.consumedSnapshot();
                // Clear residual approval placeholder messages so the LLM context for
                // replay doesn't include "[Awaiting approval]" text artifacts.
                conversationService.removeApprovalPlaceholders(conversationId);
                log.info("[Approval-Stream] User {} approved pending {} for conversation {} (msgRewritten={})",
                        username, consumed.getPendingId(), conversationId, consumeOutcome.messagesRewritten());
            }

            final PendingApproval finalConsumed = consumed;
            final String decision = isApprovalCommand ? "approved" : "denied";

            streamTracker.register(conversationId);
            Long approvalAgentId = parseLongOrNull(pending.getAgentId());
            streamTracker.bindRunMeta(conversationId, approvalAgentId, username);
            registerEmitterCallbacks(emitter, conversationId);
            streamTracker.attach(conversationId, emitter);
            AtomicBoolean approvalEmitterDone = new AtomicBoolean(false);

            sseExecutor.execute(() -> {
                AgentStreamAccumulator accumulator = newAccumulator();
                AtomicBoolean finalized = new AtomicBoolean(false);
                try {
                    // 广播 approval_resolved 事件
                    broadcastEvent(conversationId, "tool_approval_resolved", Map.of(
                            "pendingId", pending.getPendingId(),
                            "decision", decision,
                            "toolName", pending.getToolName(),
                            "timestamp", System.currentTimeMillis()
                    ));

                    if ("denied".equals(decision)) {
                        String denyMsg = "用户拒绝执行工具 " + pending.getToolName();
                        MessageEntity savedAssistant = conversationService.saveMessage(conversationId, "assistant", denyMsg);
                        broadcastEvent(conversationId, "message_start", Map.of("role", "assistant"));
                        broadcastEvent(conversationId, "content_delta", Map.of("delta", denyMsg));
                        broadcastEvent(conversationId, "message_complete", Map.of("status", "completed"));
                        broadcastEvent(conversationId, "done", buildDonePayload(
                                conversationId, "completed", savedAssistant, 0, 0,
                                isAssistantPersisted(savedAssistant),
                                conversationService.getMessageCount(conversationId)));
                        // deny 是正常 turn 终结，用户可能在 awaiting_approval 阶段排了消息
                        ChatStreamTracker.CompletionResult denyCr = streamTracker.completeAndConsumeIfLast(conversationId);
                        if (denyCr.allDone() && denyCr.queuedInput() != null) {
                            startQueuedMessage(conversationId, emitter, approvalEmitterDone, denyCr.queuedInput(), username, requesterUserIdOf(auth), requestBaseUrl);
                        } else {
                            completeEmitterQuietly(emitter, approvalEmitterDone);
                        }
                        return;
                    }

                    // approved: 使用已原子消费的记录触发 replay 流
                    if (finalConsumed == null) {
                        broadcastEvent(conversationId, "error", Map.of("message", "审批记录已被消费"));
                        broadcastEvent(conversationId, "done", Map.of("status", "completed"));
                        // 审批记录被另一个请求消费，但用户可能在等待期间排了消息
                        ChatStreamTracker.CompletionResult consumedNullCr = streamTracker.completeAndConsumeIfLast(conversationId);
                        if (consumedNullCr.allDone() && consumedNullCr.queuedInput() != null) {
                            startQueuedMessage(conversationId, emitter, approvalEmitterDone, consumedNullCr.queuedInput(), username, requesterUserIdOf(auth), requestBaseUrl);
                        } else {
                            completeEmitterQuietly(emitter, approvalEmitterDone);
                        }
                        return;
                    }

                    Long replayAgentId = finalConsumed.getAgentId() != null
                            ? Long.parseLong(finalConsumed.getAgentId()) : agentId;

                    broadcastEvent(conversationId, "message_start", Map.of("role", "assistant"));

                    // 不含工具名的中性 prompt（对齐 IM 渠道，防止 fallthrough 时误导 LLM）
                    String replayPrompt = "继续执行已批准的工具调用。";

                    streamTracker.incrementFlux(conversationId);
                    // RFC-063r §2.12: prefer the persisted Memento snapshot
                    // (covers cross-restart approval where the original channel
                    // is gone) and fall back to a fresh web-origin
                    // ChatOrigin when none was captured.
                    vip.mate.agent.context.ChatOrigin replayOrigin =
                            approvalService.restoreChatOrigin(finalConsumed.getChatOrigin());
                    if (replayOrigin == vip.mate.agent.context.ChatOrigin.EMPTY) {
                        // Carry the immutable requesterUserId so on-behalf-of
                        // identity forwarding (issue #459) asserts the real
                        // account on replayed tool calls, not the username.
                        replayOrigin = vip.mate.agent.context.ChatOrigin.web(
                                conversationId, username, workspaceId, null, null, requesterUserIdOf(auth));
                    }
                    // Carry the request-thread base URL so any file a replayed
                    // tool generates gets an absolute download link.
                    replayOrigin = replayOrigin.withBaseUrl(requestBaseUrl);
                    Disposable disposable = agentService.chatWithReplayStream(
                            replayAgentId, replayPrompt, conversationId, finalConsumed.getToolCallPayload(), username, replayOrigin)
                            .doOnNext(delta -> {
                                if (approvalEmitterDone.get()) return;
                                try {
                                    accumulator.accept(delta, conversationId);
                                } catch (Exception e) {
                                    log.warn("SSE replay broadcast error: {}", e.getMessage());
                                }
                            })
                            .doOnComplete(() -> {
                                if (!finalized.compareAndSet(false, true)) return;
                                // Force-recycle short-circuit: see main doOnComplete below.
                                if (streamTracker.isRecycled(conversationId)) {
                                    log.info("SSE replay doOnComplete skipped for force-recycled conversation: {}", conversationId);
                                    try {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                    } catch (Exception e) {
                                        log.debug("recycled-skip: stream_status reset failed for {}: {}",
                                                conversationId, e.getMessage());
                                    }
                                    ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                    if (cr.allDone()) {
                                        completeEmitterQuietly(emitter, approvalEmitterDone);
                                    }
                                    return;
                                }
                                // Replay can re-trigger an approval (the approved tool
                                // call may chain into another guarded tool). Derive status the same
                                // way as the normal stream so awaiting_approval doesn't get masked
                                // as completed.
                                boolean replayWasStopped = streamTracker.isStopRequested(conversationId);
                                ChatStreamTracker.InterruptType replayInterrupt = streamTracker.getInterruptType(conversationId);
                                boolean replayIsError = accumulator.getContent() != null
                                        && accumulator.getContent().startsWith("[错误] ");
                                String persistStatus = derivePersistStatus(
                                        accumulator.isAwaitingApproval(), replayIsError,
                                        replayWasStopped, replayInterrupt);
                                try {
                                    MessageEntity savedAssistant = null;
                                    List<MessageContentPart> parts = accumulator.toAssistantParts();
                                    String text = accumulator.getContent();
                                    if (!text.isBlank() || !parts.isEmpty()) {
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant", text, parts,
                                                persistStatus,
                                                accumulator.getPromptTokens(),
                                                accumulator.getCompletionTokens(),
                                                accumulator.getCacheReadTokens(),
                                                accumulator.getCacheWriteTokens(),
                                                accumulator.getReasoningTokens(),
                                                accumulator.getRuntimeModelName(),
                                                accumulator.getRuntimeProviderId(),
                                                accumulator.toMetadataJson());  // includes toolCalls metadata
                                    } else if (replayWasStopped) {
                                        boolean replayIsFollowup = replayInterrupt == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                                replayIsFollowup ? "[已中断]" : "[已停止生成]", null, persistStatus);
                                    } else {
                                        savedAssistant = saveEmptyAssistantPlaceholder(
                                                conversationId, persistStatus, accumulator, "SSE replay doOnComplete");
                                    }
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", persistStatus,
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !text.isBlank()
                                    ));
                                    int msgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, persistStatus, savedAssistant, 0, 0,
                                            isAssistantPersisted(savedAssistant), msgCount));
                                } catch (Exception e) {
                                    log.warn("SSE replay complete error: {}", e.getMessage());
                                } finally {
                                    ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                    if (cr.allDone()) {
                                        if (cr.queuedInput() != null) {
                                            startQueuedMessage(conversationId, emitter, approvalEmitterDone, cr.queuedInput(), username, requesterUserIdOf(auth), requestBaseUrl);
                                        } else {
                                            conversationService.updateStreamStatus(conversationId, "idle");
                                            completeEmitterQuietly(emitter, approvalEmitterDone);
                                        }
                                    }
                                }
                            })
                            .doOnError(e -> {
                                if (!finalized.compareAndSet(false, true)) return;
                                // Force-recycle short-circuit: see main doOnComplete below.
                                if (streamTracker.isRecycled(conversationId)) {
                                    log.info("SSE replay doOnError skipped for force-recycled conversation: {}, cause={}",
                                            conversationId, e.getMessage());
                                    try {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                    } catch (Exception ex) {
                                        log.debug("recycled-skip: stream_status reset failed for {}: {}",
                                                conversationId, ex.getMessage());
                                    }
                                    ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                    if (cr.allDone()) {
                                        completeEmitterQuietly(emitter, approvalEmitterDone);
                                    }
                                    return;
                                }

                                boolean isUserStop = e instanceof java.util.concurrent.CancellationException
                                        || (e.getCause() instanceof java.util.concurrent.CancellationException);
                                ChatStreamTracker.InterruptType replayInterruptType = streamTracker.getInterruptType(conversationId);
                                boolean replayIsFollowup = replayInterruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                                String errStatus = !isUserStop ? "failed"
                                        : replayIsFollowup ? "interrupted" : "stopped";

                                if (replayIsFollowup) {
                                    log.info("SSE replay stream interrupted for follow-up: conversationId={}", conversationId);
                                } else if (isUserStop) {
                                    log.info("SSE replay stream stopped by user: conversationId={}", conversationId);
                                } else {
                                    log.error("SSE replay error: {}", e.getMessage());
                                }

                                try {
                                    MessageEntity savedAssistant = null;
                                    List<MessageContentPart> replayParts = accumulator.toAssistantParts();
                                    String replayText = accumulator.getContent();
                                    if (!replayText.isBlank() || !replayParts.isEmpty()) {
                                        String savedText = replayText.isBlank() && isUserStop
                                                ? (replayIsFollowup ? "[已中断]" : "[已停止生成]") : replayText;
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, replayParts,
                                                errStatus,
                                                accumulator.getPromptTokens(),
                                                accumulator.getCompletionTokens(),
                                                accumulator.getCacheReadTokens(),
                                                accumulator.getCacheWriteTokens(),
                                                accumulator.getReasoningTokens(),
                                                accumulator.getRuntimeModelName(),
                                                accumulator.getRuntimeProviderId(),
                                                accumulator.toMetadataJson());
                                    } else if (isUserStop) {
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                                replayIsFollowup ? "[已中断]" : "[已停止生成]", null, errStatus);
                                    } else {
                                        savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                                "[错误] " + (e.getMessage() != null ? e.getMessage() : "replay error"),
                                                null, "failed");
                                    }

                                    if (replayIsFollowup) {
                                        broadcastEvent(conversationId, "message_complete", Map.of(
                                                "status", "interrupted",
                                                "hasThinking", !accumulator.getThinking().isBlank(),
                                                "hasContent", !replayText.isBlank()
                                        ));
                                        broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                                "conversationId", conversationId,
                                                "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                        ));
                                    } else if (isUserStop) {
                                        broadcastEvent(conversationId, "message_complete", Map.of(
                                                "status", "stopped",
                                                "hasThinking", !accumulator.getThinking().isBlank(),
                                                "hasContent", !replayText.isBlank()
                                        ));
                                        int stoppedMsgCount = conversationService.getMessageCount(conversationId);
                                        broadcastEvent(conversationId, "done", buildDonePayload(
                                                conversationId, "stopped", savedAssistant, 0, 0,
                                                isAssistantPersisted(savedAssistant), stoppedMsgCount));
                                    } else {
                                        broadcastEvent(conversationId, "error", buildErrorPayload(
                                                conversationId,
                                                e.getMessage() != null ? e.getMessage() : "replay error",
                                                savedAssistant));
                                    }
                                } catch (Exception ex) {
                                    log.warn("SSE replay error finalize failed: {}", ex.getMessage());
                                }
                                streamTracker.clearInterruptState(conversationId);
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    if (cr.queuedInput() != null) {
                                        startQueuedMessage(conversationId, emitter, approvalEmitterDone, cr.queuedInput(), username, requesterUserIdOf(auth), requestBaseUrl);
                                    } else {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                        completeEmitterQuietly(emitter, approvalEmitterDone);
                                    }
                                }
                            })
                            .subscribe(
                                    chunk -> { },
                                    err -> log.debug("SSE replay subscription terminated: {}", err.getMessage()),
                                    () -> log.debug("SSE replay subscription completed: conversationId={}", conversationId));

                    streamTracker.setDisposable(conversationId, disposable);
                    streamTracker.setEmergencySaveCallback(conversationId,
                            () -> emergencySaveAccumulator(conversationId, accumulator));

                } catch (Exception e) {
                    log.error("SSE approval replay setup error: {}", e.getMessage());
                    streamTracker.complete(conversationId);
                    completeEmitterQuietly(emitter, approvalEmitterDone);
                }
            });
            return emitter;
        }

        // ---- 重新生成（regenerate=true）：删除会话末尾的 assistant 回答块，
        // 复用 DB 中的种子 user 消息作为本轮输入，不重复持久化 user 行。
        // message 字段被忽略，以持久化的种子为准。 ----
        final boolean regenerate = Boolean.TRUE.equals(request.getRegenerate());
        final ConversationService.RegenerateSeed regenerateSeed;
        if (regenerate) {
            if (!conversationService.isConversationOwner(conversationId, username)) {
                sendErrorDoneAndComplete(emitter, "无权操作该会话");
                return emitter;
            }
            if (streamTracker.isRunning(conversationId)) {
                sendErrorDoneAndComplete(emitter, "正在生成回复，请先停止再重新生成");
                return emitter;
            }
            regenerateSeed = conversationService.prepareRegenerate(conversationId);
            if (regenerateSeed == null) {
                sendErrorDoneAndComplete(emitter, "当前没有可重新生成的回答");
                return emitter;
            }
            log.info("SSE regenerate: conversationId={}, seedMessageId={}",
                    conversationId, regenerateSeed.seedMessageId());
        } else {
            regenerateSeed = null;
        }
        final String message = regenerateSeed != null
                ? (regenerateSeed.content() != null ? regenerateSeed.content() : "")
                : requestMessage;

        // ---- 正常请求：注册流状态并附着首个订阅者 ----
        streamTracker.register(conversationId);
        streamTracker.bindRunMeta(conversationId, agentId, username);
        registerEmitterCallbacks(emitter, conversationId);
        streamTracker.attach(conversationId, emitter);

        // Per-emitter "the SSE channel is open and you should reset any
        // pending placeholder UI". Sent directly to the emitter rather than
        // broadcast so reconnecting subscribers don't see a duplicate marker
        // for an already-open conversation.
        try {
            sendEvent(emitter, "stream_started", Map.of(
                    "conversationId", conversationId,
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (IOException e) {
            log.debug("Failed to send stream_started event for {}: {}", conversationId, e.getMessage());
        }

        // 标记 emitter 是否已结束，防止 Flux 回调再次写入已关闭的 emitter
        AtomicBoolean emitterDone = new AtomicBoolean(false);

        sseExecutor.execute(() -> {
            AgentStreamAccumulator accumulator = newAccumulator();
            AtomicBoolean finalized = new AtomicBoolean(false);
            try {
                conversationService.getOrCreateConversation(conversationId, agentId, username, workspaceId);
                // Pin the model the user picked for this conversation so later
                // turns (and the runtime model resolver) honour it independently
                // of every other conversation.
                conversationService.updateConversationModel(conversationId,
                        request.getModelProvider(), request.getModelName());
                List<MessageContentPart> requestParts = regenerateSeed != null
                        ? regenerateSeed.parts()
                        : normalizeRequestParts(request);
                String promptText = buildPromptText(message, requestParts);
                if (regenerateSeed == null) {
                    // Regenerate reuses the already-persisted seed user row —
                    // inserting again would duplicate it (issue #547).
                    conversationService.saveMessage(conversationId, "user", message, requestParts);
                }
                conversationService.updateStreamStatus(conversationId, "running");

                broadcastEvent(conversationId, "session", Map.of(
                        "conversationId", conversationId,
                        "agentId", agentId
                ));
                broadcastEvent(conversationId, "message_start", Map.of(
                        "role", "assistant"
                ));

                streamTracker.incrementFlux(conversationId);
                // RFC-063r §2.5: web entry — null channelId / no ChannelTarget;
                // tools that need a workspace path read it from the agent (origin
                // is enriched with workspaceBasePath in StateGraph buildInitialState).
                vip.mate.agent.context.ChatOrigin webOrigin =
                        memoryOrigin(conversationId, username, requesterUserIdOf(auth), workspaceId, request.getEndUserId())
                                .withBaseUrl(requestBaseUrl);
                Disposable disposable = agentService.chatStructuredStream(agentId, promptText, conversationId, username, request.getThinkingLevel(), webOrigin)
                        .doOnNext(delta -> {
                            if (emitterDone.get()) return;
                            try {
                                accumulator.accept(delta, conversationId);
                            } catch (Exception e) {
                                log.warn("SSE broadcast error: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            if (!finalized.compareAndSet(false, true)) return;
                            // Force-recycle: the recycle path already wrote a
                            // "[已被用户中止]" placeholder (or the partial
                            // content via emergencySave). The agent's flux may
                            // have completed the same millisecond — skip its
                            // save + broadcast so we don't append a duplicate
                            // assistant row below the placeholder. Cleanup
                            // still runs so queue draining + emitter close
                            // happen normally.
                            if (streamTracker.isRecycled(conversationId)) {
                                log.info("SSE doOnComplete skipped for force-recycled conversation: {}", conversationId);
                                streamTracker.clearInterruptState(conversationId);
                                // Defensive: keep DB stream_status consistent with the
                                // "this turn is over" reality even when we skip the
                                // save. Force-recycle's controller path already wrote
                                // 'idle' for the recycled run, so this is normally a
                                // no-op — but if a register() ever fails to clear the
                                // marker (e.g. a different turn snuck through), this
                                // prevents the row leaking at 'running' across refresh.
                                try {
                                    conversationService.updateStreamStatus(conversationId, "idle");
                                } catch (Exception e) {
                                    log.debug("recycled-skip: stream_status reset failed for {}: {}",
                                            conversationId, e.getMessage());
                                }
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    completeEmitterQuietly(emitter, emitterDone);
                                }
                                return;
                            }
                            // 区分四种完成语义：
                            // 1. 正常完成（stopRequested=false）→ completed
                            // 2. 用户主动停止 → stopped
                            // 3. 用户中断后续跑（interrupt-with-followup）→ interrupted
                            // 4. LLM 客户端错误 / typed error → error
                            //    （NodeStreamingChatHelper 把 typed error 序列化为 "[错误] "
                            //     前缀的文本作为 content_delta 注入，accumulator 不区分，
                            //     这里靠前缀识别。打标 status='error' 后，BaseAgent
                            //     的 history sanitization 阶段会跳过这类消息，避免下次
                            //     prompt 被污染——DeepSeek thinking mode 缺
                            //     reasoning_content 立即 400，Claude 不接受 assistant
                            //     prefill 也 400，二者循环复制错误。）
                            boolean wasStopped = streamTracker.isStopRequested(conversationId);
                            ChatStreamTracker.InterruptType interruptType = streamTracker.getInterruptType(conversationId);
                            boolean isInterruptFollowup = interruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                            boolean isError = accumulator.getContent() != null
                                    && accumulator.getContent().startsWith("[错误] ");
                            String persistStatus = derivePersistStatus(
                                    accumulator.isAwaitingApproval(), isError, wasStopped, interruptType);
                            try {
                                MessageEntity savedAssistant = null;
                                List<MessageContentPart> assistantParts = accumulator.toAssistantParts();
                                String assistantText = accumulator.getContent();
                                if (!assistantText.isBlank() || !assistantParts.isEmpty()) {
                                    String savedText = assistantText.isBlank() && wasStopped
                                            ? (isInterruptFollowup ? "[已中断]" : "[已停止生成]") : assistantText;
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, assistantParts,
                                            persistStatus,
                                            accumulator.getPromptTokens(),
                                            accumulator.getCompletionTokens(),
                                            accumulator.getCacheReadTokens(),
                                            accumulator.getCacheWriteTokens(),
                                            accumulator.getReasoningTokens(),
                                            accumulator.getRuntimeModelName(),
                                            accumulator.getRuntimeProviderId(),
                                            accumulator.toMetadataJson());
                                } else if (wasStopped) {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                            isInterruptFollowup ? "[已中断]" : "[已停止生成]", null, persistStatus);
                                } else {
                                    savedAssistant = saveEmptyAssistantPlaceholder(
                                            conversationId, persistStatus, accumulator, "SSE doOnComplete");
                                }
                                // 发布对话完成事件（仅正常完成时；停止/中断/错误均不触发记忆提取）
                                // RFC-049 follow-up: also skip on isError — error turns persist
                                // garbage like "[错误] Bad request..." as the assistant reply,
                                // which would pollute the memory extraction pipeline if propagated.
                                if (!wasStopped && !isError) {
                                    // Attribute the memory write to the same owner the read
                                    // path recalled this turn — the publish runs in a reactive
                                    // completion callback after the origin holder is cleared,
                                    // so resolve from the captured webOrigin explicitly.
                                    completionPublisher.publish(agentId, conversationId, message, assistantText, "web",
                                            memoryOwnerResolver.resolve(webOrigin));
                                }

                                if (isInterruptFollowup) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "interrupted",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                            "conversationId", conversationId,
                                            "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                    ));
                                } else {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", persistStatus,
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    int msgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, persistStatus, savedAssistant,
                                            accumulator.getPromptTokens(), accumulator.getCompletionTokens(),
                                            isAssistantPersisted(savedAssistant), msgCount));
                                }
                            } catch (Exception e) {
                                log.warn("SSE complete error: {}", e.getMessage());
                            } finally {
                                streamTracker.clearInterruptState(conversationId);
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    // RFC follow-up (2026-04-27): the previous guard
                                    //   cr.queuedInput() != null && (isInterruptFollowup || !wasStopped)
                                    // dropped legitimate queued messages when the user stopped
                                    // the running turn and then sent a new message via the
                                    // enqueue path (not the interrupt-with-followup path) —
                                    // wasStopped=true + isInterruptFollowup=false made the
                                    // guard false, the consumed queuedInput was discarded,
                                    // and the user's new message vanished. The other 4 sites
                                    // in this controller already use the simpler "if queued,
                                    // run it" condition; align with them. If the user
                                    // genuinely doesn't want continuation, no message would
                                    // have been in messageQueue to begin with.
                                    if (cr.queuedInput() != null) {
                                        startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), username, requesterUserIdOf(auth), requestBaseUrl);
                                    } else {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                        // 延迟关闭 emitter，确保最后的事件都已发送
                                        sseExecutor.execute(() -> {
                                            try {
                                                Thread.sleep(100);
                                            } catch (InterruptedException ignored) {}
                                            completeEmitterQuietly(emitter, emitterDone);
                                        });
                                    }
                                } else {
                                    log.info("Original stream completed but replay still active, " +
                                            "keeping SSE emitter alive: conversationId={}", conversationId);
                                }
                            }
                        })
                        .doOnCancel(() -> {
                            boolean wasFirst = finalized.compareAndSet(false, true);
                            log.info("SSE doOnCancel fired: conversationId={}, wasFirst={}", conversationId, wasFirst);
                            if (!wasFirst) return;
                            // Force-recycle short-circuit: see doOnComplete above.
                            if (streamTracker.isRecycled(conversationId)) {
                                log.info("SSE doOnCancel skipped for force-recycled conversation: {}", conversationId);
                                streamTracker.clearInterruptState(conversationId);
                                try {
                                    conversationService.updateStreamStatus(conversationId, "idle");
                                } catch (Exception e) {
                                    log.debug("recycled-skip: stream_status reset failed for {}: {}",
                                            conversationId, e.getMessage());
                                }
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    completeEmitterQuietly(emitter, emitterDone);
                                }
                                return;
                            }
                            // 区分用户主动停止和 interrupt-with-followup
                            ChatStreamTracker.InterruptType interruptType = streamTracker.getInterruptType(conversationId);
                            boolean isInterruptFollowup = interruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                            String status = isInterruptFollowup ? "interrupted" : "stopped";

                            log.info("SSE stream cancelled ({}): conversationId={}", status, conversationId);
                            try {
                                MessageEntity savedAssistant = null;
                                List<MessageContentPart> assistantParts = accumulator.toAssistantParts();
                                String assistantText = accumulator.getContent();
                                if (!assistantText.isBlank() || !assistantParts.isEmpty()) {
                                    String savedText = assistantText.isBlank()
                                            ? (isInterruptFollowup ? "[已中断]" : "[已停止生成]") : assistantText;
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, assistantParts,
                                            status,
                                            accumulator.getPromptTokens(),
                                            accumulator.getCompletionTokens(),
                                            accumulator.getCacheReadTokens(),
                                            accumulator.getCacheWriteTokens(),
                                            accumulator.getReasoningTokens(),
                                            accumulator.getRuntimeModelName(),
                                            accumulator.getRuntimeProviderId(),
                                            accumulator.toMetadataJson());
                                } else {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                            isInterruptFollowup ? "[已中断]" : "[已停止生成]", null, status);
                                }

                                if (isInterruptFollowup) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "interrupted",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                            "conversationId", conversationId,
                                            "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                    ));
                                } else {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "stopped",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    int stoppedMsgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, "stopped", savedAssistant, 0, 0,
                                            isAssistantPersisted(savedAssistant), stoppedMsgCount));
                                }
                            } catch (Exception e) {
                                log.warn("SSE stop finalize error: {}", e.getMessage());
                            } finally {
                                streamTracker.clearInterruptState(conversationId);
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    if (cr.queuedInput() != null) {
                                        // 无论中断类型，都消费排队消息（修复 Disposable 不可用时队列被丢弃的 bug）
                                        startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), username, requesterUserIdOf(auth), requestBaseUrl);
                                    } else {
                                        conversationService.updateStreamStatus(conversationId, "idle");
                                        completeEmitterQuietly(emitter, emitterDone);
                                    }
                                }
                            }
                        })
                        .doOnError(e -> {
                            boolean wasFirst = finalized.compareAndSet(false, true);
                            if (!wasFirst) {
                                log.info("SSE doOnError skipped (finalized by doOnCancel): conversationId={}", conversationId);
                                return;
                            }
                            // Force-recycle short-circuit: see doOnComplete above.
                            if (streamTracker.isRecycled(conversationId)) {
                                log.info("SSE doOnError skipped for force-recycled conversation: {}, cause={}",
                                        conversationId, e.getMessage());
                                streamTracker.clearInterruptState(conversationId);
                                try {
                                    conversationService.updateStreamStatus(conversationId, "idle");
                                } catch (Exception ex) {
                                    log.debug("recycled-skip: stream_status reset failed for {}: {}",
                                            conversationId, ex.getMessage());
                                }
                                ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                                if (cr.allDone()) {
                                    completeEmitterQuietly(emitter, emitterDone);
                                }
                                return;
                            }

                            // CancellationException = 用户主动停止或中断续跑
                            boolean isUserStop = e instanceof java.util.concurrent.CancellationException
                                    || (e.getCause() instanceof java.util.concurrent.CancellationException);
                            ChatStreamTracker.InterruptType interruptType = streamTracker.getInterruptType(conversationId);
                            boolean isInterruptFollowup = interruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                            // 三态：interrupted > stopped > failed
                            String status = !isUserStop ? "failed"
                                    : isInterruptFollowup ? "interrupted" : "stopped";

                            if (isInterruptFollowup) {
                                log.info("SSE stream interrupted for follow-up (CancellationException): conversationId={}", conversationId);
                            } else if (isUserStop) {
                                log.info("SSE stream stopped by user (CancellationException): conversationId={}", conversationId);
                            } else if (isClientDisconnect(e)) {
                                log.warn("SSE client disconnected: conversationId={}, cause={}", conversationId, e.getMessage());
                            } else {
                                log.error("SSE stream error: conversationId={}, cause={}", conversationId, e.getMessage());
                            }

                            try {
                                List<MessageContentPart> assistantParts = accumulator.toAssistantParts();
                                String assistantText = accumulator.getContent();
                                log.info("SSE doOnError saving: conversationId={}, status={}, textLen={}, partsCount={}",
                                        conversationId, status, assistantText.length(), assistantParts.size());
                                String errorMsg = e.getMessage() != null ? e.getMessage() : "unknown error";
                                MessageEntity savedAssistant = null;
                                if (!assistantText.isBlank() || !assistantParts.isEmpty()) {
                                    String savedText = assistantText.isBlank() && isUserStop
                                            ? (isInterruptFollowup ? "[已中断]" : "[已停止生成]") : assistantText;
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", savedText, assistantParts,
                                            status,
                                            accumulator.getPromptTokens(),
                                            accumulator.getCompletionTokens(),
                                            accumulator.getCacheReadTokens(),
                                            accumulator.getCacheWriteTokens(),
                                            accumulator.getReasoningTokens(),
                                            accumulator.getRuntimeModelName(),
                                            accumulator.getRuntimeProviderId(),
                                            accumulator.toMetadataJson());
                                } else if (isUserStop) {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                            isInterruptFollowup ? "[已中断]" : "[已停止生成]", null, status);
                                } else {
                                    savedAssistant = conversationService.saveMessage(conversationId, "assistant", "[错误] " + errorMsg, null, "failed");
                                }

                                if (isInterruptFollowup) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "interrupted",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    broadcastEvent(conversationId, "turn_interrupted", Map.of(
                                            "conversationId", conversationId,
                                            "hasQueuedMessage", streamTracker.hasQueuedMessage(conversationId)
                                    ));
                                } else if (isUserStop) {
                                    broadcastEvent(conversationId, "message_complete", Map.of(
                                            "status", "stopped",
                                            "hasThinking", !accumulator.getThinking().isBlank(),
                                            "hasContent", !assistantText.isBlank()
                                    ));
                                    int stoppedMsgCount = conversationService.getMessageCount(conversationId);
                                    broadcastEvent(conversationId, "done", buildDonePayload(
                                            conversationId, "stopped", savedAssistant, 0, 0,
                                            isAssistantPersisted(savedAssistant), stoppedMsgCount));
                                } else {
                                    broadcastEvent(conversationId, "error", buildErrorPayload(conversationId, errorMsg, savedAssistant));
                                }
                            } catch (Exception ioException) {
                                log.error("SSE doOnError save/broadcast failed: conversationId={}, error={}",
                                        conversationId, ioException.getMessage(), ioException);
                            }
                            streamTracker.clearInterruptState(conversationId);
                            ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                            log.info("SSE doOnError cleanup: conversationId={}, allDone={}, isInterruptFollowup={}, hasQueued={}",
                                    conversationId, cr.allDone(), isInterruptFollowup, cr.queuedInput() != null);
                            if (cr.allDone()) {
                                // RFC follow-up (2026-04-27): the previous guard
                                //   cr.queuedInput()!=null && !(isUserStop && !isInterruptFollowup)
                                // tried to suppress continuation when the user "explicitly
                                // stopped" without an interrupt-with-followup. But the
                                // frontend's enqueue path doesn't set interruptType — it
                                // just calls requestStop + offers to messageQueue. From the
                                // server's POV that's "isUserStop=true, isInterruptFollowup=
                                // false, queue has content", which the guard mis-classified
                                // as "abort" and silently dropped the user's freshly-typed
                                // follow-up. Whoever puts a message in messageQueue means it
                                // — just run it. Aligns with doOnComplete and the 4 other
                                // queue-launch sites in this controller.
                                if (cr.queuedInput() != null) {
                                    startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), username, requesterUserIdOf(auth), requestBaseUrl);
                                } else {
                                    conversationService.updateStreamStatus(conversationId, "idle");
                                    completeEmitterQuietly(emitter, emitterDone);
                                }
                            }
                        })
                        .subscribe(
                                chunk -> { },
                                error -> log.debug("SSE stream subscription terminated with error: {}", error.getMessage()),
                                () -> log.debug("SSE stream subscription completed: conversationId={}", conversationId));

                // 将 Disposable 注册到 StreamTracker，以便 stop 端点可以取消它
                streamTracker.setDisposable(conversationId, disposable);
                // JVM 关闭时优雅落盘：避免 mvn spring-boot:run 重启 / SIGTERM 把
                // 进行中 turn 的 assistant 消息丢失（doOnError 来不及在 Hikari 关闭前执行）
                streamTracker.setEmergencySaveCallback(conversationId,
                        () -> emergencySaveAccumulator(conversationId, accumulator));

            } catch (Exception e) {
                log.error("SSE setup error: {}", e.getMessage());
                try {
                    broadcastEvent(conversationId, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "unknown error"));
                } catch (Exception ioException) {
                    log.warn("SSE setup failure event broadcast error: {}", ioException.getMessage());
                }
                streamTracker.complete(conversationId);
                conversationService.updateStreamStatus(conversationId, "idle");
                completeEmitterQuietly(emitter, emitterDone);
            }
        });

        return emitter;
    }

    /**
     * 停止指定会话的流式生成。
     * 取消 Flux 订阅（底层 HTTP 连接也会随之关闭），已生成的部分内容以 stopped 状态入库。
     * <p>
     * Stop 同时清理所有未 resolve 的 pending approval：当 LLM 在一个 turn 里连发了
     * 多个需要审批的工具调用、用户在中间 Stop 时，这些 pending 会一直留在 in-memory
     * pendingMap 里。下次刷新页面时 frontend 的 hydrate 链路（`getPendingApprovals` API
     * + 消息 metadata 里的 `pendingApproval` 字段）会反复弹出"允许 xxx 执行？"banner。
     * Stop 端点现在 deny 所有 pending、同步 update 受影响 message 的 metadata，并广播
     * tool_approval_resolved 让前端实时清理 UI。
     */
    @Operation(summary = "停止流式生成")
    @PostMapping("/{conversationId}/stop")
    public R<Map<String, Object>> stopStream(@PathVariable String conversationId, Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        // 权限校验：已认证用户需验证会话归属，匿名用户（permitAll）直接放行
        if (auth != null && !conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        boolean stopped = streamTracker.requestStop(conversationId);

        // Sweep ghost approvals — workflow.denyAllByConversation owns DB + metadata + memory
        // atomically; we only need to broadcast SSE events on the resulting outcomes.
        List<ResolveOutcome> denied = approvalService.denyAllByConversation(conversationId, username);
        int messagesRewritten = denied.stream().mapToInt(ResolveOutcome::messagesRewritten).sum();
        for (ResolveOutcome o : denied) {
            broadcastEvent(conversationId, "tool_approval_resolved", Map.of(
                    "pendingId", o.pendingId(),
                    "decision", "denied",
                    "toolName", o.toolName() != null ? o.toolName() : "",
                    "timestamp", System.currentTimeMillis()
            ));
        }

        log.info("Stop requested: conversationId={}, user={}, stopped={}, ghostPendingsCleared={}, messagesRewritten={}",
                conversationId, username, stopped, denied.size(), messagesRewritten);
        return R.ok(Map.of(
                "stopped", stopped,
                "ghostPendingsCleared", denied.size(),
                "messagesRewritten", messagesRewritten
        ));
    }

    /**
     * 在执行中追加一条后续消息：仅入队，等当前 turn 自然结束后再启动。
     * <p>
     * 对齐 Claude Code 行为：流式输出过程中收到新输入不会强制 dispose 当前 LLM 调用，
     * 而是仅入队。当前 turn 跑到 doOnComplete/doOnError 后由 startQueuedMessage 接管。
     * <p>
     * 旧行为（dispose 当前 disposable + 立即重启）会导致部分 LLM 输出被丢弃 + token 浪费，
     * 已废弃。如果未来需要"立即打断"语义，应该走 /stop（用户主动取消）+ 重新发起新消息的路径。
     */
    @Operation(summary = "排队后续消息（不打断当前流）")
    @PostMapping("/{conversationId}/interrupt")
    public R<Map<String, Object>> interruptStream(
            @PathVariable String conversationId,
            @RequestBody InterruptRequest request,
            Authentication auth) {
        String username = auth != null ? auth.getName() : "anonymous";
        if (auth != null && !conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }

        if (!streamTracker.isRunning(conversationId)) {
            return R.ok(Map.of("interrupted", false, "reason", "no_active_stream"));
        }

        String message = request.getMessage();
        Long agentId = request.getAgentId();
        List<MessageContentPart> contentParts = request.getContentParts();

        // 判断当前阶段（仅用于 reason 字段，行为对所有阶段一致：仅入队）
        boolean isAwaitingApproval = approvalService.findPendingByConversation(conversationId) != null;

        // 仅入队、不 dispose。延迟持久化到 startQueuedMessage（让 Asst-N 先在 doOnComplete 落库，
        // 否则 listMessages ORDER BY create_time ASC 会把 Q(N+1) 排到 Asst-N 前面）
        boolean queued = streamTracker.enqueueMessage(conversationId, message, agentId, false, contentParts);
        log.info("Enqueued follow-up message during running turn: conversationId={}, user={}, queueSize={}, awaitingApproval={}",
                conversationId, username, streamTracker.getQueueSize(conversationId), isAwaitingApproval);

        return R.ok(Map.of(
                "interrupted", false,
                "queued", queued,
                "queueSize", streamTracker.getQueueSize(conversationId),
                "reason", isAwaitingApproval ? "awaiting_approval" : "queued"
        ));
    }

    @lombok.Data
    public static class InterruptRequest {
        private String message;
        private Long agentId;
        /** 结构化内容片段（含图片等附件），排队消息带附件时由前端传入 */
        private List<MessageContentPart> contentParts;
    }

    /**
     * 同步对话
     */
    @Operation(summary = "同步对话")
    @PostMapping
    public R<String> chat(
            @RequestParam Long agentId,
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {

        String username = auth != null ? auth.getName() : null;
        if (username == null) {
            return R.fail(401, "未登录，请先登录");
        }
        conversationService.getOrCreateConversation(request.getConversationId(), agentId, username, workspaceId);
        conversationService.saveMessage(request.getConversationId(), "user", request.getMessage(), request.getContentParts());

        String promptText = buildPromptText(request.getMessage(), request.getContentParts());
        // Carry the web origin so per-owner memory recall (read) and the
        // post-conversation memory write below agree on the same owner key.
        vip.mate.agent.context.ChatOrigin webOrigin =
                memoryOrigin(request.getConversationId(), username, requesterUserIdOf(auth), workspaceId, request.getEndUserId());
        AgentService.ChatResult result = agentService.chatWithUsage(agentId, promptText, request.getConversationId(), webOrigin);
        String response = result.content();
        conversationService.saveMessage(request.getConversationId(), "assistant", response, null, "completed",
                result.promptTokens(), result.completionTokens(),
                result.runtimeModel(), result.runtimeProvider());
        completionPublisher.publish(agentId, request.getConversationId(), request.getMessage(), response, "web",
                memoryOwnerResolver.resolve(webOrigin));
        return R.ok(response);
    }

    @Operation(summary = "上传聊天附件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ChatUploadResponse> upload(
            @RequestParam String conversationId,
            @RequestPart("file") MultipartFile file,
            Authentication auth) throws IOException {

        String username = auth != null ? auth.getName() : "anonymous";
        // 校验会话归属（会话可能尚未创建，此时允许上传——后续 stream/chat 会创建并绑定用户）。
        // 注意：会话尚不存在时，附件暂存到默认目录（resolveUploadRoot 查不到会话即回退）；
        // 会话创建后读取走双重查找，仍能命中。
        if (conversationService.conversationExists(conversationId)
                && !conversationService.isConversationOwner(conversationId, username)) {
            return R.fail(403, "无权操作该会话");
        }
        if (file.isEmpty()) {
            return R.fail("上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String safeFilename = Path.of(originalFilename).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedName = System.currentTimeMillis() + "_" + safeFilename;
        Path uploadRoot = uploadLocationResolver.resolveUploadRoot(conversationId);
        // Sanitize the id before using it as a path segment — IM-channel ids like
        // "wecom:XXXX" carry a ':' that is illegal in a Windows filename and would
        // throw InvalidPathException here. Reads use the same sanitization.
        Path conversationDir = uploadRoot.resolve(ChatUploadLocationResolver.sanitizeSegment(conversationId));
        Files.createDirectories(conversationDir);
        Path target = conversationDir.resolve(storedName);
        file.transferTo(target);

        log.info("Chat attachment uploaded: conversationId={}, user={}, file={}", conversationId, username, target);

        ChatUploadResponse response = new ChatUploadResponse();
        response.setConversationId(conversationId);
        response.setFileName(originalFilename);
        response.setStoredName(storedName);
        response.setUrl("/api/v1/chat/files/" + conversationId + "/" + storedName);
        // 用 root 相对路径，避免暴露服务端绝对路径（uploadRoot 现在恒为绝对路径）。
        response.setPath(toRelativeUploadPath(uploadRoot, conversationId, storedName));
        response.setSize(file.getSize());
        response.setContentType(file.getContentType());
        return R.ok(response);
    }

    @Operation(summary = "读取聊天附件")
    @GetMapping("/files/{conversationId}/{storedName:.+}")
    public ResponseEntity<Resource> readUploadedFile(
            @PathVariable String conversationId,
            @PathVariable String storedName,
            Authentication auth) throws IOException {

        // 校验当前用户拥有该会话
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return ResponseEntity.status(403).build();
        }

        Path filePath = resolveUploadedFile(conversationId, storedName);
        if (filePath == null) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(filePath);
        String contentType = Files.probeContentType(filePath);
        // probeContentType 在部分平台不识别视频格式，通过扩展名 fallback
        if (contentType == null) {
            contentType = guessContentTypeByExtension(filePath.getFileName().toString());
        }
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (contentType != null) {
            try {
                mediaType = MediaType.parseMediaType(contentType);
            } catch (Exception ignored) {
            }
        }

        String encodedFilename = URLEncoder.encode(filePath.getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }

    @Operation(summary = "生成聊天附件的 PDF 预览（office 格式，soffice 转换）")
    @GetMapping("/files/{conversationId}/{storedName:.+}/preview")
    public ResponseEntity<byte[]> previewUploadedFile(
            @PathVariable String conversationId,
            @PathVariable String storedName,
            Authentication auth) {

        // Same ownership gate as the raw file endpoint.
        String username = auth != null ? auth.getName() : "anonymous";
        if (!conversationService.isConversationOwner(conversationId, username)) {
            return ResponseEntity.status(403).build();
        }

        // 415: the client asked to preview a format this endpoint won't convert.
        if (!officePreviewService.isConvertible(storedName)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }
        // 501: no soffice on this host — the UI degrades to a download link.
        if (!officePreviewService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        Path filePath = resolveUploadedFile(conversationId, storedName);
        if (filePath == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] pdf = officePreviewService.renderPdf(filePath);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(pdf);
        } catch (IOException e) {
            log.warn("[ChatController] office preview conversion failed for {}: {}", storedName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Resolve an uploaded attachment to its on-disk path, probing every
     * candidate conversation dir (workspace-scoped + legacy default, sanitized +
     * raw id) with a per-candidate path-traversal guard. Returns {@code null}
     * when no candidate holds the file.
     */
    private Path resolveUploadedFile(String conversationId, String storedName) {
        for (Path conversationDir : uploadLocationResolver.resolveCandidateConversationDirs(conversationId)) {
            Path normDir = conversationDir.normalize();
            Path candidate = normDir.resolve(storedName).normalize();
            if (Files.exists(candidate) && candidate.startsWith(normDir)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Build the {@link vip.mate.agent.context.ChatOrigin} that drives per-owner
     * memory isolation for a web request. When {@code endUserId} is supplied
     * (third-party single-account integration) the origin is attributed to that
     * external end-user ({@code api:<endUserId>}); otherwise to the logged-in
     * MateClaw user ({@code user:<username>}).
     */
    private vip.mate.agent.context.ChatOrigin memoryOrigin(String conversationId, String username,
                                                           Long requesterUserId, Long workspaceId,
                                                           String endUserId) {
        // Resolve the public base URL here, on the request thread, so it can ride
        // the origin into async tool execution where no request is bound. Tools
        // then mint absolute download links without operator config.
        String baseUrl = resolveRequestBaseUrl();
        if (endUserId != null && !endUserId.isBlank()) {
            // Third-party single-account integration: the requester is an external
            // end-user id, not a MateClaw account — no requesterUserId to assert.
            return vip.mate.agent.context.ChatOrigin
                    .web(conversationId, endUserId.trim(), workspaceId, null, baseUrl)
                    .withSender(null, "api", null);
        }
        // Authenticated web user: carry the immutable id so on-behalf-of identity
        // forwarding can assert "MateClaw authenticated this user" (not an anon id).
        return vip.mate.agent.context.ChatOrigin.web(conversationId, username, workspaceId, null, baseUrl, requesterUserId);
    }

    /**
     * Extract the authenticated user's immutable numeric id from the
     * {@link Authentication} details (stamped by {@code JwtAuthFilter} for both
     * the JWT and PAT paths). Null when not authenticated or details absent.
     */
    private Long requesterUserIdOf(org.springframework.security.core.Authentication auth) {
        if (auth == null) return null;
        Object details = auth.getDetails();
        return details instanceof Long id ? id : null;
    }

    /**
     * Resolve {@code scheme://host[:port][/contextPath]} from the current request,
     * honouring {@code X-Forwarded-*} when a {@code ForwardedHeaderFilter} is active.
     * Returns null off the request thread (caller falls back to config / relative).
     */
    private String resolveRequestBaseUrl() {
        try {
            return org.springframework.web.servlet.support.ServletUriComponentsBuilder
                    .fromCurrentContextPath().build().toUriString();
        } catch (Exception e) {
            return null;
        }
    }

    @lombok.Data
    public static class ChatRequest {
        private String message;
        private String conversationId = "default";
        private List<MessageContentPart> contentParts;
        /**
         * Optional third-party end-user identifier. When a single MateClaw
         * account (e.g. one PAT) fronts many of an external system's users,
         * pass that system's user id here so memory and recall are isolated
         * per end-user. Kept as a string (never coerced to a number) to
         * preserve precision of large external ids.
         */
        private String endUserId;
    }

    @lombok.Data
    public static class ChatUploadResponse {
        private String conversationId;
        private String fileName;
        private String storedName;
        private String url;
        private String path;
        private Long size;
        private String contentType;
    }

    @lombok.Data
    public static class ChatStreamRequest {
        private Long agentId;
        private String message;
        private String conversationId = "default";
        private List<MessageContentPart> contentParts;
        /** true 表示断线重连，不发送新消息，只附着到已有的流 */
        private Boolean reconnect;
        /**
         * Last SSE event id the client has already processed. Only meaningful
         * when {@link #reconnect} is true — the server skips events with
         * id &le; this value during buffer replay so the client doesn't
         * see them twice. 0 (or null) means "replay everything", matching
         * the legacy attach behavior for backwards compatibility.
         */
        private Long lastEventId;
        /** 思考深度：off / low / medium / high / max，null 表示跟随 Agent 默认 */
        private String thinkingLevel;
        /**
         * Provider id of the model the user picked for this conversation.
         * Paired with {@link #modelName}; null means "no per-conversation
         * override — use the agent / global default".
         */
        private String modelProvider;
        /** Model id the user picked for this conversation. See {@link #modelProvider}. */
        private String modelName;
        /**
         * Optional third-party end-user identifier — see
         * {@link ChatRequest#getEndUserId()}. Isolates memory per external
         * end-user when one MateClaw account fronts many of them.
         */
        private String endUserId;
        /**
         * true 表示重新生成：删除会话末尾的 assistant 回答块，复用其前最近一条
         * 已持久化的 user 消息作为本轮输入（{@link #message} 字段被忽略），且不
         * 重复插入 user 行。生成中的会话拒绝该请求。
         */
        private Boolean regenerate;
    }

    /**
     * 自动启动排队消息（interrupt-with-followup 或自然完成后的续跑逻辑）。
     * 接受由 {@link ChatStreamTracker#completeAndConsumeIfLast} 预先消费的 QueuedInput 快照。
     * 快照已脱离 RunState 生命周期，不受后续 complete/register 影响。
     * 支持链式续跑：queued stream 自身完成时也通过 completeAndConsumeIfLast 检查并递归调用。
     */
    private void startQueuedMessage(String conversationId, SseEmitter emitter, AtomicBoolean emitterDone,
                                    ChatStreamTracker.QueuedInput preConsumedInput, String requesterId,
                                    Long requesterUserId, String baseUrl) {
        if (preConsumedInput == null) {
            conversationService.updateStreamStatus(conversationId, "idle");
            completeEmitterQuietly(emitter, emitterDone);
            return;
        }

        // Rate Limit 防护：如果上一轮以 rate limit 错误结束，不立即续跑排队消息（必然再次 429）。
        // 改为持久化用户消息 + 通知前端"稍后重试"，避免连锁 429 浪费配额。
        String lastMessage = conversationService.getLastMessage(conversationId);
        if (lastMessage != null && (lastMessage.contains("频率过高") || lastMessage.contains("rate_limit")
                || lastMessage.contains("429") || lastMessage.contains("速率限制"))) {
            log.warn("Skipping queued message after rate limit error: conversationId={}, lastMessage={}",
                    conversationId, lastMessage.substring(0, Math.min(50, lastMessage.length())));
            // 持久化用户消息不丢失
            if (preConsumedInput.message() != null && !preConsumedInput.message().isBlank()
                    && !preConsumedInput.persisted()) {
                conversationService.saveMessage(conversationId, "user", preConsumedInput.message());
            }
            broadcastEvent(conversationId, "warning", Map.of(
                    "message", "上一轮请求触发了频率限制，排队消息已保存，请稍后重新发送"));
            broadcastEvent(conversationId, "done", Map.of("status", "rate_limited"));
            conversationService.updateStreamStatus(conversationId, "idle");
            completeEmitterQuietly(emitter, emitterDone);
            return;
        }

        String queuedMessage = preConsumedInput.message();
        Long agentId = preConsumedInput.agentId() != null ? preConsumedInput.agentId() : 1L;
        log.info("Starting queued message: conversationId={}, agentId={}, message={}",
                conversationId, agentId, queuedMessage.substring(0, Math.min(30, queuedMessage.length())));

        // 持久化排队的用户消息（含 contentParts；幂等：如果 /interrupt 已提前持久化则跳过）。
        // 这里持久化是为了确保 user 消息在 assistant 消息（doOnError/doOnCancel 已写入）之后落库，
        // 让 listMessages ORDER BY create_time ASC 后顺序正确：Q1 → Asst1 → Q2 → Asst2。
        if (queuedMessage != null && !queuedMessage.isBlank() && !preConsumedInput.persisted()) {
            conversationService.saveMessage(conversationId, "user", queuedMessage,
                    preConsumedInput.contentParts(), "queued");
        }

        // 广播 queued_input_started 事件
        broadcastEvent(conversationId, "queued_input_started", Map.of(
                "conversationId", conversationId,
                "message", queuedMessage
        ));

        // 重新注册流状态
        streamTracker.register(conversationId);
        streamTracker.attach(conversationId, emitter);

        // 启动新的流（复用现有 sseExecutor.execute 的逻辑模式）
        AgentStreamAccumulator accumulator = newAccumulator();
        AtomicBoolean finalized = new AtomicBoolean(false);

        broadcastEvent(conversationId, "message_start", Map.of("role", "assistant"));

        streamTracker.incrementFlux(conversationId);
        // RFC-063r §2.5: queued messages land in the same conversation; carry
        // a web-origin ChatOrigin so any cron job created during the queued
        // turn keeps a consistent (null-channel) binding. Carry the immutable
        // requesterUserId so on-behalf-of identity forwarding (issue #459) still
        // asserts the authenticated account, not just the mutable username.
        vip.mate.agent.context.ChatOrigin queuedOrigin =
                vip.mate.agent.context.ChatOrigin.web(conversationId, requesterId, null, null, null, requesterUserId)
                        .withBaseUrl(baseUrl);
        Disposable disposable = agentService.chatStructuredStream(agentId, queuedMessage, conversationId, requesterId, null, queuedOrigin)
                .doOnNext(delta -> {
                    if (emitterDone.get()) return;
                    try {
                        accumulator.accept(delta, conversationId);
                    } catch (Exception e) {
                        log.warn("SSE queued broadcast error: {}", e.getMessage());
                    }
                })
                .doOnComplete(() -> {
                    if (!finalized.compareAndSet(false, true)) return;
                    // RFC-067 §4.6: queued stream can hit a tool_approval_requested event
                    // mid-flight. Derive status via the shared helper so awaiting_approval
                    // is not silently downgraded to completed (which would prematurely fire
                    // expirePendingApprovals on the frontend and ghost-clear the banner).
                    boolean queuedWasStopped = streamTracker.isStopRequested(conversationId);
                    ChatStreamTracker.InterruptType queuedInterrupt = streamTracker.getInterruptType(conversationId);
                    boolean queuedIsError = accumulator.getContent() != null
                            && accumulator.getContent().startsWith("[错误] ");
                    String persistStatus = derivePersistStatus(
                            accumulator.isAwaitingApproval(), queuedIsError,
                            queuedWasStopped, queuedInterrupt);
                    try {
                        MessageEntity savedAssistant = null;
                        List<MessageContentPart> parts = accumulator.toAssistantParts();
                        String text = accumulator.getContent();
                        if (!text.isBlank() || !parts.isEmpty()) {
                            savedAssistant = conversationService.saveMessage(conversationId, "assistant", text, parts,
                                    persistStatus,
                                    accumulator.getPromptTokens(),
                                    accumulator.getCompletionTokens(),
                                    accumulator.getCacheReadTokens(),
                                    accumulator.getCacheWriteTokens(),
                                    accumulator.getReasoningTokens(),
                                    accumulator.getRuntimeModelName(),
                                    accumulator.getRuntimeProviderId(),
                                    accumulator.toMetadataJson());
                        } else if (queuedWasStopped) {
                            boolean queuedIsFollowup = queuedInterrupt == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                            savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                    queuedIsFollowup ? "[已中断]" : "[已停止生成]", null, persistStatus);
                        } else {
                            savedAssistant = saveEmptyAssistantPlaceholder(
                                    conversationId, persistStatus, accumulator, "SSE queued doOnComplete");
                        }
                        broadcastEvent(conversationId, "message_complete", Map.of(
                                "status", persistStatus,
                                "hasThinking", !accumulator.getThinking().isBlank(),
                                "hasContent", !text.isBlank()
                        ));
                        broadcastEvent(conversationId, "done", buildDonePayload(
                                conversationId, persistStatus, savedAssistant,
                                accumulator.getPromptTokens(), accumulator.getCompletionTokens(),
                                isAssistantPersisted(savedAssistant),
                                conversationService.getMessageCount(conversationId)));
                    } catch (Exception e) {
                        log.warn("SSE queued complete error: {}", e.getMessage());
                    } finally {
                        ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                        if (cr.allDone()) {
                            if (cr.queuedInput() != null) {
                                // 链式续跑：queued stream 期间又排了新消息
                                startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), requesterId, requesterUserId, baseUrl);
                            } else {
                                conversationService.updateStreamStatus(conversationId, "idle");
                                sseExecutor.execute(() -> {
                                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                                    completeEmitterQuietly(emitter, emitterDone);
                                });
                            }
                        }
                    }
                })
                .doOnError(e -> {
                    if (!finalized.compareAndSet(false, true)) return;
                    log.error("SSE queued stream error: conversationId={}, cause={}", conversationId, e.getMessage());
                    // 持久化已累积的 assistant 消息（修复：原逻辑未保存导致回答丢失）
                    try {
                        MessageEntity savedAssistant = null;
                        List<MessageContentPart> parts = accumulator.toAssistantParts();
                        String text = accumulator.getContent();
                        if (!text.isBlank() || !parts.isEmpty()) {
                            savedAssistant = conversationService.saveMessage(conversationId, "assistant", text, parts,
                                    "failed",
                                    accumulator.getPromptTokens(),
                                    accumulator.getCompletionTokens(),
                                    accumulator.getCacheReadTokens(),
                                    accumulator.getCacheWriteTokens(),
                                    accumulator.getReasoningTokens(),
                                    accumulator.getRuntimeModelName(),
                                    accumulator.getRuntimeProviderId(),
                                    accumulator.toMetadataJson());
                        } else {
                            String errorMsg = e.getMessage() != null ? e.getMessage() : "queued stream error";
                            savedAssistant = conversationService.saveMessage(conversationId, "assistant",
                                    "[错误] " + errorMsg, null, "failed");
                        }
                        broadcastEvent(conversationId, "error", buildErrorPayload(
                                conversationId,
                                e.getMessage() != null ? e.getMessage() : "queued stream error",
                                savedAssistant));
                    } catch (Exception saveEx) {
                        log.error("SSE queued doOnError save failed: {}", saveEx.getMessage());
                    }
                    ChatStreamTracker.CompletionResult cr = streamTracker.completeAndConsumeIfLast(conversationId);
                    if (cr.allDone()) {
                        if (cr.queuedInput() != null) {
                            startQueuedMessage(conversationId, emitter, emitterDone, cr.queuedInput(), requesterId, requesterUserId, baseUrl);
                        } else {
                            conversationService.updateStreamStatus(conversationId, "idle");
                            completeEmitterQuietly(emitter, emitterDone);
                        }
                    }
                })
                .subscribe();
        streamTracker.setDisposable(conversationId, disposable);
        streamTracker.setEmergencySaveCallback(conversationId,
                () -> emergencySaveAccumulator(conversationId, accumulator));
    }

    /**
     * Terminal error path for requests rejected before a stream is registered:
     * emit an {@code error} + terminal {@code done} pair and complete the
     * emitter, so the client's SSE reader exits cleanly instead of waiting
     * for a timeout.
     */
    private void sendErrorDoneAndComplete(SseEmitter emitter, String errorMessage) {
        try {
            sendEvent(emitter, "error", Map.of("message", errorMessage));
            sendEvent(emitter, "done", Map.of("status", "completed"));
        } catch (IOException e) {
            log.warn("SSE pre-stream error send failed: {}", e.getMessage());
        }
        emitter.complete();
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) throws IOException {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            payload = "{\"message\":\"serialization_error\"}";
        }
        emitter.send(SseEmitter.event().name(name).data(payload));
    }

    private void broadcastEvent(String conversationId, String name, Object data) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            payload = "{\"message\":\"serialization_error\"}";
        }
        streamTracker.broadcast(conversationId, name, payload);
    }

    /**
     * Derive the persistence status for an assistant message at stream finalization
     * (RFC-067 §4.6). Five-way state machine:
     * <ul>
     *   <li>{@code awaiting_approval} — accumulator hit a tool_approval_requested
     *       event; the turn is paused, not finished. queued / replay paths must not
     *       collapse this to {@code completed}.</li>
     *   <li>{@code error} — content carries the typed-error prefix from the LLM
     *       client. BaseAgent history sanitization skips these on the next prompt
     *       so error text doesn't poison the conversation.</li>
     *   <li>{@code completed} — clean finish, default case.</li>
     *   <li>{@code interrupted} — user halted the running turn AND queued a
     *       follow-up message (interrupt-with-followup).</li>
     *   <li>{@code stopped} — user pressed Stop without follow-up.</li>
     * </ul>
     * Package-private so {@link vip.mate.channel.web.ChatControllerPersistStatusTest}
     * can exercise the truth table directly without spinning up the controller.
     */
    static String derivePersistStatus(boolean isAwaitingApproval,
                                      boolean isError,
                                      boolean wasStopped,
                                      ChatStreamTracker.InterruptType interruptType) {
        if (isAwaitingApproval) return "awaiting_approval";
        if (isError)            return "error";
        if (!wasStopped)        return "completed";
        return interruptType == ChatStreamTracker.InterruptType.USER_INTERRUPT_WITH_FOLLOWUP
                ? "interrupted" : "stopped";
    }

    static String emptyAssistantPlaceholder(String status) {
        if ("awaiting_approval".equals(status)) return "[等待审批]";
        return "[本次没有输出]";
    }

    static boolean isAssistantPersisted(MessageEntity savedAssistant) {
        return savedAssistant != null;
    }

    /**
     * Build the value stored in {@code ChatUploadResponse.path} (and, downstream,
     * the message content part): a root-relative path like
     * {@code chat-uploads/{convId}/{storedName}}, never the absolute on-disk
     * location.
     * <p>
     * {@code uploadRoot} is always absolute (the resolver normalizes it via
     * {@code toAbsolutePath().normalize()}), and this field is purely
     * informational — it is rendered into the LLM prompt ("附件: foo (path)") and
     * returned to the client, while retrieval goes through the basename-based
     * {@code ChatUploadResolver} plus the {@code /api/v1/chat/files/...} URL. So
     * the absolute form must be avoided: it leaks the server's filesystem layout
     * into the prompt/response and breaks if the deploy directory ever moves.
     * <p>
     * The path is made relative to {@code uploadRoot}'s parent so the trailing
     * upload sub-directory name is preserved (e.g. {@code chat-uploads/...}), and
     * separators are normalized to {@code /} so the value is stable across OSes.
     */
    static String toRelativeUploadPath(Path uploadRoot, String conversationId, String storedName) {
        Path target = uploadRoot.resolve(ChatUploadLocationResolver.sanitizeSegment(conversationId)).resolve(storedName);
        Path base = uploadRoot.getParent();
        Path relative = base != null ? base.relativize(target) : target;
        return relative.toString().replace('\\', '/');
    }

    private MessageEntity saveEmptyAssistantPlaceholder(String conversationId, String status,
                                                       AgentStreamAccumulator accumulator, String source) {
        log.warn("{} with empty accumulator: conversationId={}, status={}, finishReason={}, phase={}, hasSegments={}",
                source, conversationId, status, accumulator.getFinishReason(),
                accumulator.getCurrentPhase(), !accumulator.segmentsEmpty());
        return conversationService.saveMessage(conversationId, "assistant",
                emptyAssistantPlaceholder(status), null, status,
                accumulator.getPromptTokens(),
                accumulator.getCompletionTokens(),
                accumulator.getCacheReadTokens(),
                accumulator.getCacheWriteTokens(),
                accumulator.getReasoningTokens(),
                accumulator.getRuntimeModelName(),
                accumulator.getRuntimeProviderId(),
                accumulator.toMetadataJson());
    }

    private Map<String, Object> buildDonePayload(String conversationId, String status, MessageEntity savedAssistant,
                                                 int promptTokens, int completionTokens,
                                                 boolean persisted, Integer messageCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (conversationId != null && !conversationId.isBlank()) payload.put("conversationId", conversationId);
        payload.put("status", status);
        if (savedAssistant != null && savedAssistant.getId() != null) {
            payload.put("assistantMessageId", savedAssistant.getId());
            // Surface runtime model attribution so the chat bubble can show
            // which model produced this reply without waiting for a history reload.
            if (savedAssistant.getRuntimeModel() != null && !savedAssistant.getRuntimeModel().isBlank()) {
                payload.put("runtimeModel", savedAssistant.getRuntimeModel());
            }
            if (savedAssistant.getRuntimeProvider() != null && !savedAssistant.getRuntimeProvider().isBlank()) {
                payload.put("runtimeProvider", savedAssistant.getRuntimeProvider());
            }
            // Surface the server-authoritative segments timeline. The live SSE
            // path builds metadata.segments from streamed deltas only, so
            // server-side annotations added at persist time (e.g. the
            // 'superseded' marker the SegmentSupersedeDetector writes onto
            // pre-tool model claims that the actual tool result replaced)
            // never reach the in-memory message until a page reload triggers
            // a refetch via /messages. Inlining them in the done payload lets
            // the client merge the markers onto its local segments by id
            // without an extra HTTP round-trip.
            String rawMetadata = savedAssistant.getMetadata();
            if (rawMetadata != null && !rawMetadata.isBlank()) {
                try {
                    Map<String, Object> parsed = objectMapper.readValue(rawMetadata,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    Object segs = parsed.get("segments");
                    if (segs instanceof java.util.List<?> list && !list.isEmpty()) {
                        payload.put("segments", segs);
                    }
                } catch (Exception ignored) {
                    // Best-effort: malformed metadata just means the client falls
                    // back to its existing "wait for reload" reconcile path.
                }
            }
        }
        if (promptTokens > 0) payload.put("promptTokens", promptTokens);
        if (completionTokens > 0) payload.put("completionTokens", completionTokens);
        // Cache / reasoning detail rides on the persisted row so the live bubble
        // can render the usage breakdown without waiting for a history reload.
        if (savedAssistant != null) {
            if (savedAssistant.getCacheReadTokens() != null && savedAssistant.getCacheReadTokens() > 0) {
                payload.put("cacheReadTokens", savedAssistant.getCacheReadTokens());
            }
            if (savedAssistant.getCacheWriteTokens() != null && savedAssistant.getCacheWriteTokens() > 0) {
                payload.put("cacheWriteTokens", savedAssistant.getCacheWriteTokens());
            }
            if (savedAssistant.getReasoningTokens() != null && savedAssistant.getReasoningTokens() > 0) {
                payload.put("reasoningTokens", savedAssistant.getReasoningTokens());
            }
        }
        payload.put("persisted", persisted);
        if (messageCount != null) payload.put("messageCount", messageCount);
        return payload;
    }

    private Map<String, Object> buildErrorPayload(String conversationId, String message, MessageEntity savedAssistant) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        if (conversationId != null && !conversationId.isBlank()) payload.put("conversationId", conversationId);
        if (savedAssistant != null && savedAssistant.getId() != null) {
            payload.put("assistantMessageId", savedAssistant.getId());
        }
        return payload;
    }

    /**
     * Snapshot the accumulator and persist it as an assistant message during JVM shutdown.
     * Invoked from {@link ChatStreamTracker#onShutdown()} so any in-flight turn doesn't
     * lose its already-streamed content + tool calls when the process exits.
     * <p>
     * Status routing (RFC-067 §4.6):
     * <ul>
     *   <li>If the accumulator was awaiting approval at shutdown, the message keeps
     *       {@code awaiting_approval}. After restart, {@code recoverFromDb} re-registers
     *       the pending in memory and the existing approval banner remains coherent
     *       with both DB and metadata. Falling back to {@code interrupted_shutdown}
     *       here would orphan the message metadata (UI would render "interrupted"
     *       while the approval is still recoverable).</li>
     *   <li>Otherwise {@code interrupted_shutdown} as before.</li>
     * </ul>
     * Idempotent w.r.t. the normal doOnComplete/doOnError save: if those paths already
     * persisted the message, this writes a second row, which is rare in practice
     * (race window is sub-second between dispose and save) and acceptable. Skipping
     * save when nothing to save avoids empty rows.
     */
    private void emergencySaveAccumulator(String conversationId, AgentStreamAccumulator accumulator) {
        try {
            String text = accumulator.getContent();
            List<MessageContentPart> parts = accumulator.toAssistantParts();
            if (text.isBlank() && parts.isEmpty()) {
                log.warn("[ChatController] Emergency save skipped (empty accumulator): conversationId={}, finishReason={}, phase={}, hasSegments={}",
                        conversationId, accumulator.getFinishReason(), accumulator.getCurrentPhase(),
                        !accumulator.segmentsEmpty());
                return;
            }
            boolean awaitingApproval = accumulator.isAwaitingApproval();
            String status = awaitingApproval ? "awaiting_approval" : "interrupted_shutdown";
            String savedText = text.isBlank()
                    ? (awaitingApproval ? "[等待审批 — 服务重启]" : "[已中断 — 服务重启]")
                    : text;
            conversationService.saveMessage(conversationId, "assistant", savedText, parts,
                    status,
                    accumulator.getPromptTokens(),
                    accumulator.getCompletionTokens(),
                    accumulator.getCacheReadTokens(),
                    accumulator.getCacheWriteTokens(),
                    accumulator.getReasoningTokens(),
                    accumulator.getRuntimeModelName(),
                    accumulator.getRuntimeProviderId(),
                    accumulator.toMetadataJson());
            log.info("[ChatController] Emergency-saved in-flight assistant message: " +
                    "conversationId={}, status={}, textLen={}, partsCount={}",
                    conversationId, status, text.length(), parts.size());
        } catch (Exception e) {
            log.error("[ChatController] Emergency save failed for {}: {}",
                    conversationId, e.getMessage(), e);
        }
    }

    private List<MessageContentPart> normalizeRequestParts(ChatStreamRequest request) {
        if (request.getContentParts() != null && !request.getContentParts().isEmpty()) {
            return request.getContentParts();
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return List.of();
        }
        MessageContentPart textPart = new MessageContentPart();
        textPart.setType("text");
        textPart.setText(request.getMessage());
        return List.of(textPart);
    }

    private String buildPromptText(String message, List<MessageContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return message != null ? message : "";
        }
        StringBuilder builder = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null || part.getType() == null) {
                continue;
            }
            switch (part.getType()) {
                case "text", "thinking" -> appendPromptLine(builder, part.getText());
                case "file" -> appendPromptLine(builder, "附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
                case "image" -> appendPromptLine(builder, "图片附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
                case "video" -> appendPromptLine(builder, "视频附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
                default -> appendPromptLine(builder, part.getText());
            }
        }
        return builder.toString().trim();
    }

    private void appendPromptLine(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private static final java.util.Map<String, String> MEDIA_CONTENT_TYPES = java.util.Map.of(
            "mp4", "video/mp4", "webm", "video/webm", "mov", "video/quicktime",
            "avi", "video/x-msvideo", "mkv", "video/x-matroska", "mpeg", "video/mpeg",
            "mp3", "audio/mpeg", "wav", "audio/wav", "ogg", "audio/ogg"
    );

    private static String guessContentTypeByExtension(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return MEDIA_CONTENT_TYPES.get(fileName.substring(dot + 1).toLowerCase());
    }

    /**
     * 注册 SseEmitter 的完整生命周期回调
     */
    private void registerEmitterCallbacks(SseEmitter emitter, String conversationId) {
        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed: conversationId={}", conversationId);
            // Detach immediately so a subsequent broadcast (heartbeat / async_task_*)
            // doesn't waste a send call on the zombie emitter and emit
            // "Removing dead subscriber ... ResponseBodyEmitter has already completed".
            streamTracker.detach(conversationId, emitter);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE emitter timeout: conversationId={}", conversationId);
            streamTracker.detach(conversationId, emitter);
            // 超时后显式 complete，防止 servlet 容器再抛 AsyncRequestTimeoutException
            emitter.complete();
        });
        emitter.onError(e -> {
            if (isClientDisconnect(e)) {
                log.debug("SSE client disconnected: conversationId={}, cause={}", conversationId, e.getMessage());
            } else {
                log.warn("SSE emitter error: conversationId={}, cause={}", conversationId, e.getMessage());
            }
            streamTracker.detach(conversationId, emitter);
        });
    }

    /**
     * 安全地完成 emitter，防止重复调用和已关闭连接引发的异常
     */
    private void completeEmitterQuietly(SseEmitter emitter, AtomicBoolean emitterDone) {
        if (!emitterDone.compareAndSet(false, true)) return;
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("Emitter already completed: {}", e.getMessage());
        }
    }

    /**
     * 判断异常是否为客户端断开连接（broken pipe、connection reset 等）
     */
    private boolean isClientDisconnect(Throwable e) {
        if (e instanceof IOException) return true;
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("broken pipe") || lower.contains("connection reset")
                || lower.contains("client abort") || lower.contains("closed");
    }

    /**
     * Build a per-stream accumulator wired to this controller's SSE
     * broadcast and phase tracking. Kept as a factory so every stream gets
     * its own instance while the fan-out semantics stay in one place.
     */
    private AgentStreamAccumulator newAccumulator() {
        return new AgentStreamAccumulator(objectMapper, new AgentStreamAccumulator.Sink() {
            @Override
            public void broadcast(String conversationId, String eventName, Object payload) {
                broadcastEvent(conversationId, eventName, payload);
            }

            @Override
            public void updatePhase(String conversationId, String phase) {
                streamTracker.updatePhase(conversationId, phase);
            }
        });
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
