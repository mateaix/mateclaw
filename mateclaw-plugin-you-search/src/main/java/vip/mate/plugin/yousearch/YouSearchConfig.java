package vip.mate.plugin.yousearch;

/**
 * You.com search plugin configuration snapshot.
 * <p>
 * Read once from {@code PluginContext#getConfig} at plugin load time and passed
 * to {@link YouSearchClient} / {@link YouSearchProvider}. Snapshot semantics —
 * config changes require a plugin reload.
 *
 * @param apiKey    You.com API key, sent as the {@code X-API-Key} header (required for the provider to be available)
 * @param baseUrl   You.com Search API base URL; null/blank falls back to the default
 * @param country   optional country code for geographical focus, e.g. "us"
 * @param safesearch optional content moderation filter: strict / moderate / off
 * @param timeoutMs HTTP timeout for search calls
 * @author Mouse Parker
 */
record YouSearchConfig(
        String apiKey,
        String baseUrl,
        String country,
        String safesearch,
        int timeoutMs
) {
    static final String DEFAULT_BASE_URL = "https://ydc-index.io";
    static final int DEFAULT_TIMEOUT_MS = 15000;

    /**
     * Whether this provider should participate at all.
     * You.com search without an API key is unusable; treat as unavailable.
     */
    boolean isUsable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Effective base URL: configured value or the You.com Search API default.
     * Trailing slashes are stripped to avoid double-slash in path joins.
     */
    String normalizedBaseUrl() {
        String url = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Safesearch value as the API spells it (lowercase); null when unset.
     */
    String normalizedSafesearch() {
        if (safesearch == null || safesearch.isBlank()) {
            return null;
        }
        return safesearch.toLowerCase();
    }

    /**
     * Country code as the API spells it (lowercase); null when unset.
     */
    String normalizedCountry() {
        if (country == null || country.isBlank()) {
            return null;
        }
        return country.toLowerCase();
    }
}
