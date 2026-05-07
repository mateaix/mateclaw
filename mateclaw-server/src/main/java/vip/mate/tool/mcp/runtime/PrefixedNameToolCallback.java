package vip.mate.tool.mcp.runtime;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a {@link ToolCallback} from an MCP server and overrides
 * {@link ToolDefinition#name()} with a stable
 * {@code mcp_<serverId>_<slug>_<hash6>} key.
 *
 * <p>Why wrap rather than configure the upstream provider's prefix
 * generator: the upstream extension point only sees protocol-level
 * connection metadata, not the database server id we want to anchor
 * to. Keeping the prefix logic inside this package binds the contract
 * to one place and survives upstream API changes.
 *
 * <p>Description, input schema, metadata, and {@code call(...)} are
 * forwarded verbatim — the wrapper changes only the name, so guard,
 * approval, observability, and return-direct routing all see the same
 * string they will write to bindings.
 */
public final class PrefixedNameToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition prefixedDefinition;

    public PrefixedNameToolCallback(String prefixedName, ToolCallback delegate) {
        if (prefixedName == null || prefixedName.isBlank()) {
            throw new IllegalArgumentException("prefixedName must not be blank");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
        ToolDefinition original = delegate.getToolDefinition();
        this.prefixedDefinition = DefaultToolDefinition.builder()
                .name(prefixedName)
                .description(original != null ? original.description() : "")
                .inputSchema(original != null ? original.inputSchema() : "{}")
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return prefixedDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }

    /** Exposed for diagnostic / wrapping detection (e.g. by ReturnDirect logic). */
    public ToolCallback getDelegate() {
        return delegate;
    }
}
