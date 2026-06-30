package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.tool.builtin.ToolExecutionContext;

/**
 * Wraps an MCP {@link ToolCallback} for a trusted server (opt-in via
 * {@link McpIdentityForwardProperties}) and injects the authenticated caller's
 * username into the call arguments under
 * {@link McpIdentityForwardProperties#IDENTITY_ARG}, so the STDIO MCP server can
 * forward it on-behalf-of to its downstream REST backend.
 *
 * <p>STDIO has no per-request header channel and the subprocess is shared by all
 * users, so identity must ride <em>in-band, per call</em>. The username is read
 * from {@link ToolExecutionContext} (trusted server-side context), never from
 * the model — any LLM-supplied value of the reserved key is overwritten, so the
 * model cannot spoof identity.
 *
 * <p>The wrapper is transparent in every other respect: tool definition,
 * metadata, schema, and (when no user is in context) the call itself are
 * forwarded verbatim. It sits <em>inside</em> {@link PrefixedNameToolCallback}
 * so name prefixing and return-direct detection still see the raw delegate.
 *
 * @author MateClaw Team
 */
@Slf4j
public final class IdentityForwardingToolCallback implements ToolCallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback delegate;

    public IdentityForwardingToolCallback(ToolCallback delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
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
        return delegate.call(withIdentity(toolInput, ToolExecutionContext.username()));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(withIdentity(toolInput, ToolExecutionContext.username(toolContext)), toolContext);
    }

    /** Exposed for diagnostic / wrapping detection. */
    public ToolCallback getDelegate() {
        return delegate;
    }

    /**
     * Merge the username into the JSON arguments under the reserved key,
     * overwriting any value the model supplied. Returns the input unchanged
     * when there is no authenticated user (don't fabricate identity — the MCP
     * server decides whether to reject) or when the arguments are not a JSON
     * object (nothing to merge into).
     */
    static String withIdentity(String toolInput, String username) {
        if (username == null || username.isBlank()) {
            return toolInput;
        }
        try {
            ObjectNode node;
            if (toolInput == null || toolInput.isBlank()) {
                node = MAPPER.createObjectNode();
            } else {
                JsonNode parsed = MAPPER.readTree(toolInput);
                if (!parsed.isObject()) {
                    // Tool arguments are conventionally an object; anything else
                    // (array/scalar) has nowhere to carry the reserved key, so
                    // forward unchanged rather than corrupt the payload.
                    log.warn("[McpIdentity] tool input is not a JSON object; forwarding without identity");
                    return toolInput;
                }
                node = (ObjectNode) parsed;
            }
            node.put(McpIdentityForwardProperties.IDENTITY_ARG, username);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // Malformed JSON would fail downstream anyway; don't mask it by
            // rewriting — forward the original and let the call surface it.
            log.warn("[McpIdentity] failed to inject identity into tool input: {}", e.getMessage());
            return toolInput;
        }
    }
}
