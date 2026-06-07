package vip.mate.system.proxy;

import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed, validated form of the global outbound-proxy configuration.
 *
 * <p>The raw config is a single proxy URL — {@code http://127.0.0.1:7890},
 * {@code https://host:443}, or {@code socks5://127.0.0.1:1080} — with optional
 * {@code user:pass@} credentials. The scheme selects the proxy type:
 * {@code socks}/{@code socks5}/{@code socks4} map to a SOCKS proxy, everything
 * else to an HTTP proxy.
 *
 * <p>HTTP/HTTPS proxies are honored across the whole backend (the JDK
 * {@link java.net.http.HttpClient} and {@code HttpURLConnection} both respect a
 * default {@link java.net.ProxySelector}). SOCKS is only honored by the
 * {@code HttpURLConnection}-based egress (search / media tools); the
 * {@code java.net.http} client silently ignores a SOCKS proxy, so SOCKS does not
 * cover the LLM / streaming path — callers must use an HTTP proxy for that.
 */
public final class ProxySettings {

    /** Default bypass list applied when the user leaves the field blank. */
    public static final String DEFAULT_NON_PROXY_HOSTS =
            "localhost|127.*|[::1]|10.*|172.16.*|172.17.*|172.18.*|172.19.*|"
                    + "172.2*|172.30.*|172.31.*|192.168.*|*.local";

    private final boolean enabled;
    private final String url;
    private final String nonProxyHosts;

    // Derived (only meaningful when valid()).
    private final Proxy.Type type;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean valid;
    private final String error;

    private ProxySettings(boolean enabled, String url, String nonProxyHosts,
                          Proxy.Type type, String host, int port,
                          String username, String password, boolean valid, String error) {
        this.enabled = enabled;
        this.url = url;
        this.nonProxyHosts = nonProxyHosts;
        this.type = type;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.valid = valid;
        this.error = error;
    }

    /**
     * Parse persisted values into a validated settings object. Never throws —
     * an unparseable URL yields {@code valid() == false} with {@link #error()}
     * populated, so a bad row can't crash startup.
     */
    public static ProxySettings parse(boolean enabled, String url, String nonProxyHosts) {
        String nph = StringUtils.hasText(nonProxyHosts) ? nonProxyHosts.trim() : DEFAULT_NON_PROXY_HOSTS;
        if (!StringUtils.hasText(url)) {
            return new ProxySettings(enabled, url, nph, null, null, -1, null, null, false,
                    "proxy url is empty");
        }
        String trimmed = url.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (Exception e) {
            return new ProxySettings(enabled, url, nph, null, null, -1, null, null, false,
                    "malformed proxy url: " + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        Proxy.Type proxyType = switch (scheme) {
            case "socks", "socks5", "socks4" -> Proxy.Type.SOCKS;
            case "http", "https" -> Proxy.Type.HTTP;
            default -> null;
        };
        if (proxyType == null) {
            return new ProxySettings(enabled, url, nph, null, null, -1, null, null, false,
                    "unsupported proxy scheme: " + scheme + " (use http/https/socks5)");
        }
        String h = uri.getHost();
        int p = uri.getPort();
        if (!StringUtils.hasText(h) || p <= 0) {
            return new ProxySettings(enabled, url, nph, null, null, -1, null, null, false,
                    "proxy url must include host and port, e.g. http://127.0.0.1:7890");
        }
        String user = null;
        String pass = null;
        String userInfo = uri.getUserInfo();
        if (StringUtils.hasText(userInfo)) {
            int idx = userInfo.indexOf(':');
            if (idx >= 0) {
                user = userInfo.substring(0, idx);
                pass = userInfo.substring(idx + 1);
            } else {
                user = userInfo;
            }
        }
        return new ProxySettings(enabled, trimmed, nph, proxyType, h, p, user, pass, true, null);
    }

    public boolean enabled() {
        return enabled;
    }

    /** True when the proxy should actually be installed: enabled AND parseable. */
    public boolean active() {
        return enabled && valid;
    }

    public boolean valid() {
        return valid;
    }

    public String error() {
        return error;
    }

    public String url() {
        return url;
    }

    public String nonProxyHosts() {
        return nonProxyHosts;
    }

    public Proxy.Type type() {
        return type;
    }

    public boolean isSocks() {
        return type == Proxy.Type.SOCKS;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public boolean hasCredentials() {
        return StringUtils.hasText(username);
    }

    /** A {@link Proxy} instance for explicit injection where needed. */
    public Proxy toProxy() {
        return new Proxy(type, new InetSocketAddress(host, port));
    }

    /**
     * The {@code --proxy-server} value for Chromium. Chromium accepts
     * {@code scheme://host:port} but not embedded credentials, so userinfo is
     * dropped here.
     */
    public String chromeProxyServer() {
        String scheme = isSocks() ? "socks5" : "http";
        return scheme + "://" + host + ":" + port;
    }

    /** Split the {@code |}-separated bypass list into individual patterns. */
    public List<String> bypassPatterns() {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(nonProxyHosts)) {
            return out;
        }
        for (String part : nonProxyHosts.split("\\|")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
