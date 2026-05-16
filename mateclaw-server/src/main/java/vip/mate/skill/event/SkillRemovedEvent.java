package vip.mate.skill.event;

/**
 * Fires after a skill row has been removed from {@code mate_skill}, whether
 * through the user-facing uninstall path or the admin hard-delete path.
 *
 * <p>Downstream listeners use this to scrub records that reference the
 * deleted skill — most importantly the agent-skill binding rows in
 * {@code mate_agent_skill}, which would otherwise leave orphan bindings the
 * UI can't unset (the binding count stays > 0 and the picker can no longer
 * render the row to uncheck it).
 *
 * @param skillId   DB id of the removed skill row
 * @param skillName slug identifier the row carried, useful for log lines
 */
public record SkillRemovedEvent(Long skillId, String skillName) {
}
