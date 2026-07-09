package vip.mate.tool.disclosure;

import org.springframework.ai.tool.ToolCallback;
import vip.mate.agent.AgentToolSet;

import java.util.List;
import java.util.Set;

/**
 * Splits an agent's tool set into the subset advertised to the LLM up front
 * ({@code core} + already-enabled extensions) and the {@code extension} catalog
 * that stays behind {@code enable_tool} until the model activates it.
 *
 * <p>Tier is resolved per source: builtin / channel atomic tools from
 * {@code mate_tool.disclosure_tier}, MCP tools from their owning
 * {@code mate_mcp_server.disclosure_tier}. Tools that cannot be classified
 * (ACP / dynamic-skill wrapped, plugin tools) default to {@code core} so the
 * feature never hides a tool it does not understand.
 */
public interface ToolDisclosureService {

    /** Resolve the tier of a runtime tool callback. */
    DisclosureTier resolveTier(ToolCallback callback);

    /** Resolve the tier of a tool by its function name. */
    DisclosureTier resolveTierByName(String toolName);

    /**
     * Split {@code baseSet} into active callbacks (core ∪ enabled extensions)
     * and the full extension catalog (every extension tool, enabled or not).
     */
    ToolDisclosureSplit split(AgentToolSet baseSet, Set<String> enabledExtensions);

    /**
     * Budget-aware variant: tools in {@code autoDemoted} are treated as
     * extension tier for this split even when their resolved tier is core.
     * The demotion set is decided once per agent build (see
     * {@link #computeAutoDemotions}) so the runtime split, the baked catalog
     * and the prompt-cache prefix stay consistent with each other.
     */
    default ToolDisclosureSplit split(AgentToolSet baseSet, Set<String> enabledExtensions,
                                      Set<String> autoDemoted) {
        return split(baseSet, enabledExtensions);
    }

    /**
     * Decide which core-tier tools to auto-demote so the advertised tool
     * schemas fit {@code budgetTokens} (estimated). Ranking: never-used tools
     * first, then least recently used; meta-tools and explicitly configured
     * core tools are never demoted. Empty when the set already fits, when
     * {@code budgetTokens} is null, or in legacy disclosure mode.
     */
    default Set<String> computeAutoDemotions(AgentToolSet baseSet, Integer budgetTokens) {
        return Set.of();
    }

    /**
     * Render the {@code ## Extension Tools} system-prompt segment for the
     * agent's extension tools, or an empty string when there are none / when
     * disclosure is disabled.
     */
    String renderExtensionCatalog(AgentToolSet baseSet, Integer maxInputTokens);

    /**
     * Budget-aware variant: auto-demoted tools are listed in the catalog too,
     * so the model can discover and {@code enable_tool} them back.
     */
    default String renderExtensionCatalog(AgentToolSet baseSet, Integer maxInputTokens,
                                          Set<String> autoDemoted) {
        return renderExtensionCatalog(baseSet, maxInputTokens);
    }

    /** Drop the cached tier snapshot so the next resolve re-reads the DB. */
    void invalidate();

    /**
     * Result of {@link #split}: {@code activeCallbacks} go to the LLM now;
     * {@code extensionCatalog} is every extension tool (enabled or not), used to
     * render the prompt catalog.
     */
    record ToolDisclosureSplit(List<ToolCallback> activeCallbacks, List<ToolCallback> extensionCatalog) {
    }
}
