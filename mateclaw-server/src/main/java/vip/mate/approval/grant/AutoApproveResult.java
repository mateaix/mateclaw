package vip.mate.approval.grant;

/**
 * Tri-state outcome of {@code ApprovalGrantResolver.tryAutoApprove(...)}.
 * <p>
 * The resolver never throws on a missing grant or a fallback condition; it
 * returns one of these three states and lets the caller
 * ({@code ToolExecutionExecutor.evaluateGuard()}) map them to the right
 * {@code GuardDecision}.
 *
 * <ul>
 *   <li>{@link #approved(Long)} — caller skips {@code createPending(...)} and
 *       runs the tool directly. Carries the matched grant id.</li>
 *   <li>{@link #hardBlocked(String)} — caller returns
 *       {@code GuardDecision.blocked(...)}. No approval banner. Carries the
 *       hard-floor pattern name for log/audit context.</li>
 *   <li>{@link #requiresHuman(String)} — caller falls back to the existing
 *       human approval flow. The {@code reason} is a short tag (e.g.
 *       {@code "FORCE_HUMAN:pipe_shell"}, {@code "SEVERITY_CRITICAL"},
 *       {@code "UNKNOWN_WORKSPACE"}, {@code "NO_GRANT"}) for logging.</li>
 * </ul>
 */
public final class AutoApproveResult {

    private enum State { APPROVED, HARD_BLOCKED, REQUIRES_HUMAN }

    private final State state;
    private final Long grantId;
    private final String reason;

    private AutoApproveResult(State state, Long grantId, String reason) {
        this.state = state;
        this.grantId = grantId;
        this.reason = reason;
    }

    public static AutoApproveResult approved(Long grantId) {
        return new AutoApproveResult(State.APPROVED, grantId, null);
    }

    public static AutoApproveResult hardBlocked(String reason) {
        return new AutoApproveResult(State.HARD_BLOCKED, null, reason);
    }

    public static AutoApproveResult requiresHuman(String reason) {
        return new AutoApproveResult(State.REQUIRES_HUMAN, null, reason);
    }

    public boolean isApproved()     { return state == State.APPROVED; }
    public boolean isHardBlocked()  { return state == State.HARD_BLOCKED; }
    public boolean isRequiresHuman() { return state == State.REQUIRES_HUMAN; }

    /** Non-null only when {@link #isApproved()} is true. */
    public Long grantId() { return grantId; }

    /** Non-null when {@link #isHardBlocked()} or {@link #isRequiresHuman()}. */
    public String reason() { return reason; }
}
