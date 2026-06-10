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
     * Render the {@code ## Extension Tools} system-prompt segment for the
     * agent's extension tools, or an empty string when there are none / when
     * disclosure is disabled.
     */
    String renderExtensionCatalog(AgentToolSet baseSet, Integer maxInputTokens);

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
