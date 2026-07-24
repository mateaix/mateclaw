package vip.mate.team.model;

import java.util.Set;

/**
 * Team task state machine constants.
 *
 * <pre>
 * pending ──claim/assign──▶ in_progress ──complete──▶ completed
 *    │                          │  (require_approval) ▶ in_review ──approve──▶ completed
 *    │                          │                                └──reject───▶ cancelled
 *    │                          ├──blocker/error──▶ failed ──retry──▶ pending
 *    │                          └──lease expired──▶ stale  ──retry──▶ pending
 *    ├──blocked_by set──▶ blocked ──all blockers released──▶ pending
 *    └──cancel──▶ cancelled
 * </pre>
 *
 * @author MateClaw Team
 */
public final class TeamTaskStatus {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String IN_REVIEW = "in_review";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String BLOCKED = "blocked";
    public static final String STALE = "stale";

    /** No further transitions except hard delete. */
    public static final Set<String> TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED);

    /** Statuses that release dependent (blocked) tasks. Failed does NOT release. */
    public static final Set<String> RELEASES_DEPENDENTS = Set.of(COMPLETED, CANCELLED);

    /** Statuses eligible for a manual retry back to pending. */
    public static final Set<String> RETRYABLE = Set.of(FAILED, STALE);

    private TeamTaskStatus() {
    }

    public static boolean isTerminal(String status) {
        return status != null && TERMINAL.contains(status);
    }
}
