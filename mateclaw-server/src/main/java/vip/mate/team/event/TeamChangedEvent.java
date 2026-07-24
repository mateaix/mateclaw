package vip.mate.team.event;

import java.util.List;

/**
 * Published when a team's composition or configuration changes. Listeners
 * evict the affected agents' cached runtime instances so the team context
 * baked into their system prompts is rebuilt on the next turn.
 *
 * @param agentIds every agent whose prompt may embed this team's context
 * @author MateClaw Team
 */
public record TeamChangedEvent(List<Long> agentIds) {
}
