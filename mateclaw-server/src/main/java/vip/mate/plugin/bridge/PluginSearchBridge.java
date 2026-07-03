package vip.mate.plugin.bridge;

import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.search.SearchProvider;
import vip.mate.tool.search.SearchQuery;
import vip.mate.tool.search.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge that wraps a plugin's {@link PluginSearchProvider} into the platform's
 * internal {@link SearchProvider} interface (issue #477).
 * <p>
 * The platform-side {@link SystemSettingsDTO} is intentionally ignored — plugin
 * providers read their own config via {@code PluginContext#getConfig}, keeping
 * the SDK free of server types.
 *
 * @author MateClaw Team
 */
public class PluginSearchBridge implements SearchProvider {

    private final PluginSearchProvider delegate;

    public PluginSearchBridge(PluginSearchProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String label() {
        return delegate.label();
    }

    @Override
    public boolean requiresCredential() {
        return delegate.requiresCredential();
    }

    @Override
    public int autoDetectOrder() {
        return delegate.autoDetectOrder();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return delegate.isAvailable();
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        return search(SearchQuery.of(query), config);
    }

    @Override
    public List<SearchResult> search(SearchQuery searchQuery, SystemSettingsDTO config) {
        PluginSearchQuery pluginQuery = new PluginSearchQuery(
                searchQuery.query(),
                searchQuery.freshness(),
                searchQuery.language(),
                searchQuery.resolvedCount()
        );
        List<PluginSearchResult> pluginResults = delegate.search(pluginQuery);
        if (pluginResults == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>(pluginResults.size());
        for (PluginSearchResult r : pluginResults) {
            results.add(SearchResult.builder()
                    .title(r.title())
                    .url(r.url())
                    .snippet(r.snippet())
                    .source(r.source())
                    .date(r.date())
                    .providerId(delegate.id())
                    .build());
        }
        return results;
    }
}
