package vip.mate.llm.routing;

import java.util.List;
import java.util.Set;

/**
 * Read access to an agent's skill / provider bindings, as needed by
 * {@link ProviderRouter} for capability-aware routing.
 *
 * <p>Declared in the {@code llm} layer so the routing code depends only on
 * this abstraction. The {@code agent} layer supplies the implementation,
 * keeping the dependency direction {@code agent → llm}.
 */
public interface AgentBindingResolver {

    /**
     * Skill ids bound to the agent, or {@code null} when the agent has no
     * explicit bindings (meaning "use the global default").
     */
    Set<Long> getBoundSkillIds(Long agentId);

    /**
     * Provider ids the agent prefers, in priority order; empty when none.
     */
    List<String> getPreferredProviderIds(Long agentId);
}
