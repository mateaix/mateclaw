package vip.mate.tool.mcp.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in configuration for forwarding the calling user's identity to MCP servers.
 *
 * <p>When a server is listed in {@link #servers}, every tool call routed to it is
 * wrapped by {@link IdentityForwardingToolCallback}, which injects the caller's
 * identity into the call arguments. Two trust models:
 *
 * <ul>
 *   <li><b>Plaintext</b> (default, {@code token.enabled=false}) — injects the
 *       username under {@link #USER_ARG}. The REST backend trusts whatever the
 *       MCP service forwards; only fits a trusted network where the backend
 *       authenticates the MCP service by API key.</li>
 *   <li><b>Signed token</b> ({@code token.enabled=true}) — injects a short-lived
 *       RS256 JWT under {@link #TOKEN_ARG} (sub=user, aud=server, short exp),
 *       minted by {@link McpIdentityForwardService} with MateClaw's private key.
 *       The REST backend <em>verifies</em> it with the public key, so it trusts
 *       the signature — not the MCP service, the Python script, or the transport.
 *       This is the cross-trust-boundary baseline.</li>
 * </ul>
 *
 * <p><b>Why opt-in, and per server.</b> A single STDIO subprocess is shared by
 * every user (the client pool is keyed by server id), so identity can only
 * travel <em>in-band per call</em> — never via the process environment, which is
 * fixed at spawn. And forwarding identity to <em>every</em> MCP server would leak
 * it to any third-party server an operator adds. So it is off by default and
 * enabled per trusted server.
 *
 * <p>Configuration ({@code application.yml}):
 * <pre>
 * mateclaw:
 *   mcp:
 *     identity-forward:
 *       servers:
 *         - my-internal-api          # MCP server name (mate_mcp_server) or numeric id
 *       token:
 *         enabled: true              # off =&gt; plaintext username (back-compat)
 *         issuer: mateclaw
 *         ttl-seconds: 60
 *         key-id: mateclaw-mcp-1
 *         private-key-pem: ${MCP_IDFWD_PRIVATE_KEY_PEM:}   # PKCS#8 PEM, RS256
 *         audiences:                 # optional name/id -&gt; aud; default aud = server name
 *           my-internal-api: my-internal-api
 * </pre>
 *
 * @author MateClaw Team
 */
@Component
@ConfigurationProperties(prefix = "mateclaw.mcp.identity-forward")
public class McpIdentityForwardProperties {

    /**
     * Reserved tool-argument key carrying the plaintext username (plaintext
     * trust model). Collision-unlikely with real tool parameters; the MCP
     * server reads and strips it.
     */
    public static final String USER_ARG = "__mateclaw_user__";

    /**
     * Reserved tool-argument key carrying the signed JWT (token trust model).
     * The MCP server forwards it as a bearer token; the REST backend verifies.
     */
    public static final String TOKEN_ARG = "__mateclaw_token__";

    /** Server names (as in mate_mcp_server) or numeric ids that opt in. */
    private Set<String> servers = Collections.emptySet();

    private Token token = new Token();

    public Set<String> getServers() {
        return servers;
    }

    public void setServers(Set<String> servers) {
        this.servers = servers != null ? new LinkedHashSet<>(servers) : Collections.emptySet();
    }

    public Token getToken() {
        return token;
    }

    public void setToken(Token token) {
        this.token = token != null ? token : new Token();
    }

    /**
     * @return {@code true} iff the configured set contains either the server's
     *         numeric id (as a string) or its name. Either argument may be
     *         {@code null}; the other is still checked.
     */
    public boolean forwardsTo(Long serverId, String serverName) {
        if (servers.isEmpty()) {
            return false;
        }
        if (serverId != null && servers.contains(String.valueOf(serverId))) {
            return true;
        }
        return serverName != null && servers.contains(serverName);
    }

    /**
     * Audience claim for a server's minted tokens: an explicit mapping (by name
     * or id) when configured, otherwise the server name (or id as string). Lets
     * the backend reject a token minted for a different server.
     *
     * @throws IllegalArgumentException when both {@code serverId} and
     *         {@code serverName} are {@code null} — there is no defensible
     *         audience for an unidentified server, and silently returning the
     *         literal string {@code "null"} would let two distinct null/null
     *         servers share one audience (defeating per-server isolation).
     *         Callers reachable from the production wrap path always have at
     *         least one of the two.
     */
    public String audienceFor(Long serverId, String serverName) {
        if (serverId == null && (serverName == null || serverName.isBlank())) {
            throw new IllegalArgumentException(
                    "audienceFor requires a serverId or serverName; both were null/blank");
        }
        Map<String, String> aud = token.getAudiences();
        if (serverName != null && aud.containsKey(serverName)) {
            return aud.get(serverName);
        }
        if (serverId != null && aud.containsKey(String.valueOf(serverId))) {
            return aud.get(String.valueOf(serverId));
        }
        return serverName != null ? serverName : String.valueOf(serverId);
    }

    /** Signed-token (JWT) settings for the token trust model. */
    public static class Token {
        private boolean enabled = false;
        private String issuer = "mateclaw";
        private long ttlSeconds = 60;
        private String keyId = "mateclaw-mcp-1";
        /** PKCS#8 PEM of the RS256 private key. Required when {@link #enabled}. */
        private String privateKeyPem = "";
        private Map<String, String> audiences = Collections.emptyMap();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public String getPrivateKeyPem() { return privateKeyPem; }
        public void setPrivateKeyPem(String privateKeyPem) { this.privateKeyPem = privateKeyPem; }
        public Map<String, String> getAudiences() { return audiences; }
        public void setAudiences(Map<String, String> audiences) {
            this.audiences = audiences != null ? audiences : Collections.emptyMap();
        }
    }
}
