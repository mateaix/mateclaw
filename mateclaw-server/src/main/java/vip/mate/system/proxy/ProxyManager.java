package vip.mate.system.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.system.service.SystemSettingService;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Installs and refreshes the process-wide outbound proxy from the global
 * {@code proxy.*} system settings.
 *
 * <p>When enabled, the configured proxy is applied through three mechanisms so
 * it reaches every egress style in the backend with a single switch, instead of
 * patching dozens of scattered HTTP-client construction sites:
 * <ol>
 *   <li>A default {@link ProxySelector} — honored by every
 *       {@link java.net.http.HttpClient} built without an explicit proxy (LLM
 *       calls, model probes, most channel adapters, MCP, STT/TTS) and by
 *       {@code HttpURLConnection} (the Hutool-based search / media tools).</li>
 *   <li>{@code http(s).proxyHost}/{@code socksProxyHost} system properties — for
 *       libraries that read them directly.</li>
 *   <li>A static accessor ({@link #chromeProxyServer()}) the browser launcher
 *       reads to add {@code --proxy-server}, since Chromium does not honor the
 *       JVM proxy.</li>
 * </ol>
 *
 * <p>SOCKS proxies are honored by {@code HttpURLConnection} but silently ignored
 * by {@code java.net.http.HttpClient}; see {@link ProxySettings} for the
 * resulting coverage boundary.
 *
 * <p>Adapters that carry their own per-channel proxy (Telegram / Discord set an
 * explicit {@code .proxy()}) override the default selector, so this global proxy
 * never fights a more specific one.
 */
@Slf4j
@Component
public class ProxyManager {

    static final String KEY_ENABLED = "proxy.enabled";
    static final String KEY_URL = "proxy.url";
    static final String KEY_NON_PROXY_HOSTS = "proxy.nonProxyHosts";

    private static final String[] MANAGED_SYSTEM_PROPS = {
            "http.proxyHost", "http.proxyPort",
            "https.proxyHost", "https.proxyPort",
            "http.nonProxyHosts",
            "socksProxyHost", "socksProxyPort",
    };

    /**
     * Current {@code scheme://host:port} for Chromium's {@code --proxy-server},
     * or {@code null} when no proxy is active. Static so the static browser
     * launch-arg builder can read it without a bean reference.
     */
    private static volatile String chromeProxyServer;

    private final SystemSettingService settings;

    /** The default selector present before we ever overrode it, for restore. */
    private final ProxySelector originalDefaultSelector;
    private final Authenticator originalDefaultAuthenticator;
    private final AtomicReference<ProxySettings> current = new AtomicReference<>();
    private volatile boolean authenticatorInstalled;

    public ProxyManager(SystemSettingService settings) {
        this.settings = settings;
        this.originalDefaultSelector = ProxySelector.getDefault();
        this.originalDefaultAuthenticator = Authenticator.getDefault();
    }

    /** Chromium {@code --proxy-server} value, or {@code null} when inactive. */
    public static String chromeProxyServer() {
        return chromeProxyServer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            apply(readSettings());
        } catch (Exception e) {
            log.warn("Failed to apply proxy settings at startup: {}", e.getMessage());
        }
    }

    /** Re-read persisted settings and re-apply. Called after a config save. */
    public synchronized void refresh() {
        apply(readSettings());
    }

    public ProxySettings currentSettings() {
        ProxySettings s = current.get();
        return s != null ? s : readSettings();
    }

    public ProxySettings readSettings() {
        boolean enabled = settings.getBool(KEY_ENABLED, false);
        String url = settings.getString(KEY_URL, "");
        String nph = settings.getString(KEY_NON_PROXY_HOSTS, "");
        return ProxySettings.parse(enabled, url, nph);
    }

    /** Persist new config and apply it immediately. Returns the parsed result. */
    public synchronized ProxySettings save(boolean enabled, String url, String nonProxyHosts) {
        settings.saveBool(KEY_ENABLED, enabled, "Global outbound proxy enabled");
        settings.saveString(KEY_URL, url == null ? "" : url.trim(), "Global outbound proxy url");
        settings.saveString(KEY_NON_PROXY_HOSTS,
                nonProxyHosts == null ? "" : nonProxyHosts.trim(),
                "Global proxy bypass list (| separated)");
        ProxySettings parsed = readSettings();
        apply(parsed);
        return parsed;
    }

    private synchronized void apply(ProxySettings s) {
        current.set(s);
        clearManagedSystemProps();
        if (!s.active()) {
            ProxySelector.setDefault(originalDefaultSelector);
            uninstallAuthenticator();
            chromeProxyServer = null;
            if (s.enabled() && !s.valid()) {
                log.warn("Global proxy is enabled but the url is invalid ({}); running without a proxy",
                        s.error());
            } else {
                log.info("Global proxy disabled; outbound traffic goes direct");
            }
            return;
        }

        ProxySelector.setDefault(new GlobalProxySelector(s, originalDefaultSelector));
        setSystemProps(s);
        installAuthenticatorIfNeeded(s);
        chromeProxyServer = s.chromeProxyServer();
        log.info("Global proxy active: {} {}:{}{} (bypass: {})",
                s.isSocks() ? "SOCKS" : "HTTP", s.host(), s.port(),
                s.hasCredentials() ? " (auth)" : "", s.nonProxyHosts());
        if (s.isSocks()) {
            log.warn("SOCKS proxy applies to HttpURLConnection-based egress (search/media) only; "
                    + "the java.net.http LLM/streaming path does not support SOCKS and will go direct");
        }
    }

    private void setSystemProps(ProxySettings s) {
        if (s.isSocks()) {
            System.setProperty("socksProxyHost", s.host());
            System.setProperty("socksProxyPort", String.valueOf(s.port()));
        } else {
            System.setProperty("http.proxyHost", s.host());
            System.setProperty("http.proxyPort", String.valueOf(s.port()));
            System.setProperty("https.proxyHost", s.host());
            System.setProperty("https.proxyPort", String.valueOf(s.port()));
            // JVM uses '|'-separated patterns here — same format we persist.
            if (StringUtils.hasText(s.nonProxyHosts())) {
                System.setProperty("http.nonProxyHosts", s.nonProxyHosts());
            }
        }
    }

    private void clearManagedSystemProps() {
        for (String prop : MANAGED_SYSTEM_PROPS) {
            System.clearProperty(prop);
        }
    }

    private void installAuthenticatorIfNeeded(ProxySettings s) {
        if (!s.hasCredentials()) {
            uninstallAuthenticator();
            return;
        }
        final String user = s.username();
        final char[] pass = s.password() == null ? new char[0] : s.password().toCharArray();
        // Allow Basic proxy auth over an HTTPS CONNECT tunnel (disabled by default since JDK 8u111).
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY) {
                    return new PasswordAuthentication(user, pass);
                }
                return null;
            }
        });
        authenticatorInstalled = true;
    }

    private void uninstallAuthenticator() {
        if (authenticatorInstalled) {
            Authenticator.setDefault(originalDefaultAuthenticator);
            authenticatorInstalled = false;
        }
    }

    /**
     * Probe reachability of the configured proxy by opening a TCP connection to
     * its host:port. Confirms the proxy endpoint is listening (the common
     * failure mode — proxy app not running / wrong port) without depending on
     * upstream internet connectivity. Returns latency in milliseconds.
     */
    public ProbeResult test(String url) {
        ProxySettings s = ProxySettings.parse(true, url, null);
        if (!s.valid()) {
            return new ProbeResult(false, 0, s.error());
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(s.host(), s.port()), 4000);
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new ProbeResult(true, ms, null);
        } catch (IOException e) {
            return new ProbeResult(false, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Result of {@link #test(String)}. */
    public record ProbeResult(boolean ok, long latencyMs, String error) {
    }

    /**
     * Routes through the configured proxy unless the target host matches the
     * bypass list, in which case it defers to the selector that was the default
     * before we took over (preserving any pre-existing direct/proxy behavior).
     */
    private static final class GlobalProxySelector extends ProxySelector {
        private final List<Proxy> proxyList;
        private final List<String> bypass;
        private final ProxySelector fallback;

        GlobalProxySelector(ProxySettings s, ProxySelector fallback) {
            this.proxyList = List.of(s.toProxy());
            this.bypass = s.bypassPatterns();
            this.fallback = fallback;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri == null ? null : uri.getHost();
            if (host == null || matchesBypass(host)) {
                return fallback != null ? fallback.select(uri) : List.of(Proxy.NO_PROXY);
            }
            return proxyList;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (fallback != null) {
                fallback.connectFailed(uri, sa, ioe);
            }
        }

        private boolean matchesBypass(String host) {
            for (String pattern : bypass) {
                if (matches(pattern, host)) {
                    return true;
                }
            }
            return false;
        }

        /** Glob match with leading/trailing {@code *}, matching JVM nonProxyHosts semantics. */
        private static boolean matches(String pattern, String host) {
            if (pattern.equalsIgnoreCase(host)) {
                return true;
            }
            if (pattern.startsWith("*") && pattern.endsWith("*") && pattern.length() > 2) {
                return host.toLowerCase().contains(pattern.substring(1, pattern.length() - 1).toLowerCase());
            }
            if (pattern.startsWith("*")) {
                return host.toLowerCase().endsWith(pattern.substring(1).toLowerCase());
            }
            if (pattern.endsWith("*")) {
                return host.toLowerCase().startsWith(pattern.substring(0, pattern.length() - 1).toLowerCase());
            }
            return false;
        }
    }
}
