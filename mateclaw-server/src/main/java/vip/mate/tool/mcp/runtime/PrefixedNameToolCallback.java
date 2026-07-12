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
 *
 * <p>When {@code serverName} is provided (non-null, non-blank), the
 * description is prefixed with {@code [MCP server: <name>]} so the LLM
 * can identify which server a tool belongs to without parsing the
 * opaque numeric {@code serverId} in the tool name. This is critical
 * for tasks that mix tools from multiple MCP servers — without the
 * tag, the LLM cannot distinguish {@code mcp_1928..._search_xxx} from
 * {@code mcp_1882..._search_yyy} and will reconstruct wrong tool names
 * from memory. See agent-attention-anchoring design doc for details.
 */
public final class PrefixedNameToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition prefixedDefinition;

    /**
     * Backward-compatible constructor — equivalent to passing
     * {@code null} for {@code serverName} (no server tag in description).
     *
     * <p>Kept so existing tests and call sites that don't yet thread
     * the server name through continue to compile.
     */
    public PrefixedNameToolCallback(String prefixedName, ToolCallback delegate) {
        this(prefixedName, delegate, null);
    }

    /**
     * Primary constructor.
     *
     * @param prefixedName the {@code mcp_<serverId>_<slug>_<hash6>} name
     * @param delegate     the underlying MCP tool callback
     * @param serverName   human-readable MCP server name; when non-blank,
     *                     prepended to the description as
     *                     {@code [MCP server: <name>]} so the LLM can tell
     *                     tools from different servers apart. May be
     *                     {@code null} when the server name is unknown
     *                     (e.g. in unit tests).
     */
    public PrefixedNameToolCallback(String prefixedName, ToolCallback delegate, String serverName) {
        if (prefixedName == null || prefixedName.isBlank()) {
            throw new IllegalArgumentException("prefixedName must not be blank");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
        ToolDefinition original = delegate.getToolDefinition();
        String originalDesc = original != null ? original.description() : "";
        if (originalDesc == null) {
            originalDesc = "";
        }
        String enrichedDesc = (serverName != null && !serverName.isBlank())
                ? "[MCP server: " + serverName + "] " + originalDesc
                : originalDesc;
        this.prefixedDefinition = DefaultToolDefinition.builder()
                .name(prefixedName)
                .description(enrichedDesc)
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
