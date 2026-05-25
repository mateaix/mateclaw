package vip.mate.agent.delegation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.ConversationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

/**
 * REST surface for managing live sub-agents:
 * <ul>
 *   <li>{@code POST /interrupt} — stop a running sub-agent.</li>
 *   <li>{@code POST /spawn-pause} — toggle the per-parent spawn-pause flag.</li>
 *   <li>{@code GET /active} — list sub-agents under one parent conversation.</li>
 * </ul>
 *
 * <p>Every endpoint authorizes the caller against the parent conversation's
 * owner before mutating or revealing anything; the {@code parentConversationId}
 * query parameter on {@code /active} is mandatory so the route cannot be used
 * to enumerate cross-tenant sub-agents.
 *
 * <p>Authorization mirrors the {@link vip.mate.workspace.conversation.ConversationService#isConversationOwner}
 * pattern used by the chat stop / fork routes — usernames are the principal
 * identity carried on {@link Authentication#getName()}, and shared "system"
 * conversations are accessible to all logged-in users (matches the existing
 * cron-job convention).
 */
@Slf4j
@Tag(name = "Sub-agents")
@RestController
@RequestMapping("/api/v1/subagents")
@RequiredArgsConstructor
public class SubagentController {

    private final SubagentRegistry registry;
    private final ConversationService conversationService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    /**
     * Resolve the record and verify the caller owns its parent conversation.
     * Throws a 403-coded exception when ownership fails so the global handler
     * can render a uniform JSON error envelope.
     */
    private SubagentRegistry.SubagentRecord requireOwnership(String subagentId, Authentication auth) {
        Optional<SubagentRegistry.SubagentRecord> opt = registry.get(subagentId);
        if (opt.isEmpty()) {
            throw new MateClawException(404, "subagent " + subagentId + " not found");
        }
        SubagentRegistry.SubagentRecord rec = opt.get();
        String username = currentUsername(auth);
        if (!conversationService.isConversationOwner(rec.parentConversationId(), username)) {
            // Audit denial separately from the operation itself so admins can
            // see what cross-tenant attempts hit the registry. Best-effort
            // serialization — the audit insert is async on the service side.
            auditEventService.record("subagent.interrupt.denied", "subagent",
                    subagentId, rec.subagentId(),
                    safeJson(Map.of(
                            "callerUsername", username,
                            "parent", rec.parentConversationId(),
                            "agentId", rec.agentId() == null ? -1L : rec.agentId()
                    )));
            throw new MateClawException(403, "not the owner of subagent's parent conversation");
        }
        return rec;
    }

    /**
     * Stop a running sub-agent. The registry flips status to {@code interrupted}
     * and disposes the streaming subscription if one was registered. Returns
     * the {@code interrupted} flag so the caller can distinguish "we did stop
     * something" from "the subagent was already finished" (404 case is handled
     * separately by {@link #requireOwnership}).
     */
    @Operation(summary = "Interrupt a running sub-agent")
    @PostMapping("/{subagentId}/interrupt")
    @RequireGlobalAdmin
    public R<Map<String, Object>> interrupt(@PathVariable String subagentId, Authentication auth) {
        SubagentRegistry.SubagentRecord rec = requireOwnership(subagentId, auth);
        boolean ok = registry.interrupt(subagentId);
        auditEventService.record("subagent.interrupt", "subagent",
                subagentId, rec.subagentId(),
                safeJson(Map.of(
                        "by", currentUsername(auth),
                        "parent", rec.parentConversationId(),
                        "result", ok
                )));
        return R.ok(Map.of("interrupted", ok));
    }

    /**
     * Toggle whether new sub-agent spawns are accepted under a parent
     * conversation. Used by the operator UI to halt runaway parent agents
     * mid-turn without killing the parent's own LLM call.
     */
    @Operation(summary = "Set sub-agent spawn-pause for a conversation")
    @PostMapping("/spawn-pause")
    @RequireGlobalAdmin
    public R<Map<String, Object>> setPaused(@RequestBody Map<String, Object> body, Authentication auth) {
        Object parentObj = body == null ? null : body.get("parentConversationId");
        String parent = parentObj == null ? null : parentObj.toString();
        if (parent == null || parent.isBlank()) {
            throw new MateClawException(400, "parentConversationId required");
        }
        String username = currentUsername(auth);
        if (!conversationService.isConversationOwner(parent, username)) {
            throw new MateClawException(403, "not the owner of conversation " + parent);
        }
        boolean paused = Boolean.TRUE.equals(body.get("paused"));
        registry.setSpawnPaused(parent, paused);
        auditEventService.record("subagent.spawn-pause", "conversation",
                parent, parent,
                safeJson(Map.of(
                        "paused", paused,
                        "by", username
                )));
        return R.ok(Map.of("paused", paused));
    }

    /**
     * List the sub-agents currently active in the delegation tree rooted at
     * {@code parentConversationId} — the user-facing conversation. Returns the
     * whole tree (direct children plus deeper descendants), so a multi-level
     * delegation is fully visible. The query parameter is mandatory: returning
     * all subagents process-wide would let any logged-in user enumerate other
     * tenants' delegation trees. Tenant isolation is enforced on this root
     * conversation, which the caller owns.
     */
    @Operation(summary = "List active sub-agents in a conversation's delegation tree")
    @GetMapping("/active")
    @RequireGlobalAdmin
    public R<Map<String, Object>> listActive(@RequestParam(required = false) String parentConversationId,
                                             Authentication auth) {
        if (parentConversationId == null || parentConversationId.isBlank()) {
            throw new MateClawException(400, "parentConversationId required");
        }
        String username = currentUsername(auth);
        if (!conversationService.isConversationOwner(parentConversationId, username)) {
            throw new MateClawException(403, "not the owner of conversation " + parentConversationId);
        }
        List<Map<String, Object>> snapshot = registry.snapshotTree(parentConversationId).stream()
                .map(this::toResponseDto)
                .toList();
        return R.ok(Map.of("subagents", snapshot));
    }

    /** Username from auth context; falls back to "anonymous" only when null. */
    private String currentUsername(Authentication auth) {
        return auth != null ? auth.getName() : "anonymous";
    }

    /**
     * DTO projection that drops the {@link reactor.core.Disposable} (not
     * serializable to the wire) and exposes only the user-facing fields.
     */
    private Map<String, Object> toResponseDto(SubagentRegistry.SubagentRecord rec) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("subagentId", rec.subagentId());
        dto.put("parentConversationId", rec.parentConversationId());
        dto.put("childConversationId", rec.childConversationId());
        dto.put("parentSubagentId", rec.parentSubagentId());
        dto.put("depth", rec.depth());
        dto.put("agentId", rec.agentId());
        dto.put("goal", rec.goal());
        dto.put("startedAt", rec.startedAt());
        dto.put("status", rec.status().get());
        dto.put("toolCount", rec.toolCount().get());
        dto.put("lastTool", rec.lastTool().get());
        dto.put("currentPhase", rec.currentPhase().get());
        return dto;
    }

    /**
     * Best-effort JSON serialization for audit detail. Falling back to a
     * marker string keeps the audit row insertable when payload contains
     * a non-serializable value — the alternative (throwing) would lose the
     * audit record entirely.
     */
    private String safeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"audit_serialization_failed\"}";
        }
    }
}
