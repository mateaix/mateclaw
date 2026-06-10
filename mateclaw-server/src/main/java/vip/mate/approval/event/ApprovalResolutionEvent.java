package vip.mate.approval.event;

/**
 * Generic application event fired AFTER an approval row reaches a final
 * decision through the human-approval path (approved / denied / consumed) or
 * through the timeout sweep.
 * <p>
 * Distinct from {@code WorkflowApprovalResolvedEvent}, which is workflow-bridge
 * specific (only published for {@code pendingId} starting with {@code "wf-"}).
 * This event is published for every tool-call approval row so the auto-grant
 * resolution-log subsystem can record exactly one row per final decision.
 *
 * <p><b>Decision source mapping</b> (see {@code ApprovalResolutionLog.DecisionSource}):
 * <ul>
 *   <li>{@code APPROVED} / {@code DENIED} / {@code CONSUMED} → {@code USER_MANUAL}</li>
 *   <li>{@code TIMEOUT} → {@code TIMEOUT}</li>
 *   <li>{@code SUPERSEDED} → no event (not a final user decision; the replacement
 *       approval will fire its own event when it resolves)</li>
 * </ul>
 *
 * <p>{@code findingsJson} is the original JSON serialization of the
 * {@code GuardEvaluation.findings} captured at {@code createPending} time.
 * The listener extracts {@code ruleId}s from it for {@code resolution_log.rule_ids}.
 *
 * <p>All fields are nullable: a row created via the legacy command-injection
 * path may not carry every snapshot field. The listener treats missing fields
 * as empty rather than skipping the row, so the resolution-log audit stays
 * complete even when upstream context is partial.
 */
public record ApprovalResolutionEvent(
        String pendingId,
        String conversationId,
        String agentId,
        String userId,
        String toolName,
        String toolArguments,
        String maxSeverity,
        String findingsJson,
        String decisionSource,
        String resolutionNote
) {}
