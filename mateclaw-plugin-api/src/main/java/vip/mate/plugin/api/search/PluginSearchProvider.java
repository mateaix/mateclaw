package vip.mate.plugin.api.search;

import java.util.List;

/**
 * SPI for plugin-provided web-search providers.
 * <p>
 * Implementations are registered via {@code PluginContext#registerSearchProvider}
 * and appear in the platform's search provider chain alongside the built-in
 * providers (serper / tavily / searxng / duckduckgo).
 * <p>
 * Configuration (API keys, base URLs, ...) is NOT passed in — plugins read their
 * own config declared in {@code mateclaw-plugin.json} via
 * {@code PluginContext#getConfig(String, Class)}.
 *
 * @author MateClaw Team
 */
public interface PluginSearchProvider {

    /** Globally unique provider id, e.g. "my-search". Must not clash with built-in ids. */
    String id();

    /** Human-readable display name. */
    String label();

    /** Whether this provider needs a credential (affects auto-detect priority). */
    default boolean requiresCredential() {
        return true;
    }

    /**
     * Auto-detect ordering (ascending). Built-in providers occupy 50-400;
     * plugin providers default to 500 (after built-ins) but may override.
     */
    default int autoDetectOrder() {
        return 500;
    }

    /**
     * Whether the provider is currently usable — typically: required config present.
     * Called on every provider resolution; keep it cheap (no network I/O).
     */
    boolean isAvailable();

    /**
     * Execute the search.
     *
     * @param query the query (never null)
     * @return results; empty list if nothing found. Must not return null.
     *         Throw on failure — the platform falls back to the next provider.
     */
    List<PluginSearchResult> search(PluginSearchQuery query);
}
