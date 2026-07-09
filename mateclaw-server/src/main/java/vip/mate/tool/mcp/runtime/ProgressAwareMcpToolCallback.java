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

import java.util.Map;
import java.util.UUID;

/**
 * MCP tool callback wrapper that injects {@code _meta.progressToken} into
 * {@code tools/call} requests, enabling MCP Servers to push progress via
 * {@code notifications/progress}.
 *
 * <p>When {@code MCP_PROGRESS_TOKEN} is present in {@link ToolContext}, this wrapper
 * calls {@link McpSyncClient#callTool(McpSchema.CallToolRequest)} directly with the
 * progressToken injected. Otherwise it delegates to the original callback
 * (compatible with MCP Servers that do not support progress, and with built-in tools).
 */
@Slf4j
public final class ProgressAwareMcpToolCallback implements ToolCallback {

    /** Key in ToolContext where the progressToken is stored. */
    public static final String MCP_PROGRESS_TOKEN_KEY = "_mcp_progress_token";

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
        String progressToken = null;
        if (toolContext != null && toolContext.getContext() != null) {
            Object token = toolContext.getContext().get(MCP_PROGRESS_TOKEN_KEY);
            if (token instanceof String s && !s.isBlank()) {
                progressToken = s;
            }
        }
        if (progressToken == null) {
            return delegate.call(toolInput, toolContext);
        }
        try {
            // Apply identity forwarding BEFORE building CallToolRequest —
            // otherwise the progress path would silently bypass identity injection.
            String effectiveInput = toolInput;
            if (delegate instanceof IdentityForwardingToolCallback idFwd) {
                effectiveInput = idFwd.inject(toolInput, toolContext);
            }
            Map<String, Object> arguments = parseArguments(effectiveInput);
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                    .name(rawToolName)
                    .arguments(arguments != null ? arguments : Map.of())
                    .meta(Map.of("progressToken", progressToken))
                    .build();
            McpSchema.CallToolResult result = mcpClient.callTool(request);
            return serializeResult(result);
        } catch (Exception e) {
            log.warn("Progress-aware MCP call failed for tool '{}', falling back to delegate: {}",
                    rawToolName, e.getMessage());
            return delegate.call(toolInput, toolContext);
        }
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
