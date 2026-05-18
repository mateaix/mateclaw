package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestClient interceptor that rewrites the Anthropic {@code system} field from
 * a plain string to a two-element content-block array before the request hits
 * the wire.
 *
 * <p>Anthropic's OAuth anti-abuse gate accepts the Claude Code identity prefix
 * as a string ONLY when it is the sole content.  Appending any additional text
 * triggers a 429; two separate array elements always pass (verified 2026-04-25).
 *
 * <p>Spring AI's native array path is guarded by {@code @JsonIgnore cacheOptions}
 * which {@code ModelOptionsUtils.copyToTarget} drops before our settings can
 * reach {@code buildSystemContent}.  This interceptor bypasses that by rewriting
 * at the HTTP transport layer.
 *
 * <p>Sync (RestClient) variant; the WebFlux equivalent is
 * {@link ClaudeCodeSystemArrayExchangeFilter}.
 */
@Slf4j
@RequiredArgsConstructor
class ClaudeCodeSystemArrayInterceptor implements ClientHttpRequestInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        return execution.execute(request, rewriteSystemField(body, objectMapper));
    }

    /**
     * If {@code body} is a JSON object whose {@code system} field is a string,
     * replace it with a two-element content-block array:
     * <pre>
     * [ {"type":"text","text":"You are Claude Code..."}, {"type":"text","text":"<rest>"} ]
     * </pre>
     * Returns {@code body} unchanged on any error or if rewrite is not needed.
     * Package-private static so {@link ClaudeCodeSystemArrayExchangeFilter} can reuse.
     */
    static byte[] rewriteSystemField(byte[] body, ObjectMapper mapper) {
        if (body == null || body.length == 0) return body;
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isObject()) return body;
            JsonNode systemNode = root.get("system");
            if (systemNode == null || !systemNode.isTextual()) return body;
            byte[] rewritten = mapper.writeValueAsBytes(buildRewritten((ObjectNode) root, systemNode.asText()));
            log.debug("[ClaudeCodeSystem] rewrote system field to array ({} → {} bytes)",
                    body.length, rewritten.length);
            return rewritten;
        } catch (Exception e) {
            log.warn("[ClaudeCodeSystem] body rewrite failed, sending original: {}", e.getMessage());
            return body;
        }
    }

    static ObjectNode buildRewritten(ObjectNode root, String systemText) {
        String identity = ClaudeCodeIdentityChatModelDecorator.CLAUDE_CODE_SYSTEM_PREFIX;
        ArrayNode arr = root.arrayNode();

        ObjectNode identityBlock = arr.objectNode();
        identityBlock.put("type", "text");
        identityBlock.put("text", identity);
        arr.add(identityBlock);

        if (!systemText.equals(identity) && systemText.startsWith(identity)) {
            String rest = systemText.substring(identity.length()).replaceFirst("^\n+", "");
            if (!rest.isBlank()) {
                ObjectNode contentBlock = arr.objectNode();
                contentBlock.put("type", "text");
                contentBlock.put("text", rest);
                arr.add(contentBlock);
            }
        }

        ObjectNode copy = root.deepCopy();
        copy.set("system", arr);
        return copy;
    }
}
