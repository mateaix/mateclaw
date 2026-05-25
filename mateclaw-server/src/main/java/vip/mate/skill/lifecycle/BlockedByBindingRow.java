package vip.mate.skill.lifecycle;

import java.util.List;

/**
 * A skill that satisfies the curator's idle time window but is kept out of
 * the candidate set because it is explicitly bound to one or more enabled
 * agents. Surfaced in the run report so an admin can see what was held back.
 *
 * @author MateClaw Team
 */
public record BlockedByBindingRow(
        Long skillId,
        String name,
        List<Long> agentIds,
        long daysIdle) {
}
