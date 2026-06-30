package vip.mate.tool.mcp.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Opt-in list of MCP servers that receive the calling user's identity.
 *
 * <p>When a server is listed here, every tool call routed to it is wrapped by
 * {@link IdentityForwardingToolCallback}, which injects the authenticated
 * username into the call arguments under {@link #IDENTITY_ARG}. The STDIO MCP
 * server then forwards that to its downstream REST backend (on-behalf-of),
 * typically alongside a static backend API key it holds itself.
 *
 * <p><b>Why opt-in, and per server.</b> A single STDIO subprocess is shared by
 * every user (the client pool is keyed by server id), so identity can only
 * travel <em>in-band per call</em> — never via the process environment, which is
 * fixed at spawn. And injecting the username into <em>every</em> MCP server's
 * calls would leak it to any third-party server an operator adds. So forwarding
 * is off by default and enabled per trusted server.
 *
 * <p>Configuration ({@code application.yml}):
 * <pre>
 * mateclaw:
 *   mcp:
 *     identity-forward:
 *       servers:
 *         - my-internal-api        # MCP server name (as configured in mate_mcp_server)
 *         - 1000000042             # or the numeric server id
 * </pre>
 *
 * <p><b>Trust model.</b> The username is plaintext and injected by trusted Java
 * (not the LLM — any LLM-supplied value of the same key is overwritten). This
 * fits a REST backend on a trusted network that authenticates the MCP service
 * by API key and treats the forwarded user as on-behalf-of. For stronger
 * isolation, mint a short-lived signed token instead of forwarding the raw
 * username (future work; see the design issue).
 *
 * @author MateClaw Team
 */
@Component
@ConfigurationProperties(prefix = "mateclaw.mcp.identity-forward")
public class McpIdentityForwardProperties {

    /**
     * Reserved tool-argument key carrying the authenticated username. Chosen to
     * be collision-unlikely with real tool parameters; the MCP server reads and
     * strips it. Kept in sync with the Python server contract.
     */
    public static final String IDENTITY_ARG = "__mateclaw_user__";

    /** Server names (as in mate_mcp_server) or numeric ids that opt in. */
    private Set<String> servers = Collections.emptySet();

    public Set<String> getServers() {
        return servers;
    }

    public void setServers(Set<String> servers) {
        this.servers = servers != null ? new LinkedHashSet<>(servers) : Collections.emptySet();
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
}
