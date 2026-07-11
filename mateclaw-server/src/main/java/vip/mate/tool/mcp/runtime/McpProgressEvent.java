package vip.mate.tool.mcp.runtime;

import org.springframework.context.ApplicationEvent;

/**
 * MCP tool-call progress event.
 * Published by {@code McpClientManager.progressConsumer} and consumed
 * by {@link McpProgressRelay} for forwarding to {@code ChatStreamTracker}.
 */
public class McpProgressEvent extends ApplicationEvent {

    private final String conversationId;
    private final String toolCallId;
    private final String toolName;
    private final double progress;       // 0.0 ~ 1.0
    private final Double total;          // may be null
    private final String message;        // current stage description

    public McpProgressEvent(Object source, String conversationId, String toolCallId,
                            String toolName, double progress, Double total, String message) {
        super(source);
        this.conversationId = conversationId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.progress = progress;
        this.total = total;
        this.message = message;
    }

    public String getConversationId() { return conversationId; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public double getProgress() { return progress; }
    public Double getTotal() { return total; }
    public String getMessage() { return message; }
}
