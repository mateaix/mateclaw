package vip.mate.plugin.mem0;

import org.slf4j.Logger;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;

/**
 * MateClaw plugin entrypoint that registers {@link Mem0Provider} with the
 * platform's memory subsystem.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@code onLoad} — read config from {@link PluginContext}, build
 *       {@link Mem0Config} → {@link Mem0Client} → {@link Mem0Provider},
 *       then {@code context.registerMemoryProvider(provider)}.
 *       If the config is incomplete (no baseUrl), the provider is registered
 *       but reports {@code isAvailable()=false} — the platform silently
 *       skips it.</li>
 *   <li>{@code onEnable} / {@code onDisable} — lifecycle log only.</li>
 * </ol>
 *
 * <p>This plugin is NOT part of the default stack. Users must:
 * <ol>
 *   <li>Self-host a Mem0 service (FastAPI + pgvector + optional Neo4j)</li>
 *   <li>Drop the built JAR into the platform's {@code plugins/} directory</li>
 *   <li>Configure {@code baseUrl} (and optionally {@code apiKey}) via the
 *       plugin admin UI</li>
 * </ol>
 *
 * @author MateClaw Team
 */
public class Mem0Plugin implements MateClawPlugin {

    private static final String CONFIG_BASE_URL = "baseUrl";
    private static final String CONFIG_API_KEY = "apiKey";
    private static final String CONFIG_SEARCH_ENABLED = "searchEnabled";
    private static final String CONFIG_SYNC_ENABLED = "syncEnabled";
    private static final String CONFIG_MAX_RESULTS = "maxResults";
    private static final String CONFIG_TIMEOUT_MS = "timeoutMs";

    private Logger log;

    @Override
    public void onLoad(PluginContext context) {
        this.log = context.getLogger();

        Mem0Config config = readConfig(context);
        if (!config.isUsable()) {
            log.warn("Mem0 plugin loaded without baseUrl — provider will stay unavailable. "
                    + "Configure 'baseUrl' in the plugin config to enable.");
        }

        Mem0Client client = new Mem0Client(config);
        Mem0Provider provider = new Mem0Provider(config, client, log);
        context.registerMemoryProvider(provider);

        log.info("Mem0 plugin loaded: baseUrl={}, searchEnabled={}, syncEnabled={}, maxResults={}, timeoutMs={}",
                maskUrl(config.baseUrl()), config.searchEnabled(), config.syncEnabled(),
                config.maxResults(), config.timeoutMs());
    }

    @Override
    public void onEnable() {
        if (log != null) log.info("Mem0 plugin enabled");
    }

    @Override
    public void onDisable() {
        if (log != null) log.info("Mem0 plugin disabled");
    }

    private Mem0Config readConfig(PluginContext ctx) {
        String baseUrl = ctx.getConfig(CONFIG_BASE_URL, String.class);
        String apiKey = ctx.getConfig(CONFIG_API_KEY, String.class);
        Boolean searchEnabled = ctx.getConfig(CONFIG_SEARCH_ENABLED, Boolean.class);
        Boolean syncEnabled = ctx.getConfig(CONFIG_SYNC_ENABLED, Boolean.class);
        Integer maxResults = ctx.getConfig(CONFIG_MAX_RESULTS, Integer.class);
        Integer timeoutMs = ctx.getConfig(CONFIG_TIMEOUT_MS, Integer.class);

        return new Mem0Config(
                baseUrl,
                apiKey,
                searchEnabled == null ? true : searchEnabled,
                syncEnabled == null ? true : syncEnabled,
                maxResults == null ? Mem0Config.DEFAULT_MAX_RESULTS : maxResults,
                timeoutMs == null ? Mem0Config.DEFAULT_TIMEOUT_MS : timeoutMs
        );
    }

    /**
     * Mask credentials in the URL when logging. Keeps the scheme + host,
     * strips any user info and path.
     */
    private static String maskUrl(String url) {
        if (url == null || url.isBlank()) return "(unset)";
        try {
            java.net.URI u = java.net.URI.create(url);
            String host = u.getHost();
            int port = u.getPort();
            return u.getScheme() + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return "(malformed)";
        }
    }
}
