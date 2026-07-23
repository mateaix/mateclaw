package vip.mate.tool.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.tool.guard.model.GuardEvaluation;

import java.util.List;
import java.util.Map;

/**
 * 工具执行安全辅助工具（从 ActionNode / StepExecutionNode 提取的共享逻辑）
 */
@Slf4j
public final class ToolExecutionGuardHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolExecutionGuardHelper() {}

    /**
     * 处理需要审批的工具调用
     *
     * @return 审批提示文本，作为 tool response 返回给 LLM
     */
    /**
     * Outcome of {@link #handleToolApproval}: the tool-response text handed back
     * to the LLM plus the persisted pending-approval id. {@code pendingId} is
     * null when the approval service is unavailable and the call degraded to a
     * block-style message.
     */
    public record ApprovalRequest(String response, String pendingId) {}

    public static ApprovalRequest handleToolApproval(
            AssistantMessage.ToolCall toolCall, String toolName, String arguments,
            GuardEvaluation evaluation, String conversationId, String agentId,
            String requesterId,
            ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
            List<GraphEventPublisher.GraphEvent> events,
            List<AssistantMessage.ToolCall> remainingToolCalls) {

        if (approvalService == null) {
            log.warn("[GuardHelper] ApprovalService not available, falling back to BLOCK for tool={}", toolName);
            events.add(GraphEventPublisher.toolComplete(toolName,
                    evaluation.summary() != null ? evaluation.summary() : "需要审批", false));
            return new ApprovalRequest("[安全拦截] " + (evaluation.summary() != null ? evaluation.summary() : "需要审批")
                    + "。审批服务不可用，请联系管理员。", null);
        }

        String toolCallPayload = serializeToolCall(toolCall);
        String siblingPayload = serializeToolCalls(remainingToolCalls);

        // 使用真实请求者 ID，而非硬编码 "system"
        String userId = (requesterId != null && !requesterId.isEmpty()) ? requesterId : "system";
        String reason = evaluation.summary() != null ? evaluation.summary() : "需要用户审批";
        // 使用增强版 createPending，内部自动处理 findings 增强 + DB 持久化
        String pendingId = approvalService.createPending(
                conversationId, userId, toolName, arguments, reason,
                toolCallPayload, siblingPayload, agentId, evaluation);

        // SSE 直推审批事件（增强版，包含 findings）
        if (streamTracker != null) {
            Map<String, Object> eventData = new java.util.LinkedHashMap<>();
            eventData.put("pendingId", pendingId);
            eventData.put("toolName", toolName != null ? toolName : "");
            eventData.put("arguments", arguments != null ? GraphEventPublisher.truncateForBroadcast(arguments) : "");
            eventData.put("reason", reason);
            eventData.put("summary", evaluation.summary());
            eventData.put("maxSeverity", evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null);
            eventData.put("findings", evaluation.findingsToMapList());
            eventData.put("timestamp", System.currentTimeMillis());
            streamTracker.broadcastObject(conversationId, "tool_approval_requested", eventData);
            log.info("[GuardHelper] Enhanced approval event pushed via SSE: pendingId={}, tool={}", pendingId, toolName);
        }

        events.add(GraphEventPublisher.toolApprovalRequested(
                pendingId, toolName, arguments, reason,
                evaluation.summary(),
                evaluation.maxSeverity() != null ? evaluation.maxSeverity().name() : null,
                evaluation.findingsToMapList()));

        log.info("[GuardHelper] Approval pending created: pendingId={}, tool={}, findings={}",
                pendingId, toolName, evaluation.hasFindings() ? evaluation.findings().size() : 0);

        return new ApprovalRequest(
                "[APPROVAL_PENDING] tool=" + toolName + " awaiting user decision", pendingId);
    }

    /**
     * 兼容旧版 ToolGuardResult 的处理方法
     */
    public static String handleToolApprovalLegacy(
            AssistantMessage.ToolCall toolCall, String toolName, String arguments,
            ToolGuardResult guardResult, String conversationId, String agentId,
            String requesterId,
            ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
            List<GraphEventPublisher.GraphEvent> events,
            List<AssistantMessage.ToolCall> remainingToolCalls) {

        if (approvalService == null) {
            log.warn("[GuardHelper] ApprovalService not available, falling back to BLOCK for tool={}", toolName);
            events.add(GraphEventPublisher.toolComplete(toolName, guardResult.reason(), false));
            return "[安全拦截] " + guardResult.reason() + "。审批服务不可用，请联系管理员。";
        }

        String toolCallPayload = serializeToolCall(toolCall);
        String siblingPayload = serializeToolCalls(remainingToolCalls);

        String userId = (requesterId != null && !requesterId.isEmpty()) ? requesterId : "system";
        String pendingId = approvalService.createPending(
                conversationId, userId, toolName, arguments, guardResult.reason(),
                toolCallPayload, siblingPayload, agentId);

        if (streamTracker != null) {
            streamTracker.broadcastObject(conversationId, "tool_approval_requested", Map.of(
                    "pendingId", pendingId,
                    "toolName", toolName != null ? toolName : "",
                    "arguments", arguments != null ? GraphEventPublisher.truncateForBroadcast(arguments) : "",
                    "reason", guardResult.reason() != null ? guardResult.reason() : "",
                    "timestamp", System.currentTimeMillis()
            ));
        }

        events.add(GraphEventPublisher.toolApprovalRequested(pendingId, toolName, arguments, guardResult.reason()));

        return "[APPROVAL_PENDING] tool=" + toolName + " awaiting user decision";
    }

    // ==================== 序列化工具 ====================

    public static String serializeToolCall(AssistantMessage.ToolCall toolCall) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "id", toolCall.id() != null ? toolCall.id() : "",
                    "type", toolCall.type() != null ? toolCall.type() : "function",
                    "name", toolCall.name() != null ? toolCall.name() : "",
                    "arguments", toolCall.arguments() != null ? toolCall.arguments() : ""
            ));
        } catch (JsonProcessingException e) {
            log.error("[GuardHelper] Failed to serialize tool call: {}", e.getMessage());
            return "{}";
        }
    }

    public static String serializeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "[]";
        }
        try {
            List<Map<String, String>> list = toolCalls.stream()
                    .map(tc -> Map.of(
                            "id", tc.id() != null ? tc.id() : "",
                            "type", tc.type() != null ? tc.type() : "function",
                            "name", tc.name() != null ? tc.name() : "",
                            "arguments", tc.arguments() != null ? tc.arguments() : ""
                    ))
                    .toList();
            return OBJECT_MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("[GuardHelper] Failed to serialize tool calls: {}", e.getMessage());
            return "[]";
        }
    }
}
