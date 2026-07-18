package vip.mate.tool.mcp.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * Marks an MCP {@link ToolCallback} as belonging to a trusted server (opt-in via
 * {@link McpIdentityForwardProperties}) so that the outer
 * {@link ProgressAwareMcpToolCallback} knows to route the call through the
 * direct {@code tools/call} path and inject the caller's identity into
 * {@code _meta}.
 *
 * <p>Identity is <b>not</b> injected here anymore — it used to be merged into the
 * tool arguments JSON, but has moved to the MCP {@code _meta} field (the
 * caller-controlled channel) per the #459 follow-up. {@code _meta} is authored
 * by MateClaw, never by the model, so the anti-spoof property holds without an
 * explicit overwrite guard.
 *
 * <p>This wrapper now serves two roles:
 * <ol>
 *   <li>A <b>type marker</b> — {@code ProgressAwareMcpToolCallback} checks
 *       {@code delegate instanceof IdentityForwardingToolCallback} to decide
 *       whether to build a {@code CallToolRequest} with identity in
 *       {@code _meta}.</li>
 *   <li>An <b>identity provider</b> — {@link #metaInjection(ToolContext)}
 *       resolves the {@code _meta} key→value map from
 *       {@link McpIdentityForwardService}, which the progress wrapper merges
 *       into the request.</li>
 * </ol>
 *
 * <p>The wrapper is transparent in every other respect: tool definition,
 * metadata, schema, and the call itself are forwarded verbatim. It sits
 * <em>inside</em> {@link ProgressAwareMcpToolCallback} which sits inside
 * {@link PrefixedNameToolCallback}.
 *
 * @author MateClaw Team
 */
@Slf4j
public final class IdentityForwardingToolCallback implements ToolCallback {

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
        // No args injection — identity travels in _meta, set by the progress
        // wrapper when it builds the CallToolRequest. This plain delegate path
        // is only reached when the progress wrapper is absent (non-MCP tools or
        // a test), in which case there is no _meta channel and identity is
        // silently dropped — acceptable because identity-forward is only armed
        // for MCP servers that always go through the progress wrapper.
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }

    /** Exposed for diagnostic / wrapping detection. */
    public ToolCallback getDelegate() {
        return delegate;
    }

    /**
     * Resolve the identity to inject into the {@code tools/call} {@code _meta}
     * field for this call. Returns an empty map when the origin carries no
     * usable identity (cron / system / anonymous-without-id) or when token mode
     * is enabled but the key is unavailable (fail-closed).
     *
     * <p>Package-private so {@link ProgressAwareMcpToolCallback} can merge the
     * result into the {@code _meta} map alongside the progress token.
     */
    Map<String, String> metaInjection(ToolContext toolContext) {
        return identityService.resolveMeta(toolContext, audience);
    }
}
