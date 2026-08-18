package vip.mate.skill.event;

/**
 * Fires after an agent authored a brand-new skill for itself during a
 * conversation (the {@code skill_manage create} path), carrying the authoring
 * agent so downstream listeners can react to "this agent just learned
 * something".
 *
 * <p>Distinct from {@link SkillUpdatedEvent}, which covers every write to an
 * existing row regardless of origin. This event fires only on creation and
 * only when the write came from an agent turn, so it carries the one piece of
 * context {@code SkillService} cannot see: which agent was talking.
 *
 * <p>The primary consumer is the auto-bind listener in the {@code agent}
 * layer: a self-authored skill is useless if the authoring agent's own
 * catalog cannot see it, which is exactly what happens when the agent runs
 * with an explicit skill allowlist. Publishing an event (rather than calling
 * the binding service directly from the tool) keeps the dependency direction
 * {@code agent → skill} intact and avoids a circular bean graph, matching the
 * reasoning already documented on {@link SkillUpdatedEvent}.
 *
 * @param skillId        DB id of the newly created skill row
 * @param skillName      slug identifier the row carries, useful for log lines
 * @param agentId        agent that authored the skill; {@code null} when the
 *                       write had no agent origin (e.g. a REST call)
 * @param conversationId conversation the skill was distilled from, or
 *                       {@code null} when unknown
 * @param workspaceId    workspace the new skill row was stamped with
 */
public record SkillAuthoredEvent(Long skillId,
                                 String skillName,
                                 Long agentId,
                                 String conversationId,
                                 Long workspaceId) {
}
