package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP tool callback wrapper that builds {@code tools/call} requests with a
 * populated {@code _meta} field, carrying:
 * <ul>
 *   <li>{@code progressToken} — enables MCP servers to push progress via
 *       {@code notifications/progress} (issue #505).</li>
 *   <li>Identity claims ({@code mateclaw_user} / {@code mateclaw_token}) — when
 *       the inner delegate is an {@link IdentityForwardingToolCallback}, the
 *       caller's typed identity is injected into {@code _meta} so the MCP server
 *       can forward it on-behalf-of (#459 follow-up: moved from args to _meta).</li>
 * </ul>
 *
 * <p>The direct {@link McpSyncClient#callTool} path is taken whenever <em>either</em>
 * a progress token <em>or</em> identity forwarding is active — both require
 * {@code _meta}, which the SDK's plain {@code delegate.call()} path cannot set.
 * When neither is active, the call delegates transparently (compatible with MCP
 * servers that don't support progress or identity, and with built-in tools).
 */
@Slf4j
public final class ProgressAwareMcpToolCallback implements ToolCallback {

    /** Key in ToolContext where the progressToken is stored. */
    public static final String MCP_PROGRESS_TOKEN_KEY = "_mcp_progress_token";

    /** Key in the {@code _meta} map carrying the progress token. */
    private static final String META_PROGRESS_TOKEN = "progressToken";

    private final ToolCallback delegate;
    private final McpSyncClient mcpClient;
    private final String rawToolName;
    private final ObjectMapper objectMapper;

    public ProgressAwareMcpToolCallback(ToolCallback delegate, McpSyncClient mcpClient,
                                        String rawToolName, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.mcpClient = mcpClient;
        this.rawToolName = rawToolName;
        this.objectMapper = objectMapper;
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
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // Decide whether to take the direct callTool path: needed when either a
        // progress token or identity forwarding requires _meta injection.
        String progressToken = extractProgressToken(toolContext);
        IdentityForwardingToolCallback idFwd = delegate instanceof IdentityForwardingToolCallback i
                ? i : null;
        Map<String, String> identityMeta = idFwd != null && toolContext != null
                ? idFwd.metaInjection(toolContext)
                : Map.of();

        boolean needsDirectPath = progressToken != null || !identityMeta.isEmpty();
        if (!needsDirectPath) {
            return delegate.call(toolInput, toolContext);
        }
        try {
            // Build _meta: merge progress token (if any) + identity claims (if any).
            Map<String, Object> meta = new HashMap<>();
            if (progressToken != null) {
                meta.put(META_PROGRESS_TOKEN, progressToken);
            }
            meta.putAll(identityMeta);

            // Arguments come from the model verbatim — identity is NOT merged
            // into args anymore (it lives in _meta now).
            Map<String, Object> arguments = parseArguments(toolInput);
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                    .name(rawToolName)
                    .arguments(arguments != null ? arguments : Map.of())
                    .meta(meta)
                    .build();
            McpSchema.CallToolResult result = mcpClient.callTool(request);
            return serializeResult(result);
        } catch (Exception e) {
            log.warn("Progress/identity-aware MCP call failed for tool '{}', falling back to delegate: {}",
                    rawToolName, e.getMessage());
            // Fallback delegates without _meta, which means identity is NOT
            // injected on this path. Log explicitly so the loss is visible.
            if (!identityMeta.isEmpty()) {
                log.warn("[McpIdentity] identity dropped on fallback for tool '{}' "
                        + "(direct callTool failed; delegate.call cannot set _meta)", rawToolName);
            }
            return delegate.call(toolInput, toolContext);
        }
    }

    private String extractProgressToken(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object token = toolContext.getContext().get(MCP_PROGRESS_TOKEN_KEY);
        if (token instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse MCP tool arguments as JSON, using raw string: {}", e.getMessage());
            return Map.of("input", toolInput);
        }
    }

    private String serializeResult(McpSchema.CallToolResult result) {
        if (result == null) return "";
        if (result.content() == null || result.content().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                sb.append(tc.text());
            } else {
                sb.append(content.toString());
            }
        }
        return sb.toString();
    }

    /** Return the underlying delegate (for ReturnDirect / IdentityForward detection). */
    public ToolCallback getDelegate() {
        return delegate;
    }
}
