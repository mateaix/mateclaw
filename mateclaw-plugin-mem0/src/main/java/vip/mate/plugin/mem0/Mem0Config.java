package vip.mate.plugin.mem0;

/**
 * Mem0 plugin configuration snapshot.
 * <p>
 * Read once from {@link vip.mate.plugin.api.PluginContext#getConfig} at plugin
 * load time and passed to {@link Mem0Client} / {@link Mem0Provider}. Snapshot
 * semantics — config changes require a plugin reload.
 *
 * @param baseUrl       Mem0 REST API base URL, e.g. {@code http://localhost:8080}
 * @param apiKey        optional bearer token; null/blank means no Authorization header
 * @param searchEnabled whether prefetch should query Mem0 /memories/search/
 * @param syncEnabled   whether syncTurn should POST to Mem0 /memories/
 * @param maxResults    cap on memories returned per recall
 * @param timeoutMs     HTTP timeout for both recall and sync
 * @author MateClaw Team
 */
record Mem0Config(
        String baseUrl,
        String apiKey,
        boolean searchEnabled,
        boolean syncEnabled,
        int maxResults,
        int timeoutMs
) {
    static final int DEFAULT_MAX_RESULTS = 5;
    static final int DEFAULT_TIMEOUT_MS = 3000;

    /**
     * Whether this provider should participate at all.
     * Mem0 without a base URL is unusable; treat as unavailable.
     */
    boolean isUsable() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Strip trailing slashes from the base URL to avoid double-slash in path joins.
     */
    String normalizedBaseUrl() {
        String url = baseUrl;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
