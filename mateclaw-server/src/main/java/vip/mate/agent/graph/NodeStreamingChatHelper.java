package vip.mate.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.chatmodel.AssistantThinkingRelay;
import vip.mate.llm.chatmodel.ReasoningContentCache;

import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 节点级流式 LLM 调用辅助
 * <p>
 * 核心原则：模型流驱动渠道流，State 只保存最终聚合结果。
 * <ul>
 *   <li>调用 {@code chatModel.stream(prompt)}，逐 chunk 处理</li>
 *   <li>从每个 chunk 中提取 content delta 和 thinking delta（reasoningContent）</li>
 *   <li>通过 {@link ChatStreamTracker} 实时广播 content_delta / thinking_delta</li>
 *   <li>同时内部累积完整 text、thinking 和 tool calls</li>
 *   <li>流结束后返回 {@link StreamResult} 供节点写回 State</li>
 * </ul>
 * <p>
 * 所有面向用户的 LLM 节点（ReasoningNode、StepExecutionNode、PlanSummaryNode 等）
 * 统一使用此 helper，而不是各自散落 {@code chatModel.call()}。
 *
 * @author MateClaw Team
 */
@Slf4j
public class NodeStreamingChatHelper {

    private final ChatStreamTracker streamTracker;

    /**
     * Ordered fallback chain tried after the primary model exhausts retries.
     * Each entry is attempted once (no retry); the first successful response
     * wins. Empty list disables fallover entirely. See RFC-009.
     *
     * <p>Stored as {@link vip.mate.llm.failover.FallbackEntry} (providerId +
     * ChatModel) so the chain walker can consult {@link vip.mate.llm.failover.ProviderHealthTracker}
     * — cooldown state is keyed by providerId, not by ChatModel instance.</p>
     */
    private final List<vip.mate.llm.failover.FallbackEntry> fallbackChain;

    /** Optional cache-metrics aggregator; {@code null} in tests or when the bean is absent. */
    private final vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics;

    /** Optional per-provider health tracker; {@code null} in tests or when bean absent. */
    private final vip.mate.llm.failover.ProviderHealthTracker healthTracker;

    /**
     * Provider id of the primary {@link ChatModel} this helper drives. Used
     * by {@link #streamCallInternal} to consult / update {@link #healthTracker}
     * for the primary too — if a provider's API key is revoked, primary
     * cooldown lets us bypass the 5-retry stall on subsequent calls within
     * the same conversation. Falls back to {@code null} when unknown
     * (legacy callers, tests).
     */
    private final String primaryProviderId;

    /**
     * RFC-009 Phase 4: membership gate for usable providers. A provider is
     * removed from the pool on HARD errors (AUTH_ERROR / BILLING /
     * MODEL_NOT_FOUND) so subsequent requests skip it entirely without
     * burning a round-trip. {@code null} disables the gate (legacy callers,
     * tests) — every provider then counts as in-pool (fail-open).
     */
    private final vip.mate.llm.failover.AvailableProviderPool providerPool;

    public NodeStreamingChatHelper(ChatStreamTracker streamTracker) {
        this(streamTracker, List.of(), null, null, null, null);
    }

    /**
     * @deprecated use the full constructor — a single fallback cannot
     *     express the ordered multi-provider chain from RFC-009.
     */
    @Deprecated
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker, ChatModel fallbackModel) {
        this(streamTracker, wrap(fallbackModel), null, null, null, null);
    }

    /**
     * @deprecated use the full constructor.
     */
    @Deprecated
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker, ChatModel fallbackModel,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics) {
        this(streamTracker, wrap(fallbackModel), cacheMetrics, null, null, null);
    }

    /**
     * Chain constructor without health tracker — primarily for tests and
     * legacy wiring. Production callers should use the full constructor.
     */
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker,
                                   List<vip.mate.llm.failover.FallbackEntry> fallbackChain,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics) {
        this(streamTracker, fallbackChain, cacheMetrics, null, null, null);
    }

    /**
     * Constructor with health tracker but unknown primary provider — used by
     * tests where the helper isn't tied to a specific primary. Primary
     * health tracking is disabled for instances built this way.
     */
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker,
                                   List<vip.mate.llm.failover.FallbackEntry> fallbackChain,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics,
                                   vip.mate.llm.failover.ProviderHealthTracker healthTracker) {
        this(streamTracker, fallbackChain, cacheMetrics, healthTracker, null, null);
    }

    /**
     * Constructor that wires health tracker + primary provider id but leaves
     * the {@link vip.mate.llm.failover.AvailableProviderPool} disabled. Kept
     * so existing tests (e.g. {@code NodeStreamingChatHelperFailoverTest})
     * compile unchanged — they don't exercise the pool gate.
     */
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker,
                                   List<vip.mate.llm.failover.FallbackEntry> fallbackChain,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics,
                                   vip.mate.llm.failover.ProviderHealthTracker healthTracker,
                                   String primaryProviderId) {
        this(streamTracker, fallbackChain, cacheMetrics, healthTracker, primaryProviderId, null);
    }

    /**
     * Full constructor — preferred for production wiring. The
     * {@link vip.mate.llm.failover.AvailableProviderPool} hookup gates both
     * the primary short-circuit and the fallback walker; passing {@code null}
     * runs in fail-open mode (every provider counted as in-pool).
     */
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker,
                                   List<vip.mate.llm.failover.FallbackEntry> fallbackChain,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics,
                                   vip.mate.llm.failover.ProviderHealthTracker healthTracker,
                                   String primaryProviderId,
                                   vip.mate.llm.failover.AvailableProviderPool providerPool) {
        this.streamTracker = streamTracker;
        this.fallbackChain = fallbackChain == null ? List.of() : List.copyOf(fallbackChain);
        this.cacheMetrics = cacheMetrics;
        this.healthTracker = healthTracker;
        this.primaryProviderId = primaryProviderId;
        this.providerPool = providerPool;
    }

    private static List<vip.mate.llm.failover.FallbackEntry> wrap(ChatModel m) {
        // Legacy single-fallback path: providerId is unknown so health tracking
        // is silently disabled for that one entry (it gets a synthetic id).
        return m == null ? List.of() : List.of(new vip.mate.llm.failover.FallbackEntry("__legacy__", m));
    }

    /**
     * Record a single primary-model outcome to the health tracker. No-op when
     * either the tracker bean isn't wired or the primary's providerId is
     * unknown (e.g., tests, legacy callers built without the full constructor).
     */
    private void recordPrimary(boolean success) {
        if (healthTracker == null || primaryProviderId == null) return;
        if (success) healthTracker.recordSuccess(primaryProviderId);
        else healthTracker.recordFailure(primaryProviderId);
    }

    /**
     * Map an {@link ErrorType} to the matching pool
     * {@link vip.mate.llm.failover.AvailableProviderPool.RemovalSource} for
     * provider-wide HARD failures (AUTH / BILLING). Returns {@code null} for
     * SOFT errors, benign types, and model-scoped errors — those keep the
     * provider in-pool.
     *
     * <p>{@code MODEL_NOT_FOUND} is deliberately excluded: it means the
     * provider rejected one specific model id, not that the provider is
     * unusable. Evicting the whole provider would needlessly take its other
     * models offline. SOFT errors are absorbed by
     * {@link vip.mate.llm.failover.ProviderHealthTracker}'s cooldown instead.</p>
     */
    private static vip.mate.llm.failover.AvailableProviderPool.RemovalSource hardRemovalSource(ErrorType type) {
        if (type == null) return null;
        return switch (type) {
            case AUTH_ERROR -> vip.mate.llm.failover.AvailableProviderPool.RemovalSource.AUTH_ERROR;
            case BILLING -> vip.mate.llm.failover.AvailableProviderPool.RemovalSource.BILLING;
            default -> null;
        };
    }

    /**
     * True when {@code type} reflects the <i>provider's own health</i> (auth,
     * billing, rate limit, server error, empty response) rather than something
     * specific to the requested model or prompt. Only provider-level failures
     * should feed pool eviction and the consecutive-failure cooldown tracker —
     * a {@code MODEL_NOT_FOUND} / {@code CLIENT_ERROR} / {@code PROMPT_TOO_LONG}
     * says nothing about whether the provider's other models still work.
     */
    private static boolean isProviderLevelFailure(ErrorType type) {
        if (type == null) return false;
        return switch (type) {
            case NONE, PROMPT_TOO_LONG, CLIENT_ERROR, THINKING_BLOCK_ERROR, MODEL_NOT_FOUND -> false;
            default -> true;
        };
    }

    /** Convenience: pool-aware membership check. Null pool means fail-open (everyone in). */
    private boolean inPool(String providerId) {
        return providerPool == null || providerId == null || providerPool.contains(providerId);
    }

    /**
     * Remove the given provider from the pool if {@code errorType} is HARD
     * (AUTH_ERROR / BILLING / MODEL_NOT_FOUND). No-op when the pool is
     * disabled, the provider id is unknown, or the error is SOFT.
     */
    private void removeFromPool(String providerId, ErrorType errorType, String message) {
        if (providerPool == null || providerId == null) return;
        var source = hardRemovalSource(errorType);
        if (source == null) return;
        providerPool.remove(providerId, source, message != null ? message : errorType.name());
    }

    /** Defensively re-affirm pool membership after a successful call. Idempotent + cheap. */
    private void addToPool(String providerId) {
        if (providerPool == null || providerId == null) return;
        providerPool.add(providerId);
    }

    /**
     * 流式调用 LLM 并实时广播增量内容
     *
     * @param chatModel      LLM 模型
     * @param prompt         完整 prompt
     * @param conversationId 会话 ID，用于广播
     * @param phase          阶段标识，用于日志（如 "reasoning"、"step_execution"）
     * @return 聚合结果
     */
    public StreamResult streamCall(ChatModel chatModel, Prompt prompt,
                                   String conversationId, String phase) {
        return streamCallInternal(chatModel, prompt, conversationId, phase, true);
    }

    /**
     * 流式调用 LLM 但不广播增量内容到前端。
     * <p>
     * 用于 PlanGenerationNode 等返回结构化 JSON 的节点 —— LLM 输出不应直接展示给用户，
     * 需要后续解析后再决定是否广播。
     *
     * @param chatModel      LLM 模型
     * @param prompt         完整 prompt
     * @param conversationId 会话 ID（仅用于日志，不广播）
     * @param phase          阶段标识
     * @return 聚合结果
     */
    public StreamResult streamCallSilent(ChatModel chatModel, Prompt prompt,
                                          String conversationId, String phase) {
        return streamCallInternal(chatModel, prompt, conversationId, phase, false);
    }

    /**
     * 广播文本内容到前端（用于 silent 调用后手动推送 direct_answer 等）
     */
    public void broadcastContent(String conversationId, String content) {
        if (content != null && !content.isEmpty()) {
            broadcastDelta(conversationId, "content_delta", content);
        }
    }

    /**
     * Broadcast a lightweight progress event so the frontend shows activity
     * during silent LLM calls (e.g. triage). Sent as a "progress" SSE event.
     */
    public void broadcastProgress(String conversationId, String message) {
        if (streamTracker == null || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        streamTracker.broadcastObject(conversationId, "progress",
                Map.of("message", message != null ? message : ""));
    }

    // ==================== 重试配置 ====================

    /**
     * Soft upper bound on per-call thinking ({@code reasoning_content}) chars
     * with zero visible content and zero tool calls. Beyond this the helper
     * disposes the upstream subscription and returns a partial result so the
     * graph can advance instead of streaming thinking forever. Calibrated
     * against typical Claude/DeepSeek extended-thinking budgets — well above
     * normal long-form reasoning, low enough to bound a runaway loop in under
     * ~30 seconds of wall clock.
     */
    private static final int THINKING_ONLY_HARD_CAP_CHARS = 32768;

    /**
     * Narrow content-repetition guard — fires when the buffer ends with
     * the same period-sized chunk repeated {@link
     * #CONTENT_REPEAT_MAX_OCCURRENCES}+ times in a row. Picked to catch
     * the specific failure mode where reasoning-mode models (qwen3.6,
     * deepseek-r1) get into a "Wait, I should X. → 写答案 → Wait, I
     * should Y. → 写同一份答案 → …" self-arguing loop and emit the same
     * final-answer paragraph dozens of times until {@code max_tokens}
     * runs out.
     *
     * <p>Tests probe sizes from {@link #CONTENT_REPEAT_MIN_PERIOD} up
     * to {@link #CONTENT_REPEAT_MAX_PERIOD}; the smallest period that
     * yields the required consecutive copies trips the guard. 4
     * verbatim consecutive copies of any 24+ char unit is a near-
     * impossible coincidence in real text, so false positives are very
     * rare. Not as exhaustive as the previous {@code RepetitionDetector}
     * (removed at 42d406ff for being brittle on legitimate long-form
     * content), just the cheap specific check that catches this loop.
     */
    public static final int CONTENT_REPEAT_MIN_PERIOD = 24;
    public static final int CONTENT_REPEAT_MAX_PERIOD = 240;
    private static final int CONTENT_REPEAT_MAX_OCCURRENCES = 4;
    /**
     * Re-scan every N chars of new content. Smaller = faster reaction,
     * larger = less CPU. The probe loop is O(period_range × occurrences)
     * char comparisons per scan — cheap even at 400-char intervals.
     */
    private static final int CONTENT_REPEAT_CHECK_INTERVAL = 200;

    /**
     * Maximum retry attempts for SERVER_ERROR / transient network failures.
     * Total LLM calls per turn = MAX_RETRIES + 1 (attempt 0 is the initial,
     * attempts 1..MAX_RETRIES are the retries). Bumped from 5 to 10 in
     * commit 1dd99b68 so sustained wiki batch load can ride out provider
     * flaps without surfacing the error.
     *
     * <p>Package-private so {@code LaneDPerformanceFixesTest} can stay in
     * sync without a magic number — when this value changes again, the
     * test follows automatically.
     */
    static final int MAX_RETRIES = 10;
    // RATE_LIMIT: fail fast to failover chain — staying on the same
    // provider during a rate-limit window wastes time without recovery.
    // SERVER_ERROR keeps MAX_RETRIES (upstream flaps often self-heal).
    static final int MAX_RETRIES_RATE_LIMIT = 2;
    private static final long BACKOFF_BASE_MS = 3000;
    private static final long BACKOFF_CAP_MS = 60_000;

    private static final ObjectMapper TOOL_ARG_JSON_MAPPER = new ObjectMapper();

    /**
     * 判断错误是否可重试（基于状态码/异常类型）
     */
    private static boolean isRetryable(Throwable error) {
        String msg = extractFullErrorChain(error);
        // Kimi engine_overloaded / 标准 HTTP 错误 / 速率限制
        return msg.contains("engine_overloaded")
                || msg.contains("rate_limit") || msg.contains("RateLimitError")
                || msg.contains("429") || msg.contains("Too Many Requests")
                || msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")
                || msg.contains("APITimeoutError") || msg.contains("APIConnectionError")
                || msg.contains("Connection reset") || msg.contains("Connection refused");
    }

    /**
     * 分类错误类型（用于分级重试和上层 Node 决策）
     */
    private static ErrorType classifyError(Throwable error) {
        String msg = extractFullErrorChain(error);
        // PTL: prompt too long / context length exceeded
        if (msg.contains("prompt is too long")
                || msg.contains("context_length_exceeded")
                || msg.contains("context length exceeded")
                || msg.contains("maximum context length")
                || msg.contains("token limit")
                || msg.contains("This model's maximum context length")
                || msg.contains("请求体中的 input tokens 总数超出了模型允许")) {
            return ErrorType.PROMPT_TOO_LONG;
        }
        // Auth errors
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("Invalid API Key")
                || msg.contains("authentication") || msg.contains("AuthenticationError")) {
            return ErrorType.AUTH_ERROR;
        }
        // Rate limit
        if (msg.contains("429") || msg.contains("rate_limit") || msg.contains("RateLimitError")
                || msg.contains("Too Many Requests") || msg.contains("engine_overloaded")) {
            return ErrorType.RATE_LIMIT;
        }
        // Thinking block errors (Anthropic: old thinking blocks cannot be modified)
        if (msg.contains("thinking blocks cannot be modified")
                || msg.contains("thinking content is not allowed")
                || msg.contains("thinking block")) {
            return ErrorType.THINKING_BLOCK_ERROR;
        }
        // RFC-009 P3.2: BILLING — payment / quota exhausted. Distinct from AUTH because
        // a different provider may have credits, so we should fall back instead of
        // terminating the call. Both OpenAI ("insufficient_quota") and Anthropic
        // ("credit balance is too low") use these phrases in 402-class responses.
        if (msg.contains("402") || msg.contains("insufficient_quota")
                || msg.contains("credit balance is too low")
                || msg.contains("billing_error") || msg.contains("billing_hard_limit_reached")
                || msg.contains("You exceeded your current quota")
                || msg.contains("quota exceeded") || msg.contains("Quota exceeded")) {
            return ErrorType.BILLING;
        }
        // RFC-009 P3.2: MODEL_NOT_FOUND — provider rejects the requested model id.
        // Includes DashScope's "[InvalidParameter] url error, please check url"
        // (https://help.aliyun.com/zh/model-studio/error-code#error-url) which despite
        // the wording is the provider rejecting an unknown/unsupported model id on
        // the native protocol. Splitting this out from CLIENT_ERROR lets us hand off
        // to the fallback chain instead of terminating — a different provider may
        // recognize the model name (or have an equivalent default).
        if (msg.contains("Model not exist")
                || msg.contains("model_not_found")
                || msg.contains("Model not found")
                || msg.contains("does not exist")
                || msg.contains("[InvalidParameter]")
                || msg.contains("InvalidParameter")
                || msg.contains("url error")
                // Volcano Ark: model exists but the user's account hasn't opened it,
                // or the id isn't valid for this region. Both are hard failures —
                // retrying won't help, and a different provider may serve the model.
                || msg.contains("ModelNotOpen")
                || msg.contains("InvalidEndpointOrModel")) {
            return ErrorType.MODEL_NOT_FOUND;
        }
        // Client errors (400 Bad Request — unsupported format, invalid params, etc.) — NOT retryable
        if (msg.contains("400") || msg.contains("Bad Request")
                || msg.contains("invalid_request_error") || msg.contains("unsupported")) {
            return ErrorType.CLIENT_ERROR;
        }
        // Server errors and transient TLS / socket-level network hiccups.
        // Without the TLS-specific patterns, a single SSL fatal alert
        // (e.g. bad_record_mac during long-running streams) falls through to
        // UNKNOWN — non-retryable — so one transient handshake glitch surfaces
        // to the user as "LLM 调用失败" with no recovery attempt. These are
        // network-layer transients that almost always succeed on retry, so
        // they belong in the same retryable bucket as 5xx/timeouts.
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")
                || msg.contains("APITimeoutError") || msg.contains("APIConnectionError")
                || msg.contains("Connection reset") || msg.contains("Connection refused")
                || msg.contains("timeout") || msg.contains("Timeout")
                // TLS-layer transients: bad_record_mac (RFC 5246 §7.2.2 fatal
                // alert 20), aborted handshakes, mid-stream protocol errors.
                || msg.contains("SSLException") || msg.contains("SSLHandshakeException")
                || msg.contains("SSLProtocolException") || msg.contains("bad_record_mac")
                // Socket-level transients: a peer closing the TCP connection
                // mid-response, or the OS reporting a half-closed pipe.
                || msg.contains("SocketException") || msg.contains("Broken pipe")
                || msg.contains("Premature close") || msg.contains("PrematureCloseException")
                || msg.contains("Connection prematurely closed")
                || msg.contains("Connection closed prematurely")
                // Reactor Netty wraps the raw socket cause in WebClientRequestException;
                // surface that wrapper too so retries fire even when the cause chain
                // string is "WebClientRequestException ...; nested ... SSLException".
                || msg.contains("WebClientRequestException")
                // SiliconFlow and some other providers return "network connection error"
                // in the response body when their backend is under high load or the
                // upstream connection to the model server is disrupted. This is a
                // transient server-side failure — classify as retryable.
                || msg.contains("network connection error")) {
            return ErrorType.SERVER_ERROR;
        }
        return ErrorType.UNKNOWN;
    }

    /** 提取完整异常链信息用于关键字匹配 */
    private static String extractFullErrorChain(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = error;
        while (cur != null) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append(" | ");
            }
            sb.append(cur.getClass().getSimpleName()).append(" | ");
            // Include the HTTP response body for WebClient errors. Many providers
            // (Volcano Ark, Ollama, …) put the actionable error code only in the
            // body, while the surface message is just "404 Not Found from POST X".
            // Without this, classifyError() can never see codes like ModelNotOpen.
            if (cur instanceof WebClientResponseException wre) {
                try {
                    String body = wre.getResponseBodyAsString();
                    if (body != null && !body.isEmpty()) {
                        sb.append(body.length() > 1024 ? body.substring(0, 1024) : body)
                          .append(" | ");
                    }
                } catch (Exception ignored) {
                }
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private StreamResult streamCallInternal(ChatModel chatModel, Prompt prompt,
                                             String conversationId, String phase,
                                             boolean broadcast) {
        // 在开始 LLM 调用前检查停止标志
        if (streamTracker.isStopRequested(conversationId)) {
            log.info("[{}] Stop requested before LLM call, aborting: conversationId={}", phase, conversationId);
            throw new CancellationException("Stream stopped by user");
        }

        // RFC-009 P3.1 + Phase 4: short-circuit the primary retry loop in two cases.
        //   (a) primary is in cooldown (P3.3) — soft, transient
        //   (b) primary was HARD-removed from the pool (Phase 4) — auth/billing/missing model
        // Either way, retrying the same model wastes seconds; head straight to fallback.
        boolean primaryInCooldown = primaryProviderId != null
                && healthTracker != null
                && healthTracker.isInCooldown(primaryProviderId);
        boolean primaryOutOfPool = primaryProviderId != null && !inPool(primaryProviderId);
        boolean primarySkipped = primaryInCooldown || primaryOutOfPool;
        if (primarySkipped) {
            String reason = primaryOutOfPool ? "removed from pool" : "in cooldown";
            log.warn("[{}] Primary provider={} {} — skipping straight to fallback chain",
                    phase, primaryProviderId, reason);
            if (broadcast) {
                broadcastDelta(conversationId, "warning",
                        buildDeltaJson("主模型暂时不可用（" + (primaryOutOfPool ? "已下线" : "冷却中")
                                + "），直接尝试备选模型..."));
            }
        }

        // D-6: performance counters
        int retryCount = 0;
        long totalBackoffMs = 0;
        int failoverCount = 0;
        int llmCallCount = 0;
        long callStartMs = System.currentTimeMillis();

        // 主模型重试循环
        StreamResult lastResult = null;
        if (!primarySkipped) for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            llmCallCount++;
            if (attempt > 0) retryCount++;
            lastResult = doStreamCall(chatModel, prompt, conversationId, phase, broadcast, attempt);
            if (lastResult != null) {
                // PTL: 不重试，直接返回给上层 Node 处理
                if (lastResult.errorType() == ErrorType.PROMPT_TOO_LONG) {
                    return lastResult;
                }
                // AUTH: primary key 失效不会自愈，跳过同模型重试，交给 fallback chain
                // — 其它 provider 的 key 可能仍然可用（与 BILLING / MODEL_NOT_FOUND 同策略）。
                // recordPrimary(false) 仍记一次失败用于 healthTracker 冷却累计。
                // 若 fallback chain 全部 401，walker 末尾会把最后一次 AUTH_ERROR 透出，
                // 不会静默吞错。
                if (lastResult.errorType() == ErrorType.AUTH_ERROR) {
                    log.warn("[{}] Primary auth failed — skipping same-model retries, handing off to fallback chain", phase);
                    recordPrimary(false);
                    removeFromPool(primaryProviderId, ErrorType.AUTH_ERROR, lastResult.errorMessage());
                    break;
                }
                // BILLING — provider-side hard failure (out of credit). Won't change
                // on retry and affects every model on the provider, so evict it and
                // hand off to the fallback chain (a different provider may have credits).
                if (lastResult.errorType() == ErrorType.BILLING) {
                    log.warn("[{}] Primary billing failure — skipping same-model retries, handing off to fallback chain", phase);
                    recordPrimary(false);
                    removeFromPool(primaryProviderId, ErrorType.BILLING, lastResult.errorMessage());
                    break;
                }
                // MODEL_NOT_FOUND — the provider rejected this specific model id. The
                // provider itself is healthy, so do NOT evict it from the pool or
                // record a provider-level failure: that would take its sibling models
                // down too. Just skip same-model retries and hand off to the fallback
                // chain — a different provider may recognize the model name.
                if (lastResult.errorType() == ErrorType.MODEL_NOT_FOUND) {
                    log.warn("[{}] Primary model not found — handing off to fallback chain "
                            + "(provider kept available for its other models)", phase);
                    break;
                }
                // CLIENT_ERROR (400 Bad Request): 不重试（参数/格式错误重试也不会变）
                if (lastResult.errorType() == ErrorType.CLIENT_ERROR) {
                    return lastResult;
                }
                // THINKING_BLOCK_ERROR: 剥离旧 thinking 块后单次重试
                if (lastResult.errorType() == ErrorType.THINKING_BLOCK_ERROR && attempt == 0) {
                    log.warn("[{}] Thinking block error detected, stripping old thinking and retrying once", phase);
                    prompt = stripThinkingFromPrompt(prompt);
                    continue; // 重试一次
                }
                if (lastResult.errorType() == ErrorType.THINKING_BLOCK_ERROR) {
                    return lastResult; // 已经重试过了
                }
                // RFC-009: EMPTY_RESPONSE — break the primary-retry loop and fall through to
                // the fallback chain. Retrying the same model that returned nothing is rarely
                // productive; a different provider has a better chance of succeeding.
                if (lastResult.errorType() == ErrorType.EMPTY_RESPONSE) {
                    log.warn("[{}] Primary returned empty response — skipping same-model retries, handing off to fallback chain", phase);
                    recordPrimary(false);
                    break;
                }
                // 成功
                if (lastResult.errorMessage() == null || lastResult.errorType() == ErrorType.NONE) {
                    recordPrimary(true);
                    addToPool(primaryProviderId);
                    logPerfSummary(phase, conversationId, callStartMs, llmCallCount, retryCount, failoverCount);
                    return lastResult;
                }
                // RATE_LIMIT / SERVER_ERROR past their retry budget are provider-level
                // failures: the same model will not recover within this turn, but a
                // different provider can. Break to the fallback chain instead of
                // returning — recordPrimary(false) runs once at the post-loop provider
                // health check below, and if every fallback also fails the chain
                // walker re-surfaces this same error to the caller.
                if (lastResult.errorType() == ErrorType.RATE_LIMIT
                        || lastResult.errorType() == ErrorType.SERVER_ERROR) {
                    log.warn("[{}] Primary exhausted retries (type={}) — handing off to fallback chain",
                            phase, lastResult.errorType());
                    break;
                }
                // Any other non-null errored result (e.g. UNKNOWN) that doStreamCall
                // chose NOT to retry must exit — otherwise we silently spin through
                // attempts and waste seconds per turn on unrecoverable errors like
                // DashScope's "url error" / unknown model.
                recordPrimary(false);
                logPerfSummary(phase, conversationId, callStartMs, llmCallCount, retryCount, failoverCount);
                return lastResult;
            }
            // lastResult == null 表示需要重试
        }
        // If we exhausted the retry loop without a verdict, primary effectively
        // failed. Only count it against provider health for provider-level errors —
        // a MODEL_NOT_FOUND break above must not nudge the provider toward cooldown.
        if (!primarySkipped && lastResult != null && isProviderLevelFailure(lastResult.errorType())) {
            recordPrimary(false);
        }

        // Primary exhausted retries — walk the fallback chain in priority order.
        // Each fallback gets a single shot (no retry); first successful result wins.
        // Same-instance entries (e.g., primary accidentally included in the chain)
        // are skipped so we don't re-try the exact model that just failed.
        // Providers in cooldown (RFC-009 P3.3) are also skipped so a known-bad
        // provider doesn't add latency to every conversation turn.
        for (int i = 0; i < fallbackChain.size(); i++) {
            vip.mate.llm.failover.FallbackEntry entry = fallbackChain.get(i);
            ChatModel fallback = entry.chatModel();
            if (fallback == chatModel) continue;
            // RFC-009 Phase 4 — pool gate (the real runtime fence). A provider
            // HARD-removed earlier (or by another conversation) must not even
            // be attempted here. Build-time filtering is best-effort; this is
            // the one that matters when pool state changes mid-conversation.
            if (!inPool(entry.providerId())) {
                log.info("[{}] Skipping fallback {}/{} provider={} — not in pool",
                        phase, i + 1, fallbackChain.size(), entry.providerId());
                continue;
            }
            if (healthTracker != null && healthTracker.isInCooldown(entry.providerId())) {
                log.info("[{}] Skipping fallback {}/{} provider={} — in cooldown",
                        phase, i + 1, fallbackChain.size(), entry.providerId());
                continue;
            }
            log.warn("[{}] Primary exhausted, trying fallback {}/{} provider={} ({}) for conversation {}",
                    phase, i + 1, fallbackChain.size(), entry.providerId(),
                    fallback.getClass().getSimpleName(), conversationId);
            if (broadcast) {
                broadcastDelta(conversationId, "warning",
                        buildDeltaJson("主模型不可用，正在切换到备选模型 (" + (i + 1) + "/" + fallbackChain.size() + ")..."));
            }
            failoverCount++;
            llmCallCount++;
            StreamResult fallbackResult = doStreamCall(fallback, prompt, conversationId,
                    phase + "_fallback_" + (i + 1), broadcast, 0);
            // Accept only fully successful fallbacks. Non-successful results (auth
            // error, client error, still-rate-limited) propagate to the next
            // fallback instead of being surfaced as the final result.
            if (fallbackResult != null
                    && fallbackResult.errorType() == ErrorType.NONE
                    && fallbackResult.errorMessage() == null) {
                if (healthTracker != null) healthTracker.recordSuccess(entry.providerId());
                addToPool(entry.providerId());
                logPerfSummary(phase, conversationId, callStartMs, llmCallCount, retryCount, failoverCount);
                return fallbackResult;
            }
            // Only provider-level failures count toward the cooldown tracker. A
            // null result is a retryable soft failure; a MODEL_NOT_FOUND result is
            // model-scoped and must not penalise an otherwise-healthy provider.
            if (healthTracker != null
                    && (fallbackResult == null || isProviderLevelFailure(fallbackResult.errorType()))) {
                healthTracker.recordFailure(entry.providerId());
            }
            if (fallbackResult != null) {
                // HARD errors (auth / billing) evict the provider from the pool so
                // later walks skip it outright. SOFT and model-scoped errors keep it
                // in-pool — absorbed by the tracker's cooldown or simply retried.
                removeFromPool(entry.providerId(), fallbackResult.errorType(), fallbackResult.errorMessage());
                lastResult = fallbackResult; // remember most recent to report if the whole chain fails
            }
        }

        logPerfSummary(phase, conversationId, callStartMs, llmCallCount, retryCount, failoverCount);
        return lastResult != null ? lastResult
                : buildErrorResult("LLM 调用失败，已达最大重试次数", conversationId, phase);
    }

    /** D-6: log a structured performance summary for the LLM call phase. */
    private void logPerfSummary(String phase, String conversationId, long startMs,
                                int llmCallCount, int retryCount, int failoverCount) {
        long totalMs = System.currentTimeMillis() - startMs;
        log.info("[{}] perf_summary: conversationId={} total_ms={} llm_call_count={} retry_count={} failover_count={}",
                phase, conversationId, totalMs, llmCallCount, retryCount, failoverCount);
    }

    /**
     * 单次流式调用尝试。
     * @return StreamResult 如果成功/降级/不可重试；null 如果应该重试
     */
    private StreamResult doStreamCall(ChatModel chatModel, Prompt prompt,
                                       String conversationId, String phase,
                                       boolean broadcast, int attempt) {
        // PR-2 L4 (RFC-049 §2.4.2): normalize as a pre-egress step (not only on retry).
        // Strip reasoning_content from prior-turn AssistantMessages (i <= lastUserIdx),
        // preserving in-turn thinking (i > lastUserIdx) so DeepSeek's contract holds.
        // The returned Prompt shares `options` by reference with the input prompt.
        Prompt outbound = stripThinkingFromPrompt(prompt);

        // RFC-049 follow-up (2026-04-27): trim trailing AssistantMessage from the
        // outbound prompt. Triggered in practice by the summarizing→reasoning
        // graph transition: the summarizer emits an in-turn AssistantMessage,
        // graph state ends with it, reasoning's next LLM call sends history
        // ending with assistant. Anthropic Claude returns 400 "does not
        // support assistant message prefill"; some DeepSeek model variants 400
        // similarly. The dropped assistant is summarizer scaffolding, not
        // user-relevant content, so removing it before egress is safe.
        outbound = dropTrailingAssistant(outbound);

        // PR-2 L3 (RFC-049 §2.3.2): producer-side relay stash. Extract per-assistant
        // thinking from the normalized prompt (cross-turn positions are already "" due
        // to strip), stash with the caller's original `user` field, and overwrite
        // `options.user` with the relay token. The consumer in
        // AgentGraphBuilder.patchReasoningContent restores the original user when
        // rebuilding the outbound ChatCompletionRequest; the token never reaches the
        // provider. We only activate relay on OpenAiChatOptions paths — Anthropic has
        // its own thinking mechanism (extended thinking via AnthropicChatOptions.thinking).
        String relayToken = null;
        String originalUser = null;
        org.springframework.ai.openai.OpenAiChatOptions oaiOptsForRelay = null;
        if (outbound.getOptions() instanceof org.springframework.ai.openai.OpenAiChatOptions oaiOpts) {
            List<String> thinkings = extractAssistantThinkings(outbound);
            if (thinkings.stream().anyMatch(s -> !s.isEmpty())) {
                originalUser = oaiOpts.getUser();
                relayToken = AssistantThinkingRelay.stash(thinkings, originalUser);
                oaiOpts.setUser(relayToken);
                oaiOptsForRelay = oaiOpts;
            }
        }

        try {
            return doStreamCallInner(chatModel, outbound, conversationId, phase, broadcast, attempt);
        } finally {
            // Idempotent: if consumer already took the entry, discard is a no-op.
            if (relayToken != null) {
                AssistantThinkingRelay.discard(relayToken);
                if (oaiOptsForRelay != null) {
                    oaiOptsForRelay.setUser(originalUser);
                }
            }
        }
    }

    /**
     * PR-2: Extract per-assistant {@code reasoningContent} from a Prompt's messages in
     * order. Non-assistant messages are skipped; assistants with no metadata or no
     * reasoningContent yield {@code ""} so the returned list's positional index aligns
     * with the assistant-message index as seen by the consumer.
     */
    private static List<String> extractAssistantThinkings(Prompt prompt) {
        List<String> out = new ArrayList<>();
        for (Message m : prompt.getInstructions()) {
            if (m instanceof AssistantMessage am) {
                Map<String, Object> meta = am.getMetadata();
                Object rc = meta != null ? meta.get("reasoningContent") : null;
                out.add(rc instanceof String s ? s : "");
            }
        }
        return out;
    }

    private StreamResult doStreamCallInner(ChatModel chatModel, Prompt prompt,
                                            String conversationId, String phase,
                                            boolean broadcast, int attempt) {
        if (attempt > 0) {
            long delay = Math.min(BACKOFF_BASE_MS * (1L << (attempt - 1)), BACKOFF_CAP_MS);
            // 加入 jitter 防止雷群效应
            delay += ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 2));
            delay = Math.min(delay, BACKOFF_CAP_MS);
            log.warn("[{}] Retry attempt {}/{} after {}ms for conversation {}",
                    phase, attempt, MAX_RETRIES, delay, conversationId);
            // 广播给前端：用户可见的重试倒计时
            if (broadcast) {
                broadcastDelta(conversationId, "warning",
                        buildDeltaJson("⏱️ 请求频率受限，等待 " + (delay / 1000) + " 秒后重试（第 " + attempt + "/" + MAX_RETRIES + " 次）..."));
            }
            // Poll stop flag every 100ms so user Stop is honored mid-backoff.
            long remaining = delay;
            while (remaining > 0) {
                if (streamTracker != null && streamTracker.isStopRequested(conversationId)) {
                    log.info("[{}] Stop requested during backoff — aborting retry: conversationId={}",
                            phase, conversationId);
                    throw new CancellationException("Stream stopped by user");
                }
                long slice = Math.min(100, remaining);
                try {
                    Thread.sleep(slice);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return buildErrorResult("LLM 调用被中断", conversationId, phase);
                }
                remaining -= slice;
            }
        }

        StringBuilder contentAccum = new StringBuilder();
        StringBuilder thinkingAccum = new StringBuilder();
        List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<>();
        AtomicReference<AssistantMessage> lastAssistantMessage = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicInteger promptTokens = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);
        // RFC-014: Anthropic prompt cache 计数（其它 provider 永远为 0）
        AtomicInteger cacheReadTokens = new AtomicInteger(0);
        AtomicInteger cacheWriteTokens = new AtomicInteger(0);

        // thinking-only soft cap 触发后设为 true，外层轮询线程据此 dispose 订阅。
        // 注意：内容流的字符级 / 句子级重复检测已整体移除（设计取舍：
        // agent 不替模型审核输出退化，靠 max_tokens + max_iterations 兜底）；
        // 仅保留 thinking-only 这条体积兜底，处理 volcengine-plan 等 provider
        // 在 thinking 通道堆字符不出 content 的死循环（生产 trace c1eefa45）。
        AtomicBoolean thinkingOnlyCapTriggered = new AtomicBoolean(false);
        // Content-repetition guard: trips when the same paragraph-sized
        // suffix appears CONTENT_REPEAT_MAX_OCCURRENCES+ times in
        // contentAccum. The outer poll loop disposes the upstream
        // subscription within 500ms once flipped — same pattern as the
        // thinking-only cap above.
        AtomicBoolean contentRepeatCapTriggered = new AtomicBoolean(false);
        // Last contentAccum length at which we ran the repetition scan.
        // Throttles the O(n) substring scan so it runs at most once per
        // CONTENT_REPEAT_CHECK_INTERVAL chars, not on every chunk.
        AtomicInteger lastContentRepeatCheckLen = new AtomicInteger(0);

        // Lifecycle events emitted at most once per call so consumers can
        // pivot the UI between "thinking" and "drafting" without inspecting
        // delta rates.
        AtomicBoolean thinkingStartEmitted = new AtomicBoolean(false);
        AtomicBoolean thinkingEndEmitted = new AtomicBoolean(false);
        AtomicBoolean firstTokenSignaled = new AtomicBoolean(false);

        // Pre-stream lifecycle: tell the front-end how the prompt was sized
        // and which provider/model is being asked. These events ride on the
        // existing SSE bus, so the heartbeat / first_token signaling stays
        // consistent.
        if (broadcast && streamTracker != null && conversationId != null && !conversationId.isEmpty()) {
            int messageCount = prompt.getInstructions() != null ? prompt.getInstructions().size() : 0;
            int contextChars = approximatePromptChars(prompt);
            streamTracker.broadcastObject(conversationId, "context_prepared", Map.of(
                    "messages", messageCount,
                    "contextChars", contextChars,
                    "timestamp", System.currentTimeMillis()
            ));
            String modelId = identifyModel(chatModel);
            String providerId = primaryProviderId != null ? primaryProviderId : "";
            streamTracker.broadcastObject(conversationId, "llm_request_sent", Map.of(
                    "provider", providerId,
                    "model", modelId != null ? modelId : "",
                    "phase", phase != null ? phase : "",
                    "timestamp", System.currentTimeMillis()
            ));
        }

        CountDownLatch latch = new CountDownLatch(1);

        Disposable subscription = chatModel.stream(prompt)
                .doOnNext(chatResponse -> {
                    if (chatResponse == null || chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
                        return;
                    }
                    var generation = chatResponse.getResult();
                    AssistantMessage msg = generation.getOutput();
                    lastAssistantMessage.set(msg);

                    // thinking-only soft cap 已触发 → 跳过一切处理（等外层 dispose）
                    if (thinkingOnlyCapTriggered.get() || contentRepeatCapTriggered.get()) {
                        return;
                    }

                    // 1. 提取 content delta
                    String contentDelta = msg.getText();
                    if (contentDelta != null && !contentDelta.isEmpty()) {
                        // First content delta closes the thinking phase if one
                        // was open, and arms first-token heartbeat relaxation.
                        if (broadcast && streamTracker != null
                                && firstTokenSignaled.compareAndSet(false, true)) {
                            streamTracker.markFirstTokenReceived(conversationId);
                        }
                        if (broadcast && thinkingAccum.length() > 0
                                && thinkingEndEmitted.compareAndSet(false, true)) {
                            streamTracker.broadcastObject(conversationId, "thinking_end", Map.of(
                                    "thinkingChars", thinkingAccum.length(),
                                    "timestamp", System.currentTimeMillis()
                            ));
                        }
                        contentAccum.append(contentDelta);
                        if (broadcast) {
                            broadcastDelta(conversationId, "content_delta", contentDelta);
                        }
                    }

                    // 2. 提取 thinking delta. Do not cancel the stream for
                    // repeated thinking phrases: some models emit repetitive
                    // internal planning while still making valid tool progress.
                    String thinkingDelta = extractReasoningContent(msg);
                    if (thinkingDelta != null && !thinkingDelta.isEmpty()) {
                        // First-token signaling fires for thinking too — UI
                        // shows "thinking" activity before any content streams.
                        if (broadcast && streamTracker != null
                                && firstTokenSignaled.compareAndSet(false, true)) {
                            streamTracker.markFirstTokenReceived(conversationId);
                        }
                        // First thinking delta opens the thinking phase. We
                        // emit the start lazily (on first delta) rather than
                        // before subscription so models that never produce
                        // thinking don't ghost-pair an empty segment.
                        if (broadcast && thinkingAccum.length() == 0
                                && thinkingStartEmitted.compareAndSet(false, true)) {
                            streamTracker.broadcastObject(conversationId, "thinking_start", Map.of(
                                    "phase", phase != null ? phase : "",
                                    "timestamp", System.currentTimeMillis()
                            ));
                        }
                        thinkingAccum.append(thinkingDelta);
                        // thinkingLevel=off 时不广播 thinking（模型仍可能产生，但前端不展示）
                        boolean suppressThinking = "off".equalsIgnoreCase(
                                vip.mate.llm.chatmodel.ThinkingLevelHolder.get());
                        if (broadcast && !suppressThinking) {
                            broadcastDelta(conversationId, "thinking_delta", thinkingDelta);
                        }
                    }

                    // 3. 累积 tool calls（处理分片）
                    if (msg.hasToolCalls()) {
                        accumulateToolCalls(msg.getToolCalls(), toolCallAccumulators);
                    }

                    // 4. Thinking-only no-progress guard. MUST run after both
                    // content delta and tool call accumulation, otherwise a
                    // chunk that carries thinking AND a tool_call together
                    // (some Anthropic / DeepSeek-thinking responses do this)
                    // would trip the guard before we observe the tool_call —
                    // the user would see "INCOMPLETE: thinking-only" on a
                    // request that was actually about to dispatch a tool.
                    // Pattern-agnostic; fires on volume alone. Outer poll
                    // tears the subscription down within 500ms once the
                    // flag flips.
                    if (thinkingAccum.length() >= THINKING_ONLY_HARD_CAP_CHARS
                            && contentAccum.length() == 0
                            && toolCallAccumulators.isEmpty()
                            && !msg.hasToolCalls()) {
                        log.warn("[{}] Thinking-only soft cap reached " +
                                        "({} thinking chars, no content/tool yet) " +
                                        "— disposing stream for conversation {}",
                                phase, thinkingAccum.length(), conversationId);
                        broadcastContentTruncated(conversationId,
                                "thinking_only_no_content",
                                thinkingAccum.length());
                        thinkingOnlyCapTriggered.set(true);
                        return;
                    }

                    // 5. Content-repetition guard. Some reasoning-mode models
                    // (qwen3.6, deepseek-r1) get stuck in a "Wait, I should X
                    // → 写答案 → Wait, I should Y → 写同一份答案 → ..." loop
                    // and emit the same final-answer paragraph dozens of times
                    // until max_tokens runs out. Without this, the user sees a
                    // wall of duplicated text and the bot never actually finishes.
                    // Throttled to one scan per CONTENT_REPEAT_CHECK_INTERVAL
                    // chars of new content — the probe loop is cheap but no
                    // need to run on every chunk.
                    int currentLen = contentAccum.length();
                    int floor = CONTENT_REPEAT_MIN_PERIOD * CONTENT_REPEAT_MAX_OCCURRENCES;
                    if (currentLen >= floor
                            && currentLen - lastContentRepeatCheckLen.get() >= CONTENT_REPEAT_CHECK_INTERVAL) {
                        lastContentRepeatCheckLen.set(currentLen);
                        if (hasRepeatingSuffix(contentAccum, CONTENT_REPEAT_MIN_PERIOD,
                                               CONTENT_REPEAT_MAX_PERIOD,
                                               CONTENT_REPEAT_MAX_OCCURRENCES)) {
                            log.warn("[{}] Content-repetition cap reached " +
                                            "({} chars, tail repeated {}+ times) " +
                                            "— disposing stream for conversation {}",
                                    phase, currentLen, CONTENT_REPEAT_MAX_OCCURRENCES,
                                    conversationId);
                            broadcastContentTruncated(conversationId,
                                    "content_repetition",
                                    currentLen);
                            contentRepeatCapTriggered.set(true);
                            return;
                        }
                    }

                    // 4. 提取 token usage（通常最后一个 chunk 携带完整 usage）
                    if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                        var usage = chatResponse.getMetadata().getUsage();
                        if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                            promptTokens.set(usage.getPromptTokens().intValue());
                        }
                        if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) {
                            completionTokens.set(usage.getCompletionTokens().intValue());
                        }
                        // RFC-014: 反射抽取 Anthropic prompt cache 字段（DashScope/OpenAI 自然返回 0）
                        var cache = vip.mate.llm.cache.CacheUsageExtractor.extract(usage);
                        if (cache.cacheReadTokens() > 0)  cacheReadTokens.set(cache.cacheReadTokens());
                        if (cache.cacheWriteTokens() > 0) cacheWriteTokens.set(cache.cacheWriteTokens());
                    }
                })
                .subscribe(
                        chunk -> { /* 处理逻辑已在 doOnNext 中完成 */ },
                        err -> { errorRef.set(err); latch.countDown(); },
                        latch::countDown
                );

        // 阻塞等待流完成（节点本身是同步 NodeAction），每 500ms 检查一次停止/重复标志
        try {
            long deadlineMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
            while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                // thinking-only 软上限触发 → 立即 dispose 上游订阅，停止消耗 tokens
                if (thinkingOnlyCapTriggered.get()) {
                    log.warn("[{}] Stream guard tripped (thinking_only_no_content), disposing " +
                            "upstream subscription for conversation {}", phase, conversationId);
                    subscription.dispose();
                    if (broadcast) {
                        broadcastDelta(conversationId, "warning",
                                buildDeltaJson("模型在思考阶段停留过久，已自动截断"));
                    }
                    // dispose 后 latch 可能不会 countDown，直接跳出
                    break;
                }
                if (contentRepeatCapTriggered.get()) {
                    // Same dispose pattern as thinking-only cap. The
                    // accumulated content is preserved (it's the looping
                    // text — at least the user gets the FIRST occurrence
                    // as a partial answer instead of waiting for max_tokens).
                    log.warn("[{}] Stream guard tripped (content_repetition), disposing " +
                            "upstream subscription for conversation {}", phase, conversationId);
                    subscription.dispose();
                    if (broadcast) {
                        broadcastDelta(conversationId, "warning",
                                buildDeltaJson("检测到回答内容反复重复，已自动截断"));
                    }
                    break;
                }
                if (streamTracker.isStopRequested(conversationId)) {
                    // 用户主动停止 — 也 dispose 上游
                    subscription.dispose();
                    boolean hasContent = !contentAccum.isEmpty() || !thinkingAccum.isEmpty()
                            || !toolCallAccumulators.isEmpty();
                    if (hasContent) {
                        log.info("[{}] Stop requested during LLM call with partial content " +
                                        "(content={} chars, thinking={} chars, toolCalls={}), " +
                                        "returning stopped partial result: conversationId={}",
                                phase, contentAccum.length(), thinkingAccum.length(),
                                toolCallAccumulators.size(), conversationId);
                        return assembleStoppedResult(contentAccum, thinkingAccum, toolCallAccumulators,
                                promptTokens.get(), completionTokens.get(),
                                cacheReadTokens.get(), cacheWriteTokens.get(), phase);
                    }
                    log.info("[{}] Stop requested during LLM call, no content accumulated, aborting: conversationId={}",
                            phase, conversationId);
                    throw new CancellationException("Stream stopped by user");
                }
                if (System.currentTimeMillis() > deadlineMs) {
                    subscription.dispose();
                    log.warn("[{}] Stream call timed out for conversation {}", phase, conversationId);
                    return buildErrorResult("LLM 调用超时", conversationId, phase);
                }
            }
        } catch (InterruptedException e) {
            subscription.dispose();
            Thread.currentThread().interrupt();
            return buildErrorResult("LLM 调用被中断", conversationId, phase);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            boolean hasAccumulatedContent = !contentAccum.isEmpty() || !toolCallAccumulators.isEmpty();

            if (hasAccumulatedContent) {
                // ===== 优雅降级：LLM 已产出部分内容（如 engine_overloaded 在流尾部触发） =====
                log.warn("[{}] Stream error after partial content ({} chars, {} tool calls), " +
                                "using accumulated content as partial result: {}",
                        phase, contentAccum.length(), toolCallAccumulators.size(), error.getMessage());
                if (broadcast) {
                    broadcastDelta(conversationId, "warning",
                            buildDeltaJson("LLM 响应中断，使用已生成的部分内容继续"));
                }
                return assembleResult(contentAccum, thinkingAccum, toolCallAccumulators,
                        promptTokens.get(), completionTokens.get(),
                        cacheReadTokens.get(), cacheWriteTokens.get(),
                        phase, true, error.getMessage());
            }

            // ===== 无内容：分类错误并决定是否重试 =====
            ErrorType errorType = classifyError(error);

            // PTL: 不重试，返回给上层 Node 处理压缩
            if (errorType == ErrorType.PROMPT_TOO_LONG) {
                log.warn("[{}] Prompt too long error, returning to node for compaction: {}",
                        phase, error.getMessage());
                return buildErrorResultWithType("Prompt 过长: " + extractUserFriendlyError(error),
                        conversationId, phase, errorType);
            }

            // Auth: 不重试
            if (errorType == ErrorType.AUTH_ERROR) {
                log.error("[{}] Authentication error, not retrying: {}", phase, error.getMessage());
                return buildErrorResultWithType("认证失败: " + extractUserFriendlyError(error),
                        conversationId, phase, errorType);
            }

            // Client error (400): 不重试（参数/格式错误重试也不会变）
            if (errorType == ErrorType.CLIENT_ERROR) {
                log.error("[{}] Client error (400), not retrying: {}", phase, error.getMessage());
                return buildErrorResultWithType("Bad request: " + extractUserFriendlyError(error),
                        conversationId, phase, errorType);
            }

            // Rate limit / Server error: retryable, but with different budgets.
            // RATE_LIMIT: cap at 2 retries then failover (RFC 06 D-2).
            // SERVER_ERROR: keep full MAX_RETRIES — upstream flaps often self-heal.
            if (errorType == ErrorType.RATE_LIMIT || errorType == ErrorType.SERVER_ERROR) {
                int effectiveMaxRetries = (errorType == ErrorType.RATE_LIMIT)
                        ? MAX_RETRIES_RATE_LIMIT : MAX_RETRIES;
                if (attempt < effectiveMaxRetries) {
                    log.warn("[{}] Retryable error (attempt {}/{}, type={}): {}",
                            phase, attempt, effectiveMaxRetries, errorType, error.getMessage());
                    return null;  // 返回 null 触发重试
                }
            }

            // 不可重试或已耗尽重试
            log.error("[{}] LLM call failed after {} attempts for conversation {}: {}",
                    phase, attempt + 1, conversationId, error.getMessage());
            return buildErrorResultWithType("LLM 调用失败: " + extractUserFriendlyError(error),
                    conversationId, phase, errorType);
        }

        // ===== 成功（检查是否因 thinking-only 软上限或内容重复被截断） =====
        boolean truncatedByThinkingCap = thinkingOnlyCapTriggered.get();
        boolean truncatedByContentRepeat = contentRepeatCapTriggered.get();
        boolean truncated = truncatedByThinkingCap || truncatedByContentRepeat;
        if (truncatedByThinkingCap) {
            log.warn("[{}] LLM stream disposed: thinking-only soft cap reached for conversation {}",
                    phase, conversationId);
        } else if (truncatedByContentRepeat) {
            log.warn("[{}] LLM stream disposed: content-repetition cap reached for conversation {}",
                    phase, conversationId);
        }

        // RFC-009: guard against silent empty responses. Some providers return
        // HTTP 200 with an empty body under soft-failure conditions (rate-limit
        // capacity, context filter, upstream overload). Treat this as a failure
        // signal so streamCallInternal can hand off to the fallback chain.
        // Only fire when neither truncation cap fired (those deliberately
        // produce non-empty output) and there are no tool calls
        // (tool-only responses are legitimately empty-text).
        if (!truncated
                && contentAccum.length() == 0
                && thinkingAccum.length() == 0
                && toolCallAccumulators.isEmpty()) {
            log.warn("[{}] LLM returned empty response (no content, no thinking, no tool calls) — marking as EMPTY_RESPONSE for fallback", phase);
            return buildErrorResultWithType("LLM 返回空响应", conversationId, phase, ErrorType.EMPTY_RESPONSE);
        }

        String truncationReason = truncatedByThinkingCap ? "thinking_only_no_content"
                : truncatedByContentRepeat ? "content_repetition"
                : null;
        return assembleResult(contentAccum, thinkingAccum, toolCallAccumulators,
                promptTokens.get(), completionTokens.get(),
                cacheReadTokens.get(), cacheWriteTokens.get(), phase,
                truncated,
                truncationReason);
    }

    /** 组装 stopped partial 结果（用户主动停止，有已累积内容） */
    private StreamResult assembleStoppedResult(StringBuilder contentAccum, StringBuilder thinkingAccum,
                                                List<ToolCallAccumulator> toolCallAccumulators,
                                                int promptTok, int completionTok,
                                                int cacheReadTok, int cacheWriteTok, String phase) {
        List<AssistantMessage.ToolCall> finalToolCalls = buildFinalToolCalls(toolCallAccumulators);
        String fullContent = contentAccum.toString();
        String fullThinking = thinkingAccum.toString();

        // Fallback: <think> 标签提取
        if (fullThinking.isEmpty() && fullContent.contains("<think>")) {
            var extracted = extractThinkTags(fullContent);
            if (!extracted.thinking.isEmpty()) {
                fullThinking = extracted.thinking;
                fullContent = extracted.content;
            }
        }

        AssistantMessage assembledMessage = buildAssistantMessageWithThinking(fullContent, fullThinking, finalToolCalls);

        // Cache reasoning_content for MiMo-style providers that require it on
        // subsequent turns.
        cacheReasoningContent(fullThinking, finalToolCalls);

        recordCacheMetrics(phase, promptTok, completionTok, cacheReadTok, cacheWriteTok);
        return new StreamResult(fullContent, fullThinking, assembledMessage,
                finalToolCalls, !finalToolCalls.isEmpty(), promptTok, completionTok,
                true, null, ErrorType.NONE, true, cacheReadTok, cacheWriteTok);
    }

    /** 组装最终 StreamResult（成功或 partial） */
    private StreamResult assembleResult(StringBuilder contentAccum, StringBuilder thinkingAccum,
                                         List<ToolCallAccumulator> toolCallAccumulators,
                                         int promptTok, int completionTok,
                                         int cacheReadTok, int cacheWriteTok,
                                         String phase, boolean partial, String errorMsg) {
        List<AssistantMessage.ToolCall> finalToolCalls = buildFinalToolCalls(toolCallAccumulators);
        String fullContent = contentAccum.toString();
        String fullThinking = thinkingAccum.toString();

        // Fallback: <think> 标签提取
        if (fullThinking.isEmpty() && fullContent.contains("<think>")) {
            var extracted = extractThinkTags(fullContent);
            if (!extracted.thinking.isEmpty()) {
                fullThinking = extracted.thinking;
                fullContent = extracted.content;
                log.debug("[{}] Extracted <think> tags from content: {} thinking chars, {} content chars",
                        phase, fullThinking.length(), fullContent.length());
            }
        }

        AssistantMessage assembledMessage = buildAssistantMessageWithThinking(fullContent, fullThinking, finalToolCalls);

        // Cache reasoning_content for MiMo-style providers that require it on
        // subsequent turns. The cache replays real values instead of empty strings.
        cacheReasoningContent(fullThinking, finalToolCalls);

        recordCacheMetrics(phase, promptTok, completionTok, cacheReadTok, cacheWriteTok);
        return new StreamResult(fullContent, fullThinking, assembledMessage,
                finalToolCalls, !finalToolCalls.isEmpty(), promptTok, completionTok,
                partial, errorMsg, ErrorType.NONE, false, cacheReadTok, cacheWriteTok);
    }

    /**
     * PR-2 L2 (RFC-049): Build an {@link AssistantMessage} that persists the per-turn
     * {@code fullThinking} into the message's properties under key {@code "reasoningContent"}.
     *
     * <p>This is the linchpin of the structural fix: without writing thinking back into
     * the AssistantMessage that enters the next ReAct round's state, the outbound
     * request's {@code reasoning_content} is lost (Spring AI 1.1.4's
     * {@code OpenAiChatModel.lambda$createRequest$20} hardcodes {@code null} on the
     * outbound conversion, so the relay in {@code AssistantThinkingRelay} is the only
     * way back — see RFC-049 §2.3 L3).
     *
     * <p>Note the Spring AI naming asymmetry: the builder method is
     * {@code .properties(Map)} but the reader is {@code getMetadata()} (see
     * {@link #stripThinkingFromPrompt} L937).
     */
    private static AssistantMessage buildAssistantMessageWithThinking(
            String fullContent, String fullThinking, List<AssistantMessage.ToolCall> finalToolCalls) {
        AssistantMessage.Builder builder = AssistantMessage.builder().content(fullContent);
        if (finalToolCalls != null && !finalToolCalls.isEmpty()) {
            builder.toolCalls(finalToolCalls);
        }
        if (fullThinking != null && !fullThinking.isEmpty()) {
            builder.properties(Map.of("reasoningContent", fullThinking));
        }
        return builder.build();
    }

    /**
     * Store reasoning content in the cache for cross-turn replay.
     * Only caches when there are tool calls (MiMo requires reasoning_content
     * specifically on assistant messages with tool_calls).
     */
    private static void cacheReasoningContent(String fullThinking,
                                               List<AssistantMessage.ToolCall> toolCalls) {
        if (fullThinking == null || fullThinking.isBlank()) return;
        if (toolCalls == null || toolCalls.isEmpty()) return;
        List<String> ids = toolCalls.stream()
                .map(AssistantMessage.ToolCall::id)
                .filter(id -> id != null && !id.isEmpty())
                .toList();
        if (!ids.isEmpty()) {
            ReasoningContentCache.store(ids, fullThinking);
        }
    }

    /**
     * Record token / cache usage to the optional metrics aggregator.
     * Called only from successful assembly paths ({@link #assembleResult}
     * and {@link #assembleStoppedResult}) — error paths are excluded because
     * their token counts are typically zero and would skew the ratio.
     */
    private void recordCacheMetrics(String phase, int promptTok, int completionTok,
                                    int cacheReadTok, int cacheWriteTok) {
        if (cacheMetrics == null) return;
        // Skip empty-usage records (pure error responses or broken chunks).
        if (promptTok == 0 && completionTok == 0 && cacheReadTok == 0 && cacheWriteTok == 0) {
            return;
        }
        cacheMetrics.record(phase, promptTok, completionTok, cacheReadTok, cacheWriteTok);
    }

    /** 构建纯错误 StreamResult（无任何内容） */
    /**
     * Strip {@code reasoningContent} from AssistantMessages that belong to <em>prior</em>
     * user turns, keeping thinking for messages within the <strong>current</strong> user
     * turn intact.
     *
     * <p>PR-2 L4 (RFC-049 §2.4.1): The old semantics "keep only the last AssistantMessage's
     * thinking" broke DeepSeek's contract for multi-round tool-calls within a single user
     * turn (DeepSeek requires all in-turn assistant thinking to be passed back on subsequent
     * rounds). Now the boundary is the most recent {@link UserMessage}: AssistantMessages at
     * index {@code <= lastUserIdx} are prior-turn history (their thinking must be stripped
     * per DeepSeek's "reset across user turns" rule); AssistantMessages at {@code > lastUserIdx}
     * are in-turn (their thinking must be preserved).
     *
     * <p>PR-2 L4 (RFC-049 §2.4.2): This method is called as a normal pre-egress step from
     * {@link #doStreamCall}, not only from the {@code THINKING_BLOCK_ERROR} retry path. The
     * retry path still calls it too (idempotent), serving as defensive re-application.
     *
     * <p>Note: {@code Prompt.getOptions()} is preserved by reference into the returned
     * {@code Prompt} (this is existing behavior). Callers rely on that — mutations to
     * {@code options.user} via {@link AssistantThinkingRelay} must stay visible after
     * normalize.
     */
    static Prompt stripThinkingFromPrompt(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();

        // Find most recent UserMessage — boundary of the current user turn
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIdx = i;
                break;
            }
        }

        int strippedCount = 0;
        List<Message> cleaned = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            // Only strip prior-turn assistant thinking (i <= lastUserIdx); in-turn (i > lastUserIdx) stays
            if (msg instanceof AssistantMessage am && i <= lastUserIdx) {
                Map<String, Object> meta = am.getMetadata();
                if (meta != null && meta.containsKey("reasoningContent")) {
                    Map<String, Object> cleanMeta = new java.util.HashMap<>(meta);
                    cleanMeta.remove("reasoningContent");
                    AssistantMessage.Builder builder = AssistantMessage.builder()
                            .content(am.getText())
                            .properties(cleanMeta);
                    if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                        builder.toolCalls(am.getToolCalls());
                    }
                    if (am.getMedia() != null && !am.getMedia().isEmpty()) {
                        builder.media(am.getMedia());
                    }
                    cleaned.add(builder.build());
                    strippedCount++;
                    continue;
                }
            }
            cleaned.add(msg);
        }
        if (strippedCount > 0) {
            log.debug("[ThinkingRecovery] Stripped reasoningContent from {} prior-turn assistant messages "
                            + "(lastUserIdx={}, total={})",
                    strippedCount, lastUserIdx, messages.size());
        }
        return new Prompt(cleaned, prompt.getOptions());
    }

    /**
     * Drop trailing {@link AssistantMessage} entries from a Prompt's instructions.
     * Most LLM providers reject prompts whose history ends with an assistant turn —
     * Anthropic Claude with a 400 "does not support assistant message prefill",
     * DeepSeek thinking-mode variants with reasoning_content errors. The
     * trailing assistant is typically a summarizer-emitted scaffold message that
     * shouldn't be sent as the final user-facing prompt anyway. Returns the
     * input unchanged if there's nothing to drop.
     */
    static Prompt dropTrailingAssistant(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        if (messages.isEmpty()) {
            return prompt;
        }
        int end = messages.size();
        while (end > 0 && messages.get(end - 1) instanceof AssistantMessage) {
            end--;
        }
        if (end == messages.size()) {
            return prompt;
        }
        if (end == 0) {
            // Refuse to produce an empty prompt — caller's bug; let provider error surface.
            log.warn("[dropTrailingAssistant] all messages were AssistantMessage; skipping trim to avoid empty prompt");
            return prompt;
        }
        log.debug("[dropTrailingAssistant] trimmed {} trailing AssistantMessage(s) from prompt (size {} -> {})",
                messages.size() - end, messages.size(), end);
        return new Prompt(new ArrayList<>(messages.subList(0, end)), prompt.getOptions());
    }

    private StreamResult buildErrorResult(String errorMsg, String conversationId, String phase) {
        log.error("[{}] Building error result for conversation {}: {}", phase, conversationId, errorMsg);
        if (streamTracker != null && conversationId != null) {
            broadcastDelta(conversationId, "warning",
                    buildDeltaJson(errorMsg));
        }
        AssistantMessage errorMessage = new AssistantMessage("[错误] " + errorMsg);
        return new StreamResult("[错误] " + errorMsg, "", errorMessage,
                List.of(), false, 0, 0, false, errorMsg, ErrorType.UNKNOWN);
    }

    /** 构建带错误类型的 StreamResult */
    private StreamResult buildErrorResultWithType(String errorMsg, String conversationId,
                                                    String phase, ErrorType errorType) {
        log.error("[{}] Building typed error result for conversation {}: {} (type={})",
                phase, conversationId, errorMsg, errorType);
        if (streamTracker != null && conversationId != null) {
            broadcastDelta(conversationId, "warning", buildDeltaJson(errorMsg));
            // 广播结构化 error 事件，供前端展示错误卡片
            String errorJson = buildErrorEventJson(errorMsg, conversationId, errorType);
            streamTracker.broadcast(conversationId, "error", errorJson);
        }
        AssistantMessage errorMessage = new AssistantMessage("[错误] " + errorMsg);
        return new StreamResult("[错误] " + errorMsg, "", errorMessage,
                List.of(), false, 0, 0, false, errorMsg, errorType);
    }

    /** 构建 error 事件的 JSON payload */
    private static String buildErrorEventJson(String message, String conversationId, ErrorType errorType) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"message\":\"");
        appendJsonEscaped(sb, message);
        sb.append("\",\"conversationId\":\"");
        appendJsonEscaped(sb, conversationId);
        sb.append("\",\"errorType\":\"");
        sb.append(errorType.name());
        sb.append("\"}");
        return sb.toString();
    }

    /** JSON 字符串转义辅助 */
    private static void appendJsonEscaped(StringBuilder sb, String value) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
    }

    // Pulls the offending model id out of a Volcano Ark error body. Both
    // ModelNotOpen and InvalidEndpointOrModel.NotFound mention it after a
    // recognizable phrase ("activated the model X" / "model or endpoint X").
    private static final java.util.regex.Pattern ARK_MODEL_NAME_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?:activated the model|model or endpoint)\\s+([A-Za-z0-9._-]+)");

    private static String extractArkModelName(String body) {
        if (body == null) return null;
        java.util.regex.Matcher m = ARK_MODEL_NAME_PATTERN.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** 从异常链提取用户友好的错误信息 */
    private static String extractUserFriendlyError(Throwable error) {
        String msg = error.getMessage();
        if (msg == null) msg = error.getClass().getSimpleName();

        // 若是 WebClientResponseException，先把 response body 也并入判定样本，
        // 因为 Ollama 的 "does not support tools" 错误只在 body 里，不在 status line 里。
        String bodySample = "";
        Throwable cursor = error;
        for (int i = 0; cursor != null && i < 5; i++, cursor = cursor.getCause()) {
            if (cursor instanceof WebClientResponseException wre) {
                try {
                    String body = wre.getResponseBodyAsString();
                    if (body != null && !body.isEmpty()) {
                        bodySample = body.length() > 512 ? body.substring(0, 512) : body;
                    }
                } catch (Exception ignored) {
                }
                break;
            }
        }
        String combined = msg + " " + bodySample;

        // ↓↓↓ 具体错误翻译（优先级由高到低）↓↓↓

        // Ollama / 其他 provider 在模型不支持 function calling 时返回此文案：
        //   "<model> does not support tools"
        // 这不是模型坏，而是用户选错了模型 —— 给出可操作的切换建议。
        if (bodySample.contains("does not support tools") || msg.contains("does not support tools")) {
            return "当前模型不支持工具调用（function calling）。请在 设置 → 模型 里切换到支持 tools 的模型，"
                    + "例如 qwen3、qwen2.5:7b+、llama3.1:8b+、mistral-nemo、command-r 等。";
        }

        // Volcano Engine Ark — model exists but the user's account hasn't activated it.
        // Body shape: {"error":{"code":"ModelNotOpen","message":"Your account ... has not activated the model X. Please activate the model service in the Ark Console..."}}
        if (combined.contains("ModelNotOpen")) {
            String modelId = extractArkModelName(combined);
            String suffix = modelId != null ? "「" + modelId + "」" : "";
            return "火山方舟（Volcano Ark）尚未为该账号开通模型" + suffix
                    + "。请前往 Ark 控制台 → 模型广场，对该模型点击「开通服务」后重试。"
                    + "（控制台：https://console.volcengine.com/ark）";
        }

        // Volcano Engine Ark — model id doesn't exist for the user's region/key.
        // Body shape: {"error":{"code":"InvalidEndpointOrModel.NotFound","message":"The model or endpoint X does not exist or you do not have access to it..."}}
        if (combined.contains("InvalidEndpointOrModel")) {
            String modelId = extractArkModelName(combined);
            String suffix = modelId != null ? "「" + modelId + "」" : "";
            return "火山方舟（Volcano Ark）找不到模型" + suffix
                    + "。原因可能是模型 ID 不在当前区域，或你的账号没有访问权限。"
                    + "建议在 设置 → 模型 里点「刷新模型」重新发现，或在 Ark 控制台创建「推理接入点」(ep-XXX) 后使用该 ID。";
        }

        // DashScope "url error" is really "model name not mapped to any valid endpoint".
        if (msg.contains("url error") || msg.contains("[InvalidParameter]")
                || msg.contains("Model not exist") || msg.contains("model_not_found")
                || msg.contains("Model not found")
                || combined.contains("model not found") || combined.contains("not_found_error")) {
            return "Model name not available on this provider — verify the model exists and is supported (Settings → Models)";
        }
        // 对 Jackson 反序列化错误，提取关键信息
        if (msg.contains("engine_overloaded")) return "Model service overloaded, please retry later";
        if (msg.contains("unsupported image format") || msg.contains("unsupported")) return "Unsupported file format (e.g. SVG), use PNG/JPG instead";
        if (msg.contains("invalid_request_error") || msg.contains("400 Bad Request")) return "Bad request, please check input";
        if (msg.contains("rate_limit") || msg.contains("429")) return "Rate limit exceeded, please retry later";
        if (msg.contains("timeout") || msg.contains("Timeout")) return "Request timeout, please retry";
        if (msg.contains("502") || msg.contains("503") || msg.contains("504")) return "Model service temporarily unavailable";
        // SiliconFlow and similar providers surface "network connection error" when their
        // backend is under high load or the upstream model connection is disrupted.
        // Treat this as a transient failure so the user gets a retry-oriented message.
        if (combined.contains("network connection error"))
            return "Model service network error, please retry in a moment";
        // 截断过长的原始消息
        return msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
    }

    /**
     * LLM 调用错误类型分类
     */
    public enum ErrorType {
        /** 无错误 */
        NONE,
        /** 速率限制 (429) */
        RATE_LIMIT,
        /** 服务端错误 (5xx, timeout) */
        SERVER_ERROR,
        /** Prompt 过长 (context length exceeded) */
        PROMPT_TOO_LONG,
        /** 认证错误 */
        AUTH_ERROR,
        /** 客户端错误 (400 Bad Request, 不支持的格式等) — 不应重试 */
        CLIENT_ERROR,
        /** Thinking 块错误（旧消息中的 thinking block 不可修改）— 可剥离后单次重试 */
        THINKING_BLOCK_ERROR,
        /**
         * RFC-009: LLM returned no content, no thinking, and no tool calls.
         * Treated as a soft failure — skip same-model retries and hand off to
         * the fallback chain directly. Typical cause: upstream rate-limit
         * rejection that comes back as HTTP 200 with empty body.
         */
        EMPTY_RESPONSE,
        /**
         * RFC-009 P3.2: payment / billing failure (HTTP 402, "insufficient_quota",
         * "credit balance is too low", etc.). Distinct from {@link #AUTH_ERROR}
         * because the right response is to <i>switch provider</i> (a different
         * provider may have credits) rather than just terminate. Skips same-model
         * retries and falls through to the fallback chain.
         */
        BILLING,
        /**
         * RFC-009 P3.2: requested model id not recognized by the provider
         * (HTTP 404, "Model not exist", "model_not_found", DashScope's
         * "url error"). Same handling as {@link #BILLING} — heads straight
         * to the fallback chain instead of looping retries against a model
         * that does not exist.
         */
        MODEL_NOT_FOUND,
        /** 其他未知错误 */
        UNKNOWN
    }

    /**
     * 流式调用结果
     */
    public record StreamResult(
            /** 完整内容文本 */
            String text,
            /** 完整 thinking 文本 */
            String thinking,
            /** 重建的完整 AssistantMessage（含 toolCalls） */
            AssistantMessage assistantMessage,
            /** 完整工具调用列表 */
            List<AssistantMessage.ToolCall> toolCalls,
            /** 是否包含工具调用 */
            boolean hasToolCalls,
            /** 本次调用消耗的 prompt tokens */
            int promptTokens,
            /** 本次调用消耗的 completion tokens */
            int completionTokens,
            /** 结果是否不完整（LLM 中途断开但已有部分内容） */
            boolean partial,
            /** 错误信息（非空表示调用失败，但可能仍有 partial 内容可用） */
            String errorMessage,
            /** 错误类型分类 */
            ErrorType errorType,
            /** 用户主动停止（stopRequested）导致的提前返回 */
            boolean stopped,
            /** RFC-014: Anthropic prompt cache 命中字节数（其它 provider 为 0） */
            int cacheReadTokens,
            /** RFC-014: Anthropic prompt cache 写入字节数（其它 provider 为 0） */
            int cacheWriteTokens
    ) {
        /** 兼容旧调用方 — 无 partial/error/stopped 的正常结果 */
        public StreamResult(String text, String thinking, AssistantMessage assistantMessage,
                            List<AssistantMessage.ToolCall> toolCalls, boolean hasToolCalls,
                            int promptTokens, int completionTokens) {
            this(text, thinking, assistantMessage, toolCalls, hasToolCalls,
                    promptTokens, completionTokens, false, null, ErrorType.NONE, false, 0, 0);
        }

        /** 兼容 10-arg 调用点 */
        public StreamResult(String text, String thinking, AssistantMessage assistantMessage,
                            List<AssistantMessage.ToolCall> toolCalls, boolean hasToolCalls,
                            int promptTokens, int completionTokens,
                            boolean partial, String errorMessage, ErrorType errorType) {
            this(text, thinking, assistantMessage, toolCalls, hasToolCalls,
                    promptTokens, completionTokens, partial, errorMessage, errorType, false, 0, 0);
        }

        /** 兼容 12-arg 调用点（pre-RFC-014） */
        public StreamResult(String text, String thinking, AssistantMessage assistantMessage,
                            List<AssistantMessage.ToolCall> toolCalls, boolean hasToolCalls,
                            int promptTokens, int completionTokens,
                            boolean partial, String errorMessage, ErrorType errorType,
                            boolean stopped) {
            this(text, thinking, assistantMessage, toolCalls, hasToolCalls,
                    promptTokens, completionTokens, partial, errorMessage, errorType, stopped, 0, 0);
        }

        /** 是否有不可忽略的错误（无内容 + 有错误） */
        public boolean hasFatalError() {
            return errorMessage != null && (text == null || text.isBlank()) && !hasToolCalls;
        }

        /** 是否为 Prompt 过长错误 */
        public boolean isPromptTooLong() {
            return errorType == ErrorType.PROMPT_TOO_LONG;
        }

        /** 是否有任何可保存的内容（text/thinking/toolCalls） */
        public boolean hasAnyContent() {
            return (text != null && !text.isBlank())
                    || (thinking != null && !thinking.isBlank())
                    || hasToolCalls;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 从 AssistantMessage 的 properties 中提取 reasoningContent
     * <p>
     * Spring AI 1.1.3 的 OpenAiChatModel 在流式路径中会将 delta.reasoning_content
     * 放入 properties 的 "reasoningContent" key。
     */
    private String extractReasoningContent(AssistantMessage msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object rc = metadata.get("reasoningContent");
        if (rc instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    /**
     * 广播 delta 事件（content_delta / thinking_delta）
     */
    private void broadcastDelta(String conversationId, String eventName, String delta) {
        if (streamTracker == null || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        // 手动构建 JSON 避免序列化开销，格式与 ChatController.broadcastEvent 一致
        String json = buildDeltaJson(delta);
        streamTracker.broadcast(conversationId, eventName, json);
    }

    /**
     * Broadcast a {@code content_truncated} lifecycle event so consumers can
     * surface when the volume-based thinking-only soft cap stops the stream.
     */
    private void broadcastContentTruncated(String conversationId, String reason, int truncatedChars) {
        if (streamTracker == null || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        try {
            streamTracker.broadcastObject(conversationId, "content_truncated", Map.of(
                    "reason", reason != null ? reason : "thinking_only_no_content",
                    "truncatedChars", truncatedChars,
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.debug("Failed to broadcast content_truncated for {}: {}", conversationId, e.getMessage());
        }
    }

    /**
     * Collapse a content buffer's trailing run of verbatim repeats to a
     * single copy. Used to clean up the persisted final answer after
     * {@link #hasRepeatingSuffix} fires — the streamed text already
     * contains the duplicates (SSE chunks can't be unsent), but the
     * DB-persisted message and the IM channel reply should show ONE
     * clean copy of the looping unit, not a wall.
     *
     * <p>Algorithm: find the smallest period in {@code [minPeriod,
     * maxPeriod]} where the buffer ends with that unit repeated 2+
     * times consecutively, then return everything up to (and including)
     * the FIRST copy of that unit. Conservative — if no period yields
     * 2+ consecutive matches, returns the buffer unchanged.
     *
     * <p>Public for unit-testing alongside {@link #hasRepeatingSuffix}.
     */
    public static String dedupTrailingRepeats(String content, int minPeriod, int maxPeriod) {
        if (content == null || content.isEmpty()) return content;
        int len = content.length();
        if (minPeriod <= 0 || maxPeriod < minPeriod) return content;
        int periodCap = Math.min(maxPeriod, len / 2);
        for (int p = minPeriod; p <= periodCap; p++) {
            int unitStart = len - p;
            // Walk backward as far as the unit keeps matching.
            int copies = 1;
            int blockStart = unitStart - p;
            while (blockStart >= 0
                    && content.regionMatches(blockStart, content, unitStart, p)) {
                copies++;
                blockStart -= p;
            }
            if (copies >= 2) {
                // Keep prefix + ONE copy. The first copy starts at
                // (blockStart + p) since the loop walked back one step
                // past the last match.
                int firstCopyStart = blockStart + p;
                int trimEnd = firstCopyStart + p;
                return content.substring(0, trimEnd);
            }
        }
        return content;
    }

    /**
     * Detect whether {@code accum} ends with the same {@code period}-sized
     * unit repeated at least {@code minOccurrences} times consecutively,
     * for some {@code period} in {@code [minPeriod, maxPeriod]}. Returns
     * true when the model is stuck in a "self-arguing" loop emitting the
     * same final-answer chunk over and over.
     *
     * <p>Algorithm: probe period sizes from small to large. For each
     * candidate period {@code p}, take the last {@code p} chars as the
     * unit and check whether the {@code minOccurrences-1} preceding
     * blocks of length {@code p} are byte-identical. The smallest period
     * that yields the required consecutive copies trips the guard. We
     * iterate small→large because tighter periods are more specific:
     * a 30-char unit repeated 4× is a stronger signal than a 200-char
     * unit happening to appear once.
     *
     * <p>Cost: O(periodRange × occurrences × period) char comparisons.
     * For default thresholds (~200 × 4 × 100) that's ~80K comparisons
     * per scan — microseconds against an LLM call. Throttled by the
     * caller via {@code lastContentRepeatCheckLen} so the scan amortizes.
     *
     * <p>Package-private + static for unit-testing the threshold without
     * spinning up a full {@code StreamResult}.
     */
    static boolean hasRepeatingSuffix(CharSequence accum, int minPeriod, int maxPeriod,
                                       int minOccurrences) {
        if (accum == null) return false;
        int len = accum.length();
        if (minPeriod <= 0 || minOccurrences <= 1 || maxPeriod < minPeriod) return false;
        if (len < minPeriod * minOccurrences) return false;
        String s = accum.toString();
        int periodCap = Math.min(maxPeriod, len / minOccurrences);
        for (int p = minPeriod; p <= periodCap; p++) {
            // Unit = last p chars. Check prior (minOccurrences - 1)
            // blocks of length p match the unit byte-for-byte.
            int unitStart = len - p;
            boolean allMatch = true;
            for (int k = 2; k <= minOccurrences; k++) {
                int blockStart = len - k * p;
                if (blockStart < 0) { allMatch = false; break; }
                if (!s.regionMatches(blockStart, s, unitStart, p)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) return true;
        }
        return false;
    }

    /**
     * Best-effort character count of the outbound prompt for the
     * {@code context_prepared} event. Cheaper than tokenizing and only used
     * for UI presentation, so an exact figure is unnecessary.
     */
    private static int approximatePromptChars(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) return 0;
        int total = 0;
        for (Message m : prompt.getInstructions()) {
            String text = m.getText();
            if (text != null) total += text.length();
        }
        return total;
    }

    /**
     * Pick a stable model identifier from whatever {@link ChatModel}
     * implementation we received — Spring AI doesn't expose a single accessor.
     * We try the well-known fields by reflection so this stays decoupled from
     * concrete provider classes (Anthropic / OpenAI / DashScope all expose
     * {@code defaultOptions.model} or equivalent).
     */
    private static String identifyModel(ChatModel chatModel) {
        if (chatModel == null) return "";
        try {
            // Common Spring AI shape: getDefaultOptions().getModel()
            java.lang.reflect.Method getDefaultOptions = chatModel.getClass().getMethod("getDefaultOptions");
            Object opts = getDefaultOptions.invoke(chatModel);
            if (opts != null) {
                try {
                    java.lang.reflect.Method getModel = opts.getClass().getMethod("getModel");
                    Object model = getModel.invoke(opts);
                    if (model != null) return model.toString();
                } catch (NoSuchMethodException ignored) {
                    // fall through
                }
            }
        } catch (Exception ignored) {
            // fall through to class-name fallback
        }
        return chatModel.getClass().getSimpleName();
    }

    /**
     * 构建 {"delta":"..."} JSON
     */
    private static String buildDeltaJson(String delta) {
        StringBuilder sb = new StringBuilder("{\"delta\":\"");
        for (int k = 0; k < delta.length(); k++) {
            char c = delta.charAt(k);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        sb.append("\"}");
        return sb.toString();
    }

    /**
     * 累积 tool call 分片。
     * <p>
     * 流式模式下 tool calls 可能分多个 chunk 到来：
     * - 第一个 chunk 携带 id、name 和部分 arguments
     * - 后续 chunk 只有 arguments 增量
     * <p>
     * 采用增量累积方式合并分片 tool_call。
     */
    private void accumulateToolCalls(List<AssistantMessage.ToolCall> chunkToolCalls,
                                     List<ToolCallAccumulator> accumulators) {
        for (AssistantMessage.ToolCall tc : chunkToolCalls) {
            if (tc.id() != null && !tc.id().isEmpty()) {
                // 新的 tool call 或完整 tool call
                ToolCallAccumulator existing = findAccumulator(accumulators, tc.id());
                if (existing != null) {
                    // 追加 arguments
                    if (tc.arguments() != null) {
                        existing.arguments.append(tc.arguments());
                    }
                } else {
                    ToolCallAccumulator acc = new ToolCallAccumulator();
                    acc.id = tc.id();
                    acc.type = tc.type();
                    acc.name = tc.name();
                    acc.arguments = new StringBuilder(tc.arguments() != null ? tc.arguments() : "");
                    accumulators.add(acc);
                }
            } else if (!accumulators.isEmpty()) {
                // 无 id 的 chunk，追加到最后一个 accumulator 的 arguments
                ToolCallAccumulator last = accumulators.get(accumulators.size() - 1);
                if (tc.arguments() != null) {
                    last.arguments.append(tc.arguments());
                }
                if (tc.name() != null && !tc.name().isEmpty() && (last.name == null || last.name.isEmpty())) {
                    last.name = tc.name();
                }
            }
        }
    }

    private ToolCallAccumulator findAccumulator(List<ToolCallAccumulator> accumulators, String id) {
        for (ToolCallAccumulator acc : accumulators) {
            if (id.equals(acc.id)) {
                return acc;
            }
        }
        return null;
    }

    private List<AssistantMessage.ToolCall> buildFinalToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            result.add(new AssistantMessage.ToolCall(
                    acc.id,
                    acc.type != null ? acc.type : "function",
                    acc.name,
                    sanitizeToolCallArguments(acc.name, acc.arguments.toString())));
        }
        return result;
    }

    /**
     * Ensure {@code function.arguments} is always a well-formed JSON string.
     * <p>
     * Some providers (e.g. aliyun-codingplan) reject the entire follow-up
     * request with HTTP 400 when the assistant message in history carries a
     * tool call whose {@code arguments} is not parseable JSON. Streaming
     * accumulation can produce such payloads when:
     * <ul>
     *   <li>The model emits zero-argument tool calls as {@code ""} instead
     *       of {@code "{}"}.</li>
     *   <li>The upstream stream is truncated mid-token, leaving a partial
     *       JSON fragment like {@code "{\"a\":"}.</li>
     * </ul>
     * Both cases are normalized to {@code "{}"} so the chat-completions
     * round-trip stays valid. Tool execution downstream still re-validates
     * arguments and surfaces a per-tool error if the empty payload is wrong
     * for that tool.
     */
    private static String sanitizeToolCallArguments(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        try {
            TOOL_ARG_JSON_MAPPER.readTree(arguments);
            return arguments;
        } catch (Exception e) {
            log.warn("Tool '{}' arguments are not valid JSON after stream aggregation "
                            + "(len={}, head={}); replacing with empty object so the "
                            + "follow-up chat-completions request stays well-formed. "
                            + "Parse error: {}",
                    toolName,
                    arguments.length(),
                    arguments.substring(0, Math.min(80, arguments.length())),
                    e.getMessage());
            return "{}";
        }
    }

    private static class ToolCallAccumulator {
        String id;
        String type;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    // ==================== <think> 标签 fallback 解析 ====================

    private record ThinkExtracted(String thinking, String content) {}

    /**
     * 从内容中提取 &lt;think&gt;...&lt;/think&gt; 标签内的文本作为 thinking。
     * 仅作为 fallback，当模型不支持结构化 reasoningContent 时使用。
     */
    private static ThinkExtracted extractThinkTags(String content) {
        StringBuilder thinking = new StringBuilder();
        StringBuilder cleaned = new StringBuilder();
        int i = 0;
        while (i < content.length()) {
            int tagStart = content.indexOf("<think>", i);
            if (tagStart < 0) {
                cleaned.append(content, i, content.length());
                break;
            }
            cleaned.append(content, i, tagStart);
            int tagEnd = content.indexOf("</think>", tagStart);
            if (tagEnd < 0) {
                // 未闭合的 <think> 标签，将剩余部分视为 thinking
                thinking.append(content, tagStart + 7, content.length());
                break;
            }
            thinking.append(content, tagStart + 7, tagEnd);
            i = tagEnd + 8;
        }
        return new ThinkExtracted(thinking.toString().trim(), cleaned.toString().trim());
    }
}
