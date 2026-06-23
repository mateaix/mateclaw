package vip.mate.llm.routing;

import java.util.List;
import java.util.Set;

/**
 * Read access to an agent's skill / provider / wiki-kb bindings, as needed by
 * {@link ProviderRouter} for capability-aware routing and by webchat
 * endpoints that need to enumerate an agent's visible catalog.
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

    /**
     * Wiki knowledge-base ids bound to the agent, or {@code null} when the
     * agent has no explicit KB scope (meaning "workspace-wide — every KB
     * in the agent's workspace is visible"). Mirrors the three-state
     * contract of {@link #getBoundSkillIds}: {@code null} = inherit
     * default, {@code Set.of()} = explicitly scoped to nothing, non-empty
     * = the explicit allowlist.
     */
    Set<Long> getBoundKbIds(Long agentId);
}
