package vip.mate.plugin.yousearch;

import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Search provider that bridges MateClaw's {@code web_search} provider chain to
 * the You.com Search API.
 * <p>
 * Query mapping: {@link PluginSearchQuery#query()} / {@code count()} /
 * {@code freshness()} / {@code language()} map 1:1 onto the API's
 * {@code query} / {@code count} / {@code freshness} / {@code language} request
 * fields — both sides use the same day/week/month/year vocabulary and BCP 47
 * language codes, so no translation layer is needed.
 * <p>
 * Result mapping: the API's web (and news) results carry
 * {@code title} / {@code url} / {@code snippets[0]} (or {@code description}) /
 * {@code page_age}; the source domain is derived from the URL.
 * <p>
 * Failure semantics: {@code search} throws {@link YouSearchException} on any
 * failure — per the SPI contract, the platform catches and falls through to
 * the next provider in the chain.
 *
 * @author Mouse Parker
 */
class YouSearchProvider implements PluginSearchProvider {

    static final String ID = "you";

    private final YouSearchConfig config;
    private final YouSearchClient client;

    YouSearchProvider(YouSearchConfig config, YouSearchClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "You.com";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        // Cheap config check only — no network I/O, per the SPI contract.
        return config.isUsable();
    }

    @Override
    public List<PluginSearchResult> search(PluginSearchQuery query) {
        List<YouSearchHit> hits = client.search(
                query.query(),
                query.count(),
                query.freshness(),
                query.language());

        List<PluginSearchResult> results = new ArrayList<>(hits.size());
        for (YouSearchHit hit : hits) {
            results.add(new PluginSearchResult(
                    hit.title(),
                    hit.url(),
                    hit.snippet(),
                    hit.source(),
                    hit.date()));
        }
        return results;
    }
}
