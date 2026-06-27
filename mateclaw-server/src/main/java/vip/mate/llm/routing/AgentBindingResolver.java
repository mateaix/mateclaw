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
     *
     * <p>Id-only view kept for capability-aware provider reordering. Routing
     * that needs the model dimension should use
     * {@link #getPreferredProviderModels(Long)}.
     */
    List<String> getPreferredProviderIds(Long agentId);

    /**
     * Ordered preferred-model chain for the agent: each entry is a provider
     * plus an optional pinned model ({@code modelId == null} = the provider's
     * default chat model). The same provider may repeat with different models,
     * so an agent can express a chain like {@code A/modelX → A/modelY →
     * B/modelZ}. Empty when the agent has no preferences.
     */
    List<ProviderModelRef> getPreferredProviderModels(Long agentId);

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
