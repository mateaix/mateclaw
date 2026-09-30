package vip.mate.plugin.yousearch;

import org.slf4j.Logger;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;

/**
 * MateClaw plugin entrypoint that registers {@link YouSearchProvider} with the
 * platform's search provider chain.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@code onLoad} — read config from {@link PluginContext}, build
 *       {@link YouSearchConfig} → {@link YouSearchClient} → {@link YouSearchProvider},
 *       then {@code context.registerSearchProvider(provider)}.
 *       If no API key is configured, the provider is registered but reports
 *       {@code isAvailable()=false} — the platform silently skips it.</li>
 *   <li>{@code onEnable} / {@code onDisable} — lifecycle log only.</li>
 * </ol>
 *
 * <p>This plugin is NOT part of the default stack. Users must:
 * <ol>
 *   <li>Get a You.com API key at https://you.com/platform/api-keys</li>
 *   <li>Build the plugin JAR ({@code mvn -pl mateclaw-plugin-you-search -am package})
 *       and drop it into the platform's {@code plugins/} directory</li>
 *   <li>Configure {@code apiKey} (and optionally {@code baseUrl} / {@code country} /
 *       {@code safesearch} / {@code timeoutMs}) via the plugin admin UI</li>
 * </ol>
 *
 * @author Mouse Parker
 */
public class YouSearchPlugin implements MateClawPlugin {

    private static final String CONFIG_API_KEY = "apiKey";
    private static final String CONFIG_BASE_URL = "baseUrl";
    private static final String CONFIG_COUNTRY = "country";
    private static final String CONFIG_SAFESEARCH = "safesearch";
    private static final String CONFIG_TIMEOUT_MS = "timeoutMs";

    private Logger log;

    @Override
    public void onLoad(PluginContext context) {
        this.log = context.getLogger();

        YouSearchConfig config = readConfig(context);
        if (!config.isUsable()) {
            log.warn("You.com search plugin loaded without apiKey — provider will stay unavailable. "
                    + "Configure 'apiKey' in the plugin config to enable.");
        }

        YouSearchClient client = new YouSearchClient(config);
        YouSearchProvider provider = new YouSearchProvider(config, client);
        context.registerSearchProvider(provider);

        log.info("You.com search plugin loaded: baseUrl={}, country={}, safesearch={}, timeoutMs={}",
                config.normalizedBaseUrl(), config.normalizedCountry(),
                config.normalizedSafesearch(), config.timeoutMs());
    }

    @Override
    public void onEnable() {
        if (log != null) log.info("You.com search plugin enabled");
    }

    @Override
    public void onDisable() {
        if (log != null) log.info("You.com search plugin disabled");
    }

    private YouSearchConfig readConfig(PluginContext ctx) {
        String apiKey = ctx.getConfig(CONFIG_API_KEY, String.class);
        String baseUrl = ctx.getConfig(CONFIG_BASE_URL, String.class);
        String country = ctx.getConfig(CONFIG_COUNTRY, String.class);
        String safesearch = ctx.getConfig(CONFIG_SAFESEARCH, String.class);
        Integer timeoutMs = ctx.getConfig(CONFIG_TIMEOUT_MS, Integer.class);

        return new YouSearchConfig(
                apiKey,
                baseUrl,
                country,
                safesearch,
                timeoutMs == null ? YouSearchConfig.DEFAULT_TIMEOUT_MS : timeoutMs
        );
    }
}
