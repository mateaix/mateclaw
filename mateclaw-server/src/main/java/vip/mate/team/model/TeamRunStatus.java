package vip.mate.team.model;

import java.util.Set;

/** Team run lifecycle status constants. */
public final class TeamRunStatus {

    public static final String PLANNING = "planning";
    public static final String RUNNING = "running";
    public static final String AWAITING_REVIEW = "awaiting_review";
    public static final String FINALIZING = "finalizing";
    public static final String COMPLETED = "completed";
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    public static final Set<String> TERMINAL = Set.of(COMPLETED, PARTIAL, FAILED, CANCELLED);

    private TeamRunStatus() {
    }

    public static boolean isTerminal(String status) {
        return status != null && TERMINAL.contains(status);
    }
}
