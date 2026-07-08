package vip.mate.plugin.api.search;

/**
 * A single search result returned by a plugin search provider.
 * <p>
 * Self-contained SDK type — mirrors the platform's internal SearchResult
 * (title/url/snippet/source/date) without depending on server classes.
 *
 * @param title   result title
 * @param url     result link
 * @param snippet short excerpt
 * @param source  source domain, e.g. "reuters.com" (nullable)
 * @param date    published date as raw string (nullable)
 *
 * @author MateClaw Team
 */
public record PluginSearchResult(
        String title,
        String url,
        String snippet,
        String source,
        String date
) {
}
