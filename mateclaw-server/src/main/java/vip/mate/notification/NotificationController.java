package vip.mate.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.agent.runtime.AgentRuntimeAggregator;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregate counts that drive global UI attention signals (sidebar badges,
 * future notification center).
 *
 * <p>Designed so the frontend can poll a single endpoint instead of fan-out
 * to every domain service. Fields with no settled "is it actually a problem"
 * semantics (failed crons / down channels / down MCP servers) are returned
 * as zero placeholders so the wire shape is stable and later phases can
 * populate them without bumping the contract.
 */
@Slf4j
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final ApprovalWorkflowService approvalWorkflowService;
    private final AgentRuntimeAggregator agentRuntimeAggregator;

    @Operation(summary = "Aggregated counts for the sidebar attention badges")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(Authentication auth) {
        boolean admin = isAdmin(auth);

        // Cast to int — counts won't exceed Integer.MAX_VALUE in practice
        // and the project's global Jackson config serializes Long as a string
        // (for ID precision), which would break the numeric UI badge.
        int pendingApprovals = (int) Math.min(Integer.MAX_VALUE, approvalWorkflowService.countPendingFromDb());
        int stuckAgents = admin
                ? agentRuntimeAggregator.snapshot().summary().stuck()
                : 0;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pendingApprovals", pendingApprovals);
        payload.put("stuckAgents", stuckAgents);
        // Reserved fields — wire shape stays stable so the frontend doesn't
        // need a fan-out when these get real semantics later.
        payload.put("failedCrons", 0);
        payload.put("downChannels", 0);
        payload.put("downMcps", 0);
        return R.ok(payload);
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) {
            throw new MateClawException(401, "authentication required");
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
