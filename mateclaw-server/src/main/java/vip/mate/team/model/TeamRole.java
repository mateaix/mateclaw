package vip.mate.team.model;

/**
 * Team membership role constants.
 *
 * @author MateClaw Team
 */
public final class TeamRole {

    /** Orchestrates the team; receives the full task-board playbook. */
    public static final String LEAD = "lead";

    /** Executes assigned tasks. */
    public static final String MEMBER = "member";

    /** Reviews work submitted for approval. */
    public static final String REVIEWER = "reviewer";

    private TeamRole() {
    }
}
