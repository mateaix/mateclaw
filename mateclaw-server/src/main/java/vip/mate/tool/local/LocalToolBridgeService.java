package vip.mate.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.guard.model.ToolGuardAuditLogEntity;
import vip.mate.tool.guard.repository.ToolGuardAuditLogMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * High-level entry point the {@code local_*} tools use to execute an operation
 * on the requesting user's desktop.
 * <p>
 * It resolves the requester from the {@link ChatOrigin} carried in the tool
 * context, forwards the call over {@link DesktopBridgeRegistry}, blocks for the
 * desktop's reply (bounded by a timeout), and writes an audit record to
 * {@code mate_tool_guard_audit_log} regardless of outcome. Approval itself is
 * performed natively on the desktop (where the user can see the full path /
 * command / content), so this layer only records the decision the desktop made.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalToolBridgeService {

    /** Capability tokens advertised by the desktop in its handshake. */
    public static final String CAP_READ = "read";
    public static final String CAP_LIST = "list";
    public static final String CAP_STAT = "stat";
    public static final String CAP_WRITE = "write";
    public static final String CAP_EDIT = "edit";
    public static final String CAP_SHELL = "shell";

    private final DesktopBridgeRegistry registry;
    private final ObjectMapper objectMapper;
    private final ToolGuardAuditLogMapper auditLogMapper;

    /** Whether a user has a live desktop tunnel right now. */
    public boolean isOnline(@Nullable ChatOrigin origin) {
        return origin != null && registry.isOnline(origin.requesterId());
    }

    /**
     * Result envelope returned to the tools. {@code data} is the desktop's
     * payload on success; on failure {@code error}/{@code code} describe why.
     */
    public record BridgeResult(boolean ok, @Nullable JsonNode data,
                               @Nullable String error, @Nullable String code) {

        public static BridgeResult success(JsonNode data) {
            return new BridgeResult(true, data, null, null);
        }

        public static BridgeResult failure(String code, String error) {
            return new BridgeResult(false, null, error, code);
        }
    }

    /**
     * Forward a tool call to the user's desktop and wait for the reply.
     *
     * @param origin         chat origin carrying the requester identity
     * @param toolName       the {@code local_*} tool name (for audit)
     * @param method         the desktop RPC method (e.g. {@code read_file})
     * @param capability     capability the desktop must advertise, or null
     * @param params         the call parameters
     * @param timeoutSeconds how long to wait for the desktop reply
     * @param mutating       true for write/edit/shell (drives the audit decision label)
     */
    public BridgeResult invoke(@Nullable ChatOrigin origin, String toolName, String method,
                               @Nullable String capability, ObjectNode params,
                               int timeoutSeconds, boolean mutating) {
        String username = origin != null ? origin.requesterId() : null;
        if (username == null || username.isBlank()) {
            audit(origin, toolName, params, "ERROR");
            return BridgeResult.failure("NO_USER",
                    "Cannot determine which desktop to reach for this request");
        }

        try {
            CompletableFuture<JsonNode> future = registry.call(username, method, capability, params);
            JsonNode envelope = future.get(timeoutSeconds, TimeUnit.SECONDS);
            boolean ok = envelope.path("ok").asBoolean(false);
            if (ok) {
                audit(origin, toolName, params, mutating ? "APPROVED" : "ALLOW");
                return BridgeResult.success(envelope.path("data"));
            }
            String code = envelope.path("code").asText("ERROR");
            String error = envelope.path("error").asText("Operation failed on desktop");
            audit(origin, toolName, params, "DENIED".equalsIgnoreCase(code) ? "DENIED" : "BLOCK");
            return BridgeResult.failure(code, error);

        } catch (DesktopBridgeException e) {
            audit(origin, toolName, params, "OFFLINE");
            return BridgeResult.failure(e.code().name(), e.getMessage());
        } catch (TimeoutException e) {
            audit(origin, toolName, params, "TIMEOUT");
            return BridgeResult.failure("TIMEOUT",
                    "Desktop did not respond within " + timeoutSeconds + "s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            audit(origin, toolName, params, "ERROR");
            return BridgeResult.failure("ERROR", cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audit(origin, toolName, params, "ERROR");
            return BridgeResult.failure("ERROR", "Interrupted while waiting for desktop");
        }
    }

    /** Write an audit row reusing the tool-guard audit table. Best-effort. */
    private void audit(@Nullable ChatOrigin origin, String toolName, ObjectNode params, String decision) {
        try {
            ToolGuardAuditLogEntity entity = new ToolGuardAuditLogEntity();
            if (origin != null) {
                entity.setConversationId(origin.conversationId());
                entity.setAgentId(origin.agentId() != null ? String.valueOf(origin.agentId()) : null);
                entity.setUserId(origin.requesterId());
                entity.setChannelType(origin.channelType() != null ? origin.channelType() : "desktop");
            }
            entity.setToolName(toolName);
            entity.setToolParamsJson(truncate(params != null ? params.toString() : null));
            entity.setDecision(decision);
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[LocalToolBridge] Failed to record audit: {}", e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
