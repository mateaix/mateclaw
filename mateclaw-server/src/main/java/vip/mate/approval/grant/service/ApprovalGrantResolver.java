package vip.mate.approval.grant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.approval.grant.AutoApproveAuditLogger;
import vip.mate.approval.grant.AutoApproveResult;
import vip.mate.approval.grant.AutoGrantSafetyFloor;
import vip.mate.approval.grant.entity.ApprovalGrant;
import vip.mate.approval.grant.entity.ApprovalResolutionLog;
import vip.mate.approval.grant.repository.ApprovalGrantMapper;
import vip.mate.approval.grant.repository.ApprovalResolutionLogMapper;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;
import java.util.Objects;

/**
 * Decides whether a tool invocation that {@code ToolGuardService.evaluate(...)}
 * already flagged as {@code NEEDS_APPROVAL} can be auto-approved by a stored
 * {@link ApprovalGrant}, or must fall back to the existing human-approval flow,
 * or must be hard-blocked.
 * <p>
 * Decision order:
 * <ol>
 *   <li>Safety floor — {@link AutoGrantSafetyFloor#evaluate(String)} short-circuits
 *       on disasters ({@code HARD_BLOCK}) and downgrades dangerous-but-occasionally-
 *       legitimate patterns to {@code FORCE_HUMAN} (skip grant lookup, fall back
 *       to manual approval).</li>
 *   <li>Severity ceiling — {@code CRITICAL} is never auto-approvable.</li>
 *   <li>Tenant gate — when {@code workspaceId} is unknown the resolver
 *       conservatively returns {@code requiresHuman("UNKNOWN_WORKSPACE")} rather
 *       than letting a malformed context match the wrong workspace.</li>
 *   <li>Grant lookup — every {@code ruleId} present on the findings is sent to
 *       the mapper as a candidate; the mapper's SQL handles scope priority and
 *       severity ceiling.</li>
 * </ol>
 *
 * <p>Whenever the resolver itself reaches a final decision (HARD_BLOCK or
 * AUTO_GRANT), it writes one row to {@code mate_approval_resolution_log}. For
 * {@code FORCE_HUMAN} / {@code SEVERITY_CRITICAL} / {@code UNKNOWN_WORKSPACE} /
 * {@code NO_GRANT} no row is written here — the row is added later by
 * {@code ApprovalWorkflowService.resolve*()} / {@code garbageCollect()} (PR-2)
 * once the human path actually completes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalGrantResolver {

    private static final int ARGS_PREVIEW_MAX = 500;

    private final ApprovalGrantMapper grantMapper;
    private final ApprovalResolutionLogMapper resolutionMapper;
    private final AutoGrantSafetyFloor safetyFloor;
    private final AutoApproveAuditLogger auditLogger;

    public AutoApproveResult tryAutoApprove(ToolInvocationContext ctx, GuardEvaluation evaluation) {
        // 1) Safety floor — hard block or force the existing human path.
        AutoGrantSafetyFloor.SafetyFloorMatch sf = safetyFloor.evaluate(ctx.rawArguments());
        if (sf.action() == AutoGrantSafetyFloor.Action.HARD_BLOCK) {
            auditLogger.logHardBlock(ctx, evaluation, sf.patternName());
            resolutionMapper.insert(
                    buildResolutionLog(ctx, evaluation,
                            ApprovalResolutionLog.DecisionSource.HARD_BLOCK,
                            null, null,
                            "matched safety floor pattern: " + sf.patternName()));
            return AutoApproveResult.hardBlocked(sf.patternName());
        }
        if (sf.action() == AutoGrantSafetyFloor.Action.FORCE_HUMAN) {
            auditLogger.logForceHuman(ctx, evaluation, sf.patternName());
            return AutoApproveResult.requiresHuman("FORCE_HUMAN:" + sf.patternName());
        }

        // 2) Severity ceiling — CRITICAL is never auto-approvable.
        if (evaluation != null && evaluation.maxSeverity() == GuardSeverity.CRITICAL) {
            return AutoApproveResult.requiresHuman("SEVERITY_CRITICAL");
        }

        // 3) workspaceId required for tenant isolation; null → conservative human path.
        if (ctx.workspaceId() == null) {
            log.warn("[APPROVAL] workspaceId=null for conversation={} agent={} tool={} — "
                            + "falling back to human approval. Check WorkspaceLookupCache wiring.",
                    ctx.conversationId(), ctx.agentId(), ctx.toolName());
            return AutoApproveResult.requiresHuman("UNKNOWN_WORKSPACE");
        }

        // 4) Collect all candidate ruleIds from findings (for IN-clause matching).
        List<String> candidateRuleIds = (evaluation == null || evaluation.findings() == null)
                ? List.of()
                : evaluation.findings().stream()
                        .map(GuardFinding::ruleId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();

        // 5) Mapper finds first grant ordered by scope priority + specificity.
        // workspaceId is also passed as a string for the WORKSPACE-scope match,
        // so the mapper SQL stays dialect-clean (no CAST). See ApprovalGrantMapper.xml.
        String workspaceScopeId = String.valueOf(ctx.workspaceId());
        String evalSeverity = evaluation == null || evaluation.maxSeverity() == null
                ? GuardSeverity.LOW.name()
                : evaluation.maxSeverity().name();

        ApprovalGrant matched = grantMapper.findFirstMatching(
                ctx.workspaceId(),
                ctx.userId(), ctx.agentId(), ctx.conversationId(),
                workspaceScopeId,
                ctx.toolName(),
                candidateRuleIds,
                evalSeverity);

        if (matched == null) {
            return AutoApproveResult.requiresHuman("NO_GRANT");
        }

        // 6) Grant matched — log, audit, and approve.
        auditLogger.logAutoGrant(matched, ctx, evaluation);
        resolutionMapper.insert(
                buildResolutionLog(ctx, evaluation,
                        ApprovalResolutionLog.DecisionSource.AUTO_GRANT,
                        matched.getId(), null, matched.getNote()));
        return AutoApproveResult.approved(matched.getId());
    }

    /**
     * Builds a {@link ApprovalResolutionLog} row for the given final decision.
     * {@code grantId} is set only for AUTO_GRANT; {@code pendingId} is set only when
     * the row is later written by the human-path hooks in
     * {@code ApprovalWorkflowService} (PR-2).
     */
    private ApprovalResolutionLog buildResolutionLog(ToolInvocationContext ctx,
                                                    GuardEvaluation evaluation,
                                                    String decisionSource,
                                                    Long grantId,
                                                    String pendingId,
                                                    String note) {
        ApprovalResolutionLog row = new ApprovalResolutionLog();
        row.setWorkspaceId(ctx.workspaceId());
        row.setConversationId(ctx.conversationId());
        row.setAgentId(ctx.agentId());
        row.setUserId(ctx.userId());
        row.setToolName(ctx.toolName());
        row.setMaxSeverity(evaluation == null || evaluation.maxSeverity() == null
                ? null : evaluation.maxSeverity().name());
        row.setRuleIds(joinRuleIds(evaluation));
        row.setDecisionSource(decisionSource);
        row.setGrantId(grantId);
        row.setPendingId(pendingId);
        row.setArgsPreview(previewArgs(ctx.rawArguments()));
        row.setNote(note);
        return row;
    }

    private static String joinRuleIds(GuardEvaluation evaluation) {
        if (evaluation == null || evaluation.findings() == null || evaluation.findings().isEmpty()) {
            return null;
        }
        return evaluation.findings().stream()
                .map(GuardFinding::ruleId)
                .filter(Objects::nonNull)
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    private static String previewArgs(String raw) {
        if (raw == null) return null;
        return raw.length() <= ARGS_PREVIEW_MAX ? raw : raw.substring(0, ARGS_PREVIEW_MAX);
    }
}
