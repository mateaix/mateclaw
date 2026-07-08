package vip.mate.plugin.api.search;

/**
 * Search query passed from the platform to a plugin search provider.
 * <p>
 * Self-contained SDK type — must not depend on any mateclaw-server class,
 * because plugin JARs are compiled only against mateclaw-plugin-api.
 *
 * @param query     search keywords (never null/blank)
 * @param freshness time-range filter: day / week / month / year (nullable)
 * @param language  language preference, e.g. zh-CN / en (nullable)
 * @param count     max results 1-10, already clamped by the platform (never null)
 *
 * @author MateClaw Team
 */
public record PluginSearchQuery(
        String query,
        String freshness,
        String language,
        Integer count
) {
}
