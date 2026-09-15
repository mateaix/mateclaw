package vip.mate.plugin.yousearch;

/**
 * A single You.com search hit, flattened from the API's web/news result shape
 * into the fields {@link YouSearchProvider} needs.
 *
 * @param title   result title (nullable)
 * @param url     result link (nullable)
 * @param snippet first snippet, or description (nullable)
 * @param source  source domain, e.g. "reuters.com" (nullable)
 * @param date    page age as raw string (nullable)
 * @author Mouse Parker
 */
record YouSearchHit(
        String title,
        String url,
        String snippet,
        String source,
        String date
) {
}
