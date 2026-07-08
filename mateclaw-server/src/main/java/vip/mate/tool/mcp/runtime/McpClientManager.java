package vip.mate.tool.mcp.runtime;

import cn.hutool.json.JSONUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.tool.mcp.event.McpConnectionLostEvent;
import vip.mate.tool.mcp.event.McpServerChangedEvent;
import vip.mate.tool.mcp.model.McpServerEntity;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 系统级 MCP 客户端生命周期管理器
 * <p>
 * 职责：
 * - 管理所有 MCP server 的 McpSyncClient 实例
 * - 提供 connect/replace/remove/closeAll 操作
 * - 连接失败不阻塞其他 server
 * - replace 时先连新 client 再 swap 旧 client
 * - 使用 ConcurrentHashMap + per-server lock 保证线程安全
 * - close 时保证 stdio 子进程退出
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class McpClientManager {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** serverId -> active client */
    private final ConcurrentHashMap<Long, McpSyncClient> clients = new ConcurrentHashMap<>();

    /** serverId -> discovered tools metadata */
    private final ConcurrentHashMap<Long, List<McpSchema.Tool>> toolsCache = new ConcurrentHashMap<>();

    /**
     * serverId -> last successfully-built, prefix-wrapped tool callbacks.
     * Served as a fallback when a live {@code listTools()} momentarily fails
     * (e.g. the upstream server just restarted), so the agent keeps seeing the
     * MCP tools instead of dropping the whole server and falling back to
     * non-MCP tools. Refreshed on every successful collection.
     */
    private final ConcurrentHashMap<Long, List<ToolCallback>> lastGoodCallbacks = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher eventPublisher;

    private final McpIdentityForwardService identityForwardService;

    /** serverId -> server name, captured at build time for identity-forward opt-in matching. */
    private final ConcurrentHashMap<Long, String> serverNames = new ConcurrentHashMap<>();

    public McpClientManager(ApplicationEventPublisher eventPublisher,
                            McpIdentityForwardService identityForwardService) {
        this.eventPublisher = eventPublisher;
        this.identityForwardService = identityForwardService;
    }

    /** serverId -> connection result info */
    private final ConcurrentHashMap<Long, ConnectionResult> connectionResults = new ConcurrentHashMap<>();

    /** per-server lock to serialize connect/replace/remove */
    private final ConcurrentHashMap<Long, ReentrantLock> serverLocks = new ConcurrentHashMap<>();

    private ReentrantLock getLock(Long serverId) {
        return serverLocks.computeIfAbsent(serverId, k -> new ReentrantLock());
    }

    /**
     * 连接指定 MCP server
     *
     * @return ConnectionResult 连接结果
     */
    public ConnectionResult connect(McpServerEntity server) {
        ReentrantLock lock = getLock(server.getId());
        lock.lock();
        try {
            return doConnect(server);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 替换指定 MCP server 的 client（先连新，再 swap，再关旧）
     */
    public ConnectionResult replace(McpServerEntity server) {
        ReentrantLock lock = getLock(server.getId());
        lock.lock();
        try {
            // 1. Build and connect new client
            ConnectionResult result = doConnect(server);
            // doConnect already handles swap internally
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 断开并移除指定 server 的 client
     */
    public void remove(Long serverId) {
        ReentrantLock lock = getLock(serverId);
        lock.lock();
        try {
            McpSyncClient old = clients.remove(serverId);
            toolsCache.remove(serverId);
            connectionResults.remove(serverId);
            lastGoodCallbacks.remove(serverId);
            if (old != null) {
                closeClientSafely(serverId, old);
            }
        } finally {
            lock.unlock();
            // 不删除 serverLocks 中的 lock：ReentrantLock 很轻量，
            // 删除会导致其他线程 getLock() 创建新 lock 从而破坏互斥保护
        }
    }

    /**
     * 测试连接（不纳入 active clients 池）
     */
    public ConnectionResult testConnection(McpServerEntity server) {
        long start = System.currentTimeMillis();
        McpSyncClient testClient = null;
        try {
            testClient = buildClient(server, false);
            testClient.initialize();
            List<McpSchema.Tool> tools = testClient.listTools().tools();
            long latency = System.currentTimeMillis() - start;
            List<String> toolNames = tools.stream().map(McpSchema.Tool::name).toList();
            return ConnectionResult.success(tools.size(), latency, toolNames);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("MCP test connection failed for '{}': {}", server.getName(), e.getMessage());
            return ConnectionResult.failure(e.getMessage(), latency);
        } finally {
            if (testClient != null) {
                closeClientSafely(null, testClient);
            }
        }
    }

    /**
     * 获取所有 active clients
     */
    public Map<Long, McpSyncClient> getActiveClients() {
        return Collections.unmodifiableMap(clients);
    }

    /**
     * 获取指定 server 的 tools 列表
     */
    public List<McpSchema.Tool> getServerTools(Long serverId) {
        return toolsCache.getOrDefault(serverId, List.of());
    }

    /**
     * Collect ToolCallbacks from every active MCP client, with each callback's
     * name rewritten to a server-id-anchored prefix
     * (see {@link McpToolNameResolver}). Two guarantees:
     * <ul>
     *   <li>Two MCP servers can expose the same raw tool name without one
     *       silently overwriting the other in a name-keyed map downstream.</li>
     *   <li>If two raw names within the same server happen to hash to the
     *       same prefixed name, only the first survives —
     *       {@link McpHashCollisionDetector} flags the second so the picker
     *       can refuse to bind it.</li>
     * </ul>
     */
    public List<ToolCallback> getAllToolCallbacks() {
        List<ToolCallback> allCallbacks = new ArrayList<>();
        for (Map.Entry<Long, McpSyncClient> entry : clients.entrySet()) {
            long serverId = entry.getKey();
            try {
                SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(entry.getValue());
                ToolCallback[] cbs = provider.getToolCallbacks();
                if (cbs != null && cbs.length > 0) {
                    String serverName = serverNames.get(serverId);
                    McpIdentityForwardService idSvc =
                            identityForwardService.forwardsTo(serverId, serverName) ? identityForwardService : null;
                    String audience = idSvc != null ? identityForwardService.audienceFor(serverId, serverName) : null;
                    List<ToolCallback> wrapped = wrapServerCallbacks(serverId, cbs, idSvc, audience, serverName);
                    lastGoodCallbacks.put(serverId, wrapped);
                    allCallbacks.addAll(wrapped);
                    continue;
                }
                // Live call succeeded but returned nothing. This can be a
                // transient post-restart state while the SDK re-initializes;
                // keep serving the last good snapshot rather than dropping the
                // server. A server that legitimately has no tools simply has no
                // snapshot and contributes nothing — same as before.
                addSnapshot(allCallbacks, serverId);
            } catch (Exception e) {
                // A live listTools() failure usually means the upstream server
                // restarted and the held connection went stale. Instead of
                // dropping the whole server (which makes the agent fall back to
                // non-MCP tools), keep serving the last known-good callbacks and
                // ask the service layer to reconnect. For streamable_http the
                // stale callbacks self-heal on call via the SDK's lazy
                // re-initialization; for stdio/sse the async reconnect rebuilds
                // them and clears the agent cache.
                log.warn("MCP server {} listTools failed; serving cached snapshot and requesting reconnect: {}",
                        serverId, e.getMessage());
                eventPublisher.publishEvent(new McpConnectionLostEvent(serverId, "listTools-failed"));
                addSnapshot(allCallbacks, serverId);
            }
        }
        return allCallbacks;
    }

    /** Append the last known-good callbacks for {@code serverId}, if any. */
    private void addSnapshot(List<ToolCallback> out, long serverId) {
        List<ToolCallback> snapshot = lastGoodCallbacks.get(serverId);
        if (snapshot != null && !snapshot.isEmpty()) {
            log.debug("Serving {} cached MCP tool callbacks for server {} while it reconnects",
                    snapshot.size(), serverId);
            out.addAll(snapshot);
        }
    }

    /**
     * Apply per-server collision detection and wrap each surviving callback
     * with its prefixed name. Walks {@code cbs} and the matching decision
     * list in lockstep so that duplicate raw names are honored
     * one-decision-per-callback — a {@code Map<raw, decision>} would make
     * every duplicate look up the first (bindable) decision and silently
     * register two callbacks under the same prefixed name, breaking the
     * "runtime and picker share one decision" contract.
     *
     * <p>Package-private so unit tests can drive it without standing up a
     * real {@link McpSyncClient}.
     */
    static List<ToolCallback> wrapServerCallbacks(long serverId, ToolCallback[] cbs) {
        return wrapServerCallbacks(serverId, cbs, null, null, null);
    }

    /**
     * @param identitySvc when non-null, each callback is additionally wrapped in
     *        {@link IdentityForwardingToolCallback} (inside the prefix wrapper) so
     *        the caller's identity rides along with the call. Driven by
     *        {@link McpIdentityForwardService} opt-in per server; {@code null}
     *        means this server does not forward identity.
     * @param audience the token audience for this server (ignored in plaintext mode).
     * @param serverName human-readable MCP server name; forwarded into each
     *        {@link PrefixedNameToolCallback} so the tool description is tagged
     *        {@code [MCP server: <name>]}. May be {@code null} when unknown.
     */
    static List<ToolCallback> wrapServerCallbacks(long serverId, ToolCallback[] cbs,
                                                  McpIdentityForwardService identitySvc, String audience,
                                                  String serverName) {
        List<String> rawNames = new ArrayList<>(cbs.length);
        for (ToolCallback cb : cbs) {
            rawNames.add(cb.getToolDefinition() != null ? cb.getToolDefinition().name() : null);
        }
        List<McpHashCollisionDetector.Decision> decisions =
                McpHashCollisionDetector.classify(serverId, rawNames);

        // classify() drops blank/null raws; advance the decision pointer
        // only when the cb's raw is non-blank so the indices stay aligned.
        List<ToolCallback> out = new ArrayList<>(cbs.length);
        int dIdx = 0;
        for (ToolCallback cb : cbs) {
            String raw = cb.getToolDefinition() != null ? cb.getToolDefinition().name() : null;
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (dIdx >= decisions.size()) {
                break;
            }
            McpHashCollisionDetector.Decision d = decisions.get(dIdx++);
            if (!d.bindable()) {
                log.error("Skipping MCP tool callback on server {} (raw='{}', prefixed='{}'): {}",
                        serverId, raw, d.prefixedName(), d.unavailableReason());
                continue;
            }
            ToolCallback inner = identitySvc != null
                    ? new IdentityForwardingToolCallback(cb, identitySvc, audience)
                    : cb;
            out.add(new PrefixedNameToolCallback(d.prefixedName(), inner, serverName));
        }
        return out;
    }

    /**
     * 获取连接结果
     */
    public ConnectionResult getConnectionResult(Long serverId) {
        return connectionResults.get(serverId);
    }

    /**
     * 获取 active server 数量
     */
    public int getActiveCount() {
        return clients.size();
    }

    /**
     * 关闭所有 clients
     */
    @PreDestroy
    public void closeAll() {
        log.info("Closing all MCP clients ({} active)", clients.size());
        for (Map.Entry<Long, McpSyncClient> entry : clients.entrySet()) {
            closeClientSafely(entry.getKey(), entry.getValue());
        }
        clients.clear();
        toolsCache.clear();
        connectionResults.clear();
        lastGoodCallbacks.clear();
        // 不清除 serverLocks：closeAll 后 server 可能被重新 connect，
        // 保留 lock 对象确保后续操作仍有互斥保护
    }

    // ==================== Internal ====================

    private ConnectionResult doConnect(McpServerEntity server) {
        long start = System.currentTimeMillis();
        McpSyncClient newClient = null;
        try {
            newClient = buildClient(server, true);
            newClient.initialize();

            // Discover tools
            List<McpSchema.Tool> tools = newClient.listTools().tools();

            // Swap old client — 成功后才放入 clients
            McpSyncClient old = clients.put(server.getId(), newClient);
            toolsCache.put(server.getId(), tools);
            newClient = null; // 已交给 clients 管理，不在 finally 中关闭

            if (old != null) {
                closeClientSafely(server.getId(), old);
            }

            long latency = System.currentTimeMillis() - start;
            List<String> toolNames = tools.stream().map(McpSchema.Tool::name).toList();
            ConnectionResult result = ConnectionResult.success(tools.size(), latency, toolNames);
            connectionResults.put(server.getId(), result);

            log.info("MCP server '{}' connected successfully, {} tools discovered in {}ms",
                    server.getName(), tools.size(), latency);
            return result;

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ConnectionResult result = ConnectionResult.failure(errorMsg, latency);
            connectionResults.put(server.getId(), result);

            log.warn("MCP server '{}' connection failed ({}ms): {}", server.getName(), latency, errorMsg);
            return result;
        } finally {
            // 如果 newClient 非 null，说明 initialize/listTools 过程中失败，
            // client 未入池但可能已启动子进程（stdio）或打开连接，必须关闭
            if (newClient != null) {
                closeClientSafely(server.getId(), newClient);
            }
        }
    }

    /**
     * Build a sync MCP client for {@code server}.
     *
     * @param managed {@code true} for long-lived clients placed in the active
     *                pool (connect/replace), {@code false} for throwaway clients
     *                (testConnection). Only managed clients arm the runtime
     *                self-healing hooks — a server-pushed {@code tools/list_changed}
     *                notification refreshes agent graphs, and a stdio subprocess
     *                death requests a reconnect. A throwaway test client must stay
     *                side-effect free.
     */
    private McpSyncClient buildClient(McpServerEntity server, boolean managed) {
        if (server.getId() != null && server.getName() != null) {
            // Remember the name so identity-forward opt-in can be matched by name
            // (not just numeric id) when callbacks are wrapped.
            serverNames.put(server.getId(), server.getName());
        }
        McpClientTransport transport = switch (server.getTransport()) {
            case "stdio" -> buildStdioTransport(server, managed);
            case "sse" -> buildSseTransport(server);
            case "streamable_http" -> buildStreamableHttpTransport(server);
            default -> throw new IllegalArgumentException("Unsupported transport: " + server.getTransport());
        };

        // requestTimeout 控制每次 MCP 请求（initialize / listTools / callTool）的超时，
        // 语义上对应"读取超时"；connectTimeout 已在各 transport builder 中单独设置。
        Duration requestTimeout = Duration.ofSeconds(
                server.getReadTimeoutSeconds() != null ? server.getReadTimeoutSeconds() : 60);

        var spec = McpClient.sync(transport).requestTimeout(requestTimeout);
        if (managed) {
            // Server-pushed tool-list changes (tools/list_changed) refresh the
            // agent graphs without any polling — the SDK invokes this consumer
            // on the client's inbound notification thread.
            Long serverId = server.getId();
            spec.toolsChangeConsumer(tools ->
                    eventPublisher.publishEvent(new McpServerChangedEvent("mcp-tools-changed:" + serverId)));
        }
        return spec.build();
    }

    private StdioClientTransport buildStdioTransport(McpServerEntity server, boolean managed) {
        String command = normalizeStdioCommand(server.getCommand());
        ServerParameters.Builder builder = ServerParameters.builder(command);

        // Args
        if (server.getArgsJson() != null && !server.getArgsJson().isBlank()) {
            List<String> args = JSONUtil.toList(server.getArgsJson(), String.class);
            args = args.stream().map(McpClientManager::expandEnvVars).toList();
            builder.args(args);
        }

        // Env
        if (server.getEnvJson() != null && !server.getEnvJson().isBlank()) {
            Map<String, String> env = JSONUtil.toBean(server.getEnvJson(),
                    new cn.hutool.core.lang.TypeReference<Map<String, String>>() {}, false);
            Map<String, String> expandedEnv = new HashMap<>();
            for (var e : env.entrySet()) {
                expandedEnv.put(e.getKey(), expandEnvVars(e.getValue()));
            }
            builder.env(expandedEnv);
        }

        CwdAwareStdioClientTransport transport = new CwdAwareStdioClientTransport(
                builder.build(),
                McpJsonMapper.createDefault(),
                expandEnvVars(server.getCwd()));
        transport.setStdErrorHandler(line -> log.info("MCP stdio stderr [{}]: {}", server.getName(), line));
        if (managed) {
            // stdio is the one transport the MCP SDK cannot self-heal: a dead
            // subprocess is only recoverable by respawning it, which lazy
            // re-initialization never does. Request a reconnect on unexpected exit.
            Long serverId = server.getId();
            transport.setOnUnexpectedExit(() ->
                    eventPublisher.publishEvent(new McpConnectionLostEvent(serverId, "stdio-process-exited")));
        }
        return transport;
    }

    private String normalizeStdioCommand(String command) {
        if (command == null || command.isBlank() || !IS_WINDOWS) {
            return command;
        }

        String normalized = command.trim();
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains(".")) {
            return normalized;
        }

        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "npx" -> "npx.cmd";
            case "npm" -> "npm.cmd";
            case "pnpm" -> "pnpm.cmd";
            case "yarn" -> "yarn.cmd";
            case "bunx" -> "bunx.cmd";
            default -> normalized;
        };
    }

    private HttpClientSseClientTransport buildSseTransport(McpServerEntity server) {
        Duration connectTimeout = Duration.ofSeconds(
                server.getConnectTimeoutSeconds() != null ? server.getConnectTimeoutSeconds() : 30);

        HttpEndpointConfig endpointConfig = splitHttpUrl(server.getUrl(), "/sse");
        var builder = HttpClientSseClientTransport.builder(endpointConfig.baseUrl())
                .sseEndpoint(endpointConfig.endpoint())
                .connectTimeout(connectTimeout);

        // Add headers via request customizer
        Map<String, String> headers = parseHeaders(server);
        if (!headers.isEmpty()) {
            builder.customizeRequest(reqBuilder -> {
                for (var entry : headers.entrySet()) {
                    reqBuilder.header(entry.getKey(), entry.getValue());
                }
            });
        }

        return builder.build();
    }

    private HttpClientStreamableHttpTransport buildStreamableHttpTransport(McpServerEntity server) {
        Duration connectTimeout = Duration.ofSeconds(
                server.getConnectTimeoutSeconds() != null ? server.getConnectTimeoutSeconds() : 30);

        HttpEndpointConfig endpointConfig = splitHttpUrl(server.getUrl(), "/mcp");
        var builder = HttpClientStreamableHttpTransport.builder(endpointConfig.baseUrl())
                .endpoint(endpointConfig.endpoint())
                .connectTimeout(connectTimeout);

        // Add headers via request customizer
        Map<String, String> headers = parseHeaders(server);
        if (!headers.isEmpty()) {
            builder.customizeRequest(reqBuilder -> {
                for (var entry : headers.entrySet()) {
                    reqBuilder.header(entry.getKey(), entry.getValue());
                }
            });
        }

        return builder.build();
    }

    /**
     * Splits a full HTTP MCP URL into a {@code scheme://authority} base and a
     * {@code path[?query]} endpoint suffix. The underlying SDK builders take
     * the two halves separately and resolve them via {@link URI#resolve(URI)},
     * which replaces the base URL's path with the endpoint when the endpoint
     * starts with {@code /}. Passing a full URL as the base would therefore
     * silently route every request to the SDK's default endpoint
     * (e.g. {@code /mcp}) and drop any user-configured path or query string.
     *
     * @param url             the user-configured full URL
     * @param defaultEndpoint endpoint to use when the URL has no path
     */
    static HttpEndpointConfig splitHttpUrl(String url, String defaultEndpoint) {
        String trimmed = url != null ? url.trim() : "";
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("MCP server URL must not be empty");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid MCP server URL: " + url, e);
        }
        if (uri.getScheme() == null || uri.getRawAuthority() == null) {
            throw new IllegalArgumentException("MCP server URL must include scheme and host: " + url);
        }
        String path = uri.getRawPath();
        String endpoint = (path == null || path.isEmpty() || "/".equals(path)) ? defaultEndpoint : path;
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            endpoint += "?" + query;
        }
        String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
        return new HttpEndpointConfig(baseUrl, endpoint);
    }

    record HttpEndpointConfig(String baseUrl, String endpoint) {
    }

    private Map<String, String> parseHeaders(McpServerEntity server) {
        if (server.getHeadersJson() != null && !server.getHeadersJson().isBlank()) {
            Map<String, String> headers = JSONUtil.toBean(server.getHeadersJson(),
                    new cn.hutool.core.lang.TypeReference<Map<String, String>>() {}, false);
            // Expand env vars in header values
            Map<String, String> expanded = new HashMap<>();
            for (var e : headers.entrySet()) {
                expanded.put(e.getKey(), expandEnvVars(e.getValue()));
            }
            return expanded;
        }
        return Map.of();
    }

    private void closeClientSafely(Long serverId, McpSyncClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Error closing MCP client{}: {}",
                    serverId != null ? " (server " + serverId + ")" : "",
                    e.getMessage());
        }
    }

    /**
     * 展开系统属性和环境变量引用，如 ${user.home}、${ENV_VAR} 或 $ENV_VAR
     * <p>
     * 先处理 ${VAR}（精确匹配，JVM 系统属性优先于环境变量，
     * 这样 ${user.home} 等跨平台占位符在 Windows 上也能解析），
     * 再用正则处理 $VAR（word boundary），避免 $PATH 误替换 $PATH_HOME 的问题。
     */
    private static String expandEnvVars(String value) {
        if (value == null || !value.contains("$")) {
            return value;
        }
        String result = value;
        // Phase 1a: ${VAR} 优先匹配 JVM system property（如 ${user.home}、${java.io.tmpdir}）
        for (String key : System.getProperties().stringPropertyNames()) {
            result = result.replace("${" + key + "}", System.getProperty(key));
        }
        // Phase 1b: ${VAR} 回退匹配 OS 环境变量
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            result = result.replace("${" + env.getKey() + "}", env.getValue());
        }
        // Phase 2: 正则匹配 $VAR 模式（要求 VAR 后面不跟字母/数字/下划线）
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            // \Q...\E 转义 key 中可能的特殊字符，(?![A-Za-z0-9_]) 确保 word boundary
            result = result.replaceAll(
                    "\\$\\Q" + env.getKey() + "\\E(?![A-Za-z0-9_])",
                    java.util.regex.Matcher.quoteReplacement(env.getValue()));
        }
        return result;
    }

    /**
     * 连接结果
     */
    public record ConnectionResult(
            boolean success,
            String message,
            int toolCount,
            long latencyMs,
            List<String> discoveredTools,
            LocalDateTime timestamp
    ) {
        public static ConnectionResult success(int toolCount, long latencyMs, List<String> tools) {
            return new ConnectionResult(true, "Connected", toolCount, latencyMs, tools, LocalDateTime.now());
        }

        public static ConnectionResult failure(String message, long latencyMs) {
            return new ConnectionResult(false, message, 0, latencyMs, List.of(), LocalDateTime.now());
        }
    }
}
