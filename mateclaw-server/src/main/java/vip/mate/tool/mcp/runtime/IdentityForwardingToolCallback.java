package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps an MCP {@link ToolCallback} for a trusted server (opt-in via
 * {@link McpIdentityForwardProperties}) and injects the caller's identity into
 * the call arguments, so the STDIO MCP server can forward it on-behalf-of to its
 * downstream REST backend.
 *
 * <p>What gets injected is decided by {@link McpIdentityForwardService}: either
 * the plaintext username (under {@code __mateclaw_user__}) or a short-lived
 * signed JWT (under {@code __mateclaw_token__}).
 *
 * <p>STDIO has no per-request header channel and the subprocess is shared by all
 * users, so identity must ride <em>in-band, per call</em>. The identity is
 * derived from the trusted server-side {@link ToolContext}, never from the model
 * — any LLM-supplied value of the reserved key is overwritten, so the model
 * cannot spoof identity.
 *
 * <p>The wrapper is transparent in every other respect: tool definition,
 * metadata, schema, and (when there is no identity to inject) the call itself are
 * forwarded verbatim. It sits <em>inside</em> {@link PrefixedNameToolCallback}
 * so name prefixing and return-direct detection still see the raw delegate.
 *
 * @author MateClaw Team
 */
@Slf4j
public final class IdentityForwardingToolCallback implements ToolCallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final McpIdentityForwardService identityService;
    private final String audience;

    public IdentityForwardingToolCallback(ToolCallback delegate,
                                          McpIdentityForwardService identityService,
                                          String audience) {
        if (delegate == null || identityService == null) {
            throw new IllegalArgumentException("delegate and identityService must not be null");
        }
        this.delegate = delegate;
        this.identityService = identityService;
        this.audience = audience;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(inject(toolInput, null));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(inject(toolInput, toolContext), toolContext);
    }

    /** Exposed for diagnostic / wrapping detection. */
    public ToolCallback getDelegate() {
        return delegate;
    }

    /**
     * Inject identity claim into the toolInput JSON.
     * Package-private so {@link ProgressAwareMcpToolCallback} can apply identity
     * forwarding before calling mcpClient directly (progress path).
     */
    String inject(String toolInput, ToolContext toolContext) {
        return identityService.resolve(toolContext, audience)
                .map(i -> withClaim(toolInput, i.key(), i.value()))
                .orElse(toolInput);
    }

    /**
     * Merge {@code (key, value)} into the JSON arguments, overwriting any value
     * the model supplied for that key. Returns the input unchanged when the
     * arguments are not a JSON object (nothing to merge into) or are malformed
     * (let the call surface the error rather than mask it by rewriting).
     */
    static String withClaim(String toolInput, String key, String value) {
        try {
            ObjectNode node;
            if (toolInput == null || toolInput.isBlank()) {
                node = MAPPER.createObjectNode();
            } else {
                JsonNode parsed = MAPPER.readTree(toolInput);
                if (!parsed.isObject()) {
                    log.warn("[McpIdentity] tool input is not a JSON object; forwarding without identity");
                    return toolInput;
                }
                node = (ObjectNode) parsed;
            }
            node.put(key, value);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("[McpIdentity] failed to inject identity into tool input: {}", e.getMessage());
            return toolInput;
        }
    }
}
