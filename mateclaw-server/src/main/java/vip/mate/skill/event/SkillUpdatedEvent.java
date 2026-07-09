package vip.mate.skill.event;

/**
 * Fires after a skill row has been updated (metadata, SKILL.md content,
 * security rescan, or enabled-flag toggle) so downstream listeners can
 * react to the new state.
 *
 * <p>Mirrors {@link SkillRemovedEvent} in shape, with an added
 * {@code changeType} hint so listeners can skip no-op refreshes. The
 * publisher is {@code SkillService} (which already holds the
 * {@code ApplicationEventPublisher}), avoiding a circular dependency on
 * {@code SkillRuntimeService} or {@code AgentService}.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Environment event routing (C3) notifies running conversations
 *       that a skill's constraints or flow may have changed.</li>
 *   <li>Agent cache invalidation so the next turn picks up the new
 *       manifest.</li>
 * </ul>
 *
 * @param skillId    DB id of the updated skill row
 * @param skillName  slug identifier the row carries, useful for log lines
 * @param changeType {@code updated} | {@code toggled} | {@code rescanned}
 */
public record SkillUpdatedEvent(Long skillId, String skillName, String changeType) {
}
