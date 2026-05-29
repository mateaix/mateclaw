package vip.mate.approval.grant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.approval.grant.entity.ApprovalGrant;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;

/**
 * WARN-level audit logger for the three resolver outcomes that need operator
 * visibility: AUTO_GRANT (a stored grant let a tool through), HARD_BLOCK (the
 * safety floor blocked a disaster) and FORCE_HUMAN (a dangerous pattern was
 * downgraded back to manual approval).
 * <p>
 * The logger is intentionally a separate bean with a fixed name
 * ({@code vip.mate.approval.grant.AutoApproveAuditLogger}) so operations can
 * filter / route just this signal without grepping through generic guard logs.
 * Arguments are truncated to 200 characters to keep each entry to one line; the
 * DB column {@code mate_approval_resolution_log.args_preview} stores up to 500
 * for the detail page.
 */
@Slf4j
@Component
public class AutoApproveAuditLogger {

    private static final int LOG_ARGS_MAX = 200;

    public void logAutoGrant(ApprovalGrant grant, ToolInvocationContext ctx, GuardEvaluation evaluation) {
        log.warn("[APPROVAL] AUTO_GRANT grantId={} tool={} severity={} (ceiling={}) "
                        + "scope={}/{} workspaceId={} args({})={} userId={} conversationId={} ruleId={}",
                grant.getId(),
                ctx.toolName(),
                severityName(evaluation),
                grant.getMaxSeverity(),
                grant.getScopeType(), grant.getScopeId(),
                ctx.workspaceId(),
                LOG_ARGS_MAX, truncate(ctx.rawArguments()),
                ctx.userId(), ctx.conversationId(),
                primaryRuleId(evaluation));
    }

    public void logHardBlock(ToolInvocationContext ctx, GuardEvaluation evaluation, String patternName) {
        log.warn("[APPROVAL] HARD_BLOCK pattern={} tool={} args({})={} userId={} conversationId={}",
                patternName,
                ctx.toolName(),
                LOG_ARGS_MAX, truncate(ctx.rawArguments()),
                ctx.userId(), ctx.conversationId());
    }

    public void logForceHuman(ToolInvocationContext ctx, GuardEvaluation evaluation, String patternName) {
        log.warn("[APPROVAL] FORCE_HUMAN pattern={} tool={} args({})={} userId={} conversationId={} "
                        + "— falling back to existing approval flow",
                patternName,
                ctx.toolName(),
                LOG_ARGS_MAX, truncate(ctx.rawArguments()),
                ctx.userId(), ctx.conversationId());
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= LOG_ARGS_MAX ? s : s.substring(0, LOG_ARGS_MAX) + "…";
    }

    private static String severityName(GuardEvaluation evaluation) {
        return evaluation == null || evaluation.maxSeverity() == null
                ? "UNKNOWN"
                : evaluation.maxSeverity().name();
    }

    private static String primaryRuleId(GuardEvaluation evaluation) {
        if (evaluation == null || evaluation.findings() == null || evaluation.findings().isEmpty()) {
            return null;
        }
        return evaluation.findings().get(0).ruleId();
    }
}
