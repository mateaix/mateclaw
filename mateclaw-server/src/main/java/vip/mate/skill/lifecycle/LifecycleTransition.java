package vip.mate.skill.lifecycle;

/**
 * The transition a skill should undergo on a lifecycle sweep.
 *
 * @author MateClaw Team
 */
public enum LifecycleTransition {
    /** No change needed. */
    NONE,
    /** active -> stale (idle past the stale threshold). */
    TO_STALE,
    /** stale -> archived (idle past the archive threshold). */
    TO_ARCHIVED,
    /** stale -> active (activity observed again). */
    REACTIVATE
}
