package vip.mate.skill.model;

import java.util.List;

/**
 * Who authored a skill, read as a policy flag: <em>may autonomous curation
 * mutate this skill?</em>
 *
 * <p>The distinction that matters is not "which code path wrote the row" but
 * "was a user present and asking for it". A skill the user requested in a live
 * conversation is theirs — aging it out on the same clock as one the system
 * invented on its own would delete work they deliberately asked for. A skill
 * written by the background reviewer or the routine miner has no such standing:
 * nobody asked for it, so nobody is surprised when it expires unused.
 *
 * <p>Deliberately not inferred. Usage telemetry cannot establish authorship —
 * a heavily-patched skill proves the agent maintains it, not that the agent
 * wrote it — so the value is stamped at write time by the caller that knows,
 * and never guessed afterwards.
 *
 * @author MateClaw Team
 */
public enum SkillOrigin {

    /**
     * Authored in a foreground conversation at the user's request, or created
     * through the admin UI. Off-limits to autonomous curation.
     */
    USER("user"),

    /** Authored by the out-of-band reflection reviewer. Curator-managed. */
    AGENT("agent"),

    /** Authored by routine mining from a recurring request. Curator-managed. */
    ROUTINE("routine");

    private final String code;

    SkillOrigin(String code) {
        this.code = code;
    }

    /** Persisted column value. */
    public String code() {
        return code;
    }

    /** Whether autonomous curation may age or rewrite skills of this origin. */
    public boolean curatorManaged() {
        return this != USER;
    }

    /** Column values the curator is allowed to touch. */
    public static List<String> curatorManagedCodes() {
        return List.of(AGENT.code, ROUTINE.code);
    }
}
