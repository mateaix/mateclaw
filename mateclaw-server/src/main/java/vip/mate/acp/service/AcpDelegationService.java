package vip.mate.acp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.acp.client.AcpStdioClient;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.exception.MateClawException;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RFC-090 Phase 7b — fire-and-forget delegation to an external ACP
 * agent.
 *
 * <p>One {@link #prompt(String, String, String)} call:
 * <ol>
 *   <li>Looks up the endpoint row, refuses if disabled or undefined.</li>
 *   <li>Spawns a fresh {@link AcpStdioClient} (no session caching in
 *       v1 — stateless tool calls keep failure surface small;
 *       multi-turn caching can be a follow-up RFC).</li>
 *   <li>Runs {@code initialize → session/new → session/prompt}.</li>
 *   <li>Accumulates {@code agent_message_chunk} text from
 *       {@code session/update} notifications into the response.</li>
 *   <li>Auto-allows or cancels {@code session/request_permission}
 *       based on the endpoint's {@code trusted} flag — untrusted
 *       endpoints reject every permission request, surfacing a
 *       transparent "this endpoint can't be used non-interactively"
 *       error to the LLM caller.</li>
 *   <li>Returns the accumulated text or a JSON error blob on failure.</li>
 * </ol>
 *
 * <p>The streaming surface (chunk-by-chunk relay back through MateClaw's
 * own SSE stream) is intentionally not done yet — the wrapper tool is
 * synchronous so it composes cleanly with the existing ReAct graph.
 * When we want native streaming, we'll add a second method that takes
 * an {@code Sinks.Many<String>}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpDelegationService {

    /** Hard ceiling on a single ACP delegation. Long enough for a
     *  multi-turn coding session, short enough that a hung agent can't
     *  permanently block an LLM tool call. */
    private static final Duration PROMPT_TIMEOUT = Duration.ofMinutes(5);

    private static final long INITIALIZE_TIMEOUT_MS = 15_000L;
    private static final long SESSION_NEW_TIMEOUT_MS = 10_000L;

    private final ObjectMapper objectMapper;
    private final AcpEndpointService endpointService;
    private final AcpRuntimeSupport runtimeSupport;

    /**
     * Run a one-shot ACP prompt against {@code endpointName}. Returns
     * the agent's accumulated reply text. Throws
     * {@link MateClawException} for configuration / runtime errors so
     * the caller (typically a wrapper tool) can serialize a friendly
     * JSON error.
     */
    public String prompt(String endpointName, String userPrompt, String cwdHint) {
        StringBuilder accumulator = new StringBuilder();
        runPrompt(endpointName, userPrompt, cwdHint, update -> {
            if ("agent_message_chunk".equals(update.type())
                    || "agent-message-chunk".equals(update.type())) {
                if (update.text() != null && !update.text().isEmpty()) {
                    accumulator.append(update.text());
                }
            }
        });
        return accumulator.toString().trim();
    }

    /**
     * RFC-090 Phase 7c — streaming relay. Runs the same
     * {@code initialize → session/new → session/prompt} sequence as
     * {@link #prompt} but emits every {@code session/update} event as
     * a {@link StreamDelta} on the returned {@link reactor.core.publisher.Flux}.
     *
     * <p>Event mapping:
     * <ul>
     *   <li>{@code agent_message_chunk} → {@code StreamDelta(content, null)}</li>
     *   <li>{@code tool_call_*} → {@code StreamDelta.event("acp_tool_call", data)}</li>
     *   <li>{@code plan} → {@code StreamDelta.event("acp_plan", data)}</li>
     *   <li>{@code current_mode} → {@code StreamDelta.event("acp_mode", data)}</li>
     *   <li>terminal completion / error → {@code StreamDelta.event("acp_done"/"acp_error", data)}</li>
     * </ul>
     *
     * <p>The synchronous JSON-RPC exchange runs on a virtual-thread
     * async stage; the notification handler pushes events into a
     * {@code Sinks.Many} which backs the returned Flux. This keeps the
     * blocking ACP stdio handshake off the subscriber's thread while
     * preserving backpressure semantics for the SSE relay.
     */
    public reactor.core.publisher.Flux<StreamDelta> promptStream(
            String endpointName, String userPrompt, String cwdHint) {
        // Bounded buffer: 256 items is generous for ACP agent messages
        // (each chunk is a few hundred chars). autoCancel=false ensures the
        // sink stays alive even if the subscriber briefly unsubscribes
        // during Reactor internals.
        reactor.core.publisher.Sinks.Many<StreamDelta> sink =
                reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer(256, false);

        // Hold the ACP client so the cancel handler can close it, killing
        // the spawned agent process when the subscriber (e.g. the ReAct
        // graph) cancels the Flux. Without this, a cancelled delegation
        // keeps the external agent process alive for up to PROMPT_TIMEOUT
        // (5 minutes) — a resource leak.
        AtomicReference<AcpStdioClient> clientRef = new AtomicReference<>();
        // Signal flag so the notification handler knows the sink is dead
        // and should stop pushing (and ideally close the client).
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        CompletableFuture.runAsync(() -> {
            try {
                runPrompt(endpointName, userPrompt, cwdHint, update -> {
                    if (cancelled.get()) return;
                    StreamDelta delta = toStreamDelta(update);
                    if (delta != null) {
                        var result = sink.tryEmitNext(delta);
                        if (result.isFailure()) {
                            // Sink cancelled or terminated — stop the ACP process
                            cancelled.set(true);
                            AcpStdioClient c = clientRef.get();
                            if (c != null) {
                                try { c.close(); } catch (Exception ignored) {}
                            }
                        }
                    }
                }, clientRef);
                sink.tryEmitComplete();
            } catch (Exception e) {
                Map<String, Object> errData = new LinkedHashMap<>();
                errData.put("endpoint", endpointName);
                errData.put("error", e.getMessage());
                sink.tryEmitNext(StreamDelta.event("acp_error", errData));
                sink.tryEmitComplete();
            }
        });

        return sink.asFlux()
                .doOnCancel(() -> {
                    cancelled.set(true);
                    AcpStdioClient c = clientRef.get();
                    if (c != null) {
                        try { c.close(); } catch (Exception ignored) {}
                    }
                });
    }

    /**
     * Map an {@link AcpUpdateEvent} to a {@link StreamDelta}. Returns
     * null for event types we don't relay (keeps the Flux clean).
     */
    private StreamDelta toStreamDelta(AcpUpdateEvent update) {
        return switch (update.type()) {
            case "agent_message_chunk", "agent-message-chunk" ->
                    new StreamDelta(update.text(), null);
            case "tool_call_start", "tool_call_update", "tool_call_end",
                 "tool-call-start", "tool-call-update", "tool-call-end" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("phase", update.type());
                if (update.toolName() != null) data.put("tool", update.toolName());
                if (update.text() != null) data.put("text", update.text());
                yield StreamDelta.event("acp_tool_call", data);
            }
            case "plan" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                if (update.text() != null) data.put("text", update.text());
                if (update.toolName() != null) data.put("plan", update.toolName());
                yield StreamDelta.event("acp_plan", data);
            }
            case "current_mode", "current-mode" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                if (update.text() != null) data.put("mode", update.text());
                yield StreamDelta.event("acp_mode", data);
            }
            case "error" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                if (update.text() != null) data.put("error", update.text());
                yield StreamDelta.event("acp_error", data);
            }
            default -> {
                if (update.text() != null && !update.text().isEmpty()) {
                    yield new StreamDelta(update.text(), null);
                }
                yield null;
            }
        };
    }

    // ==================== shared prompt runner ====================

    /**
     * Internal: run the full ACP handshake, feeding every
     * {@code session/update} event to {@code listener}. Used by both
     * {@link #prompt} (text accumulator) and {@link #promptStream}
     * (StreamDelta emitter).
     */
    private void runPrompt(String endpointName, String userPrompt, String cwdHint,
                            java.util.function.Consumer<AcpUpdateEvent> listener) {
        runPrompt(endpointName, userPrompt, cwdHint, listener, null);
    }

    /**
     * Extended overload that optionally publishes the spawned
     * {@link AcpStdioClient} to {@code clientRef} so the caller can
     * close it on cancellation (e.g. when the subscriber cancels the
     * Flux returned by {@link #promptStream}).
     */
    private void runPrompt(String endpointName, String userPrompt, String cwdHint,
                            java.util.function.Consumer<AcpUpdateEvent> listener,
                            AtomicReference<AcpStdioClient> clientRef) {
        if (endpointName == null || endpointName.isBlank()) {
            throw new MateClawException("err.acp.endpoint_required",
                    "ACP endpoint name is required");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new MateClawException("err.acp.prompt_required",
                    "ACP prompt is required");
        }

        AcpEndpointEntity endpoint = endpointService.findByName(endpointName);
        if (endpoint == null) {
            throw new MateClawException("err.acp.endpoint_not_found",
                    "ACP endpoint not found: " + endpointName);
        }
        if (!Boolean.TRUE.equals(endpoint.getEnabled())) {
            throw new MateClawException("err.acp.endpoint_disabled",
                    "ACP endpoint '" + endpointName + "' is disabled — enable it in Settings ▸ ACP Endpoints");
        }

        List<String> args = endpointService.parseArgs(endpoint);
        Map<String, String> env = endpointService.parseEnv(endpoint);
        boolean trusted = !Boolean.FALSE.equals(endpoint.getTrusted());
        String resolvedCwd = runtimeSupport.resolveCwd(endpoint, cwdHint);

        AcpStdioClient client;
        try {
            client = AcpStdioClient.spawn(objectMapper, endpoint.getCommand(),
                    args, env, resolvedCwd);
        } catch (IOException e) {
            throw new MateClawException("err.acp.spawn_failed",
                    "Failed to spawn ACP agent '" + endpointName + "': " + e.getMessage());
        }

        // Publish the client reference so the caller's cancel handler
        // can close it (and kill the spawned process) if the subscriber
        // cancels the Flux.
        if (clientRef != null) {
            clientRef.set(client);
        }

        try (AcpStdioClient autoClose = client) {
            wireHandlers(autoClose, listener, trusted, endpointName);

            JsonNode initResp = autoClose.initialize(INITIALIZE_TIMEOUT_MS);
            if (initResp == null || initResp.path("protocolVersion").asInt(-1)
                    != AcpStdioClient.PROTOCOL_VERSION) {
                throw new MateClawException("err.acp.protocol_mismatch",
                        "ACP protocol mismatch with endpoint '" + endpointName + "'");
            }

            JsonNode session = autoClose.newSession(resolvedCwd, SESSION_NEW_TIMEOUT_MS);
            String sessionId = session == null ? null : session.path("sessionId").asText("");
            if (sessionId == null || sessionId.isBlank()) {
                throw new MateClawException("err.acp.session_failed",
                        "ACP session/new returned no sessionId for '" + endpointName + "'");
            }

            ObjectNode promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.set("prompt", buildPromptArray(userPrompt));
            autoClose.sendRequest("session/prompt", promptParams, PROMPT_TIMEOUT.toMillis());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ACP delegation failed for endpoint '{}': {}", endpointName, e.getMessage());
            String authHint = runtimeSupport.translateAuthError(endpoint, e.getMessage());
            if (authHint != null) {
                throw new MateClawException("err.acp.auth_failed", authHint);
            }
            throw new MateClawException("err.acp.delegation_failed",
                    "ACP delegation to '" + endpointName + "' failed: " + e.getMessage());
        }
    }

    private void wireHandlers(AcpStdioClient client,
                                java.util.function.Consumer<AcpUpdateEvent> listener,
                                boolean trusted, String endpointName) {
        // Notifications carry session/update messages; relay every known
        // update kind to the listener so the streaming surface can show
        // tool calls, plans, mode switches — not just the final text.
        client.setNotificationHandler(msg -> {
            String method = msg.path("method").asText("");
            if (!"session/update".equals(method)) return;
            JsonNode update = msg.path("params").path("update");
            if (update.isMissingNode() || update.isNull()) return;
            String type = update.path("sessionUpdate").asText(
                    update.path("type").asText(""));
            String text = extractText(update.path("content"));
            String toolName = update.path("toolName").asText(
                    update.path("tool").asText(null));
            listener.accept(new AcpUpdateEvent(type, text, toolName));
        });

        // Permission requests: trusted endpoints auto-allow the FIRST
        // option (which Zed-style agents make the "allow" choice);
        // untrusted refuse every request explicitly so the agent
        // exits cleanly instead of hanging.
        client.setRequestHandler(msg -> {
            String method = msg.path("method").asText("");
            if (!"session/request_permission".equals(method)) return null;
            JsonNode params = msg.path("params");
            if (!trusted) {
                log.info("[ACP] declining permission for untrusted endpoint '{}'", endpointName);
                return cancelledOutcome();
            }
            JsonNode options = params.path("options");
            String optionId = "";
            if (options.isArray() && options.size() > 0) {
                JsonNode first = options.get(0);
                optionId = first.path("optionId").asText(first.path("id").asText(""));
            }
            if (optionId.isEmpty()) {
                return cancelledOutcome();
            }
            return selectedOutcome(optionId);
        });
    }

    private JsonNode buildPromptArray(String text) {
        // Spring AI / Zed ACP prompt format: array of content blocks.
        // For now we only emit a single text block; future iterations
        // can attach images / file references via additional blocks.
        var arr = objectMapper.createArrayNode();
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        arr.add(block);
        return arr;
    }

    /**
     * Extract plain text from an ACP {@code content} field. The shape
     * varies between agents — Zed uses {@code [{type:"text",text:"..."}]},
     * some emit a single object, others nest in {@code resource.text}.
     * Tolerant extractor that handles all known shapes.
     */
    private String extractText(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) sb.append(extractText(item));
            return sb.toString();
        }
        JsonNode text = content.get("text");
        if (text != null && text.isTextual()) return text.asText("");
        JsonNode resource = content.get("resource");
        if (resource != null) {
            JsonNode rt = resource.get("text");
            if (rt != null && rt.isTextual()) return rt.asText("");
        }
        return "";
    }

    private ObjectNode selectedOutcome(String optionId) {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode outcome = objectMapper.createObjectNode();
        outcome.put("outcome", "selected");
        outcome.put("optionId", optionId);
        result.set("outcome", outcome);
        return result;
    }

    private ObjectNode cancelledOutcome() {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode outcome = objectMapper.createObjectNode();
        outcome.put("outcome", "cancelled");
        result.set("outcome", outcome);
        return result;
    }

    /**
     * RFC-090 Phase 7c — a parsed {@code session/update} event from an
     * ACP agent. The {@code type} field carries the
     * {@code sessionUpdate} / {@code type} string verbatim so callers
     * can branch on the upstream protocol's event taxonomy.
     *
     * @param type    sessionUpdate type (agent_message_chunk, tool_call_*, plan, current_mode, …)
     * @param text    extracted text payload (agent message text, tool description, plan text, …)
     * @param toolName tool name when the event carries one (tool_call_* events)
     */
    public record AcpUpdateEvent(String type, String text, String toolName) {}
}
