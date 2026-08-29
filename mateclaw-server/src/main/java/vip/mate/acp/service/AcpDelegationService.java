package vip.mate.acp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.acp.client.AcpStdioClient;
import vip.mate.acp.model.AcpEndpointEntity;
import vip.mate.exception.MateClawException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
        long promptTimeoutMillis = resolvePromptTimeoutMillis(endpoint);
        // Always resolve cwd to a real directory: Zed's ACP Zod schema
        // marks cwd as a required string and rejects {@code undefined}
        // with -32602. See {@link AcpRuntimeSupport#resolveCwd}.
        String resolvedCwd = runtimeSupport.resolveCwd(endpoint, cwdHint);

        StringBuilder accumulator = new StringBuilder();
        AcpStdioClient client;
        try {
            client = AcpStdioClient.spawn(objectMapper, endpoint.getCommand(),
                    args, env, resolvedCwd);
        } catch (IOException e) {
            throw new MateClawException("err.acp.spawn_failed",
                    "Failed to spawn ACP agent '" + endpointName + "': " + e.getMessage());
        }

        try (AcpStdioClient autoClose = client) {
            wireHandlers(autoClose, accumulator, trusted, endpointName);

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
            autoClose.sendRequest("session/prompt", promptParams, promptTimeoutMillis);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ACP delegation failed for endpoint '{}': {}", endpointName, e.getMessage());
            // Upstream CLIs (claude-code / codex / qwen-code) wrap their
            // own auth failures in opaque JSON-RPC noise. Recognise the
            // 401/403/forbidden/unauthorized fingerprints and rewrite
            // the message into something the user can act on, with the
            // exact env var name they need to set.
            String authHint = runtimeSupport.translateAuthError(endpoint, e.getMessage());
            if (authHint != null) {
                throw new MateClawException("err.acp.auth_failed", authHint);
            }
            throw new MateClawException("err.acp.delegation_failed",
                    "ACP delegation to '" + endpointName + "' failed: " + e.getMessage());
        }

        return accumulator.toString().trim();
    }

    static long resolvePromptTimeoutMillis(AcpEndpointEntity endpoint) {
        int seconds = AcpEndpointService.normalizePromptTimeoutSeconds(
                endpoint != null ? endpoint.getPromptTimeoutSeconds() : null);
        return seconds * 1000L;
    }

    private void wireHandlers(AcpStdioClient client, StringBuilder buf,
                               boolean trusted, String endpointName) {
        // Notifications carry session/update messages; agent_message_chunk
        // is what we accumulate. Other update kinds (tool_call_*, plan,
        // current_mode) are observed but not relayed in v1.
        client.setNotificationHandler(msg -> {
            String method = msg.path("method").asText("");
            if (!"session/update".equals(method)) return;
            JsonNode update = msg.path("params").path("update");
            if (update.isMissingNode() || update.isNull()) return;
            String type = update.path("sessionUpdate").asText(
                    update.path("type").asText(""));
            if ("agent_message_chunk".equals(type) || "agent-message-chunk".equals(type)) {
                String text = extractText(update.path("content"));
                if (!text.isEmpty()) buf.append(text);
            }
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
}
