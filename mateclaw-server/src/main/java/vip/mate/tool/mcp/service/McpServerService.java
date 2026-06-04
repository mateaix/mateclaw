package vip.mate.tool.mcp.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.repository.McpServerMapper;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.runtime.McpClientManager.ConnectionResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP Server 业务服务
 * <p>
 * 负责 CRUD、参数校验、触发 McpClientManager 连接/断开
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerService {

    private final McpServerMapper mcpServerMapper;
    private final McpClientManager mcpClientManager;

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-. ]{1,128}$");

    // ==================== CRUD ====================

    public List<McpServerEntity> listAll() {
        return mcpServerMapper.selectList(new LambdaQueryWrapper<McpServerEntity>()
                .orderByDesc(McpServerEntity::getEnabled)
                .orderByDesc(McpServerEntity::getCreateTime));
    }

    public List<McpServerEntity> listEnabled() {
        return mcpServerMapper.selectList(new LambdaQueryWrapper<McpServerEntity>()
                .eq(McpServerEntity::getEnabled, true)
                .orderByAsc(McpServerEntity::getName));
    }

    public McpServerEntity getById(Long id) {
        McpServerEntity entity = mcpServerMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.mcp.not_found", "MCP server 不存在: " + id);
        }
        return entity;
    }

    public McpServerEntity create(McpServerEntity entity) {
        validateServer(entity);
        entity.setBuiltin(false);
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (entity.getConnectTimeoutSeconds() == null) {
            entity.setConnectTimeoutSeconds(30);
        }
        if (entity.getReadTimeoutSeconds() == null) {
            entity.setReadTimeoutSeconds(60);
        }
        entity.setLastStatus("disconnected");
        entity.setToolCount(0);

        mcpServerMapper.insert(entity);
        log.info("MCP server created: name={}, transport={}, id={}", entity.getName(), entity.getTransport(), entity.getId());

        // Auto-connect if enabled
        if (Boolean.TRUE.equals(entity.getEnabled())) {
            connectSync(entity);
        }

        return entity;
    }

    public McpServerEntity update(Long id, McpServerEntity updates) {
        McpServerEntity existing = getById(id);

        // Merge fields (only update non-null fields)
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getTransport() != null) existing.setTransport(updates.getTransport());
        if (updates.getUrl() != null) existing.setUrl(updates.getUrl());
        if (updates.getCommand() != null) existing.setCommand(updates.getCommand());
        if (updates.getCwd() != null) existing.setCwd(updates.getCwd());
        if (updates.getConnectTimeoutSeconds() != null) existing.setConnectTimeoutSeconds(updates.getConnectTimeoutSeconds());
        if (updates.getReadTimeoutSeconds() != null) existing.setReadTimeoutSeconds(updates.getReadTimeoutSeconds());
        if (updates.getEnabled() != null) existing.setEnabled(updates.getEnabled());

        // JSON fields: only update if explicitly provided (non-null)
        // Empty string means clear; null means keep current value
        if (updates.getHeadersJson() != null) {
            existing.setHeadersJson(resolveSensitiveJsonUpdate(existing.getHeadersJson(), updates.getHeadersJson()));
        }
        if (updates.getArgsJson() != null) existing.setArgsJson(updates.getArgsJson());
        if (updates.getEnvJson() != null) {
            existing.setEnvJson(resolveSensitiveJsonUpdate(existing.getEnvJson(), updates.getEnvJson()));
        }

        validateServer(existing);
        mcpServerMapper.updateById(existing);

        log.info("MCP server updated: name={}, id={}", existing.getName(), id);

        // Reconnect if enabled, disconnect if disabled
        if (Boolean.TRUE.equals(existing.getEnabled())) {
            reconnectSync(existing);
        } else {
            mcpClientManager.remove(id);
            updateStatus(id, "disconnected", null, 0);
        }

        return existing;
    }

    public void delete(Long id) {
        McpServerEntity entity = getById(id);
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw new MateClawException("err.mcp.builtin_readonly", "内置 MCP server 不可删除");
        }

        // Disconnect first
        mcpClientManager.remove(id);
        mcpServerMapper.deleteById(id);
        log.info("MCP server deleted: name={}, id={}", entity.getName(), id);
    }

    public McpServerEntity toggle(Long id, boolean enabled) {
        McpServerEntity entity = getById(id);
        entity.setEnabled(enabled);
        mcpServerMapper.updateById(entity);

        if (enabled) {
            connectSync(entity);
        } else {
            mcpClientManager.remove(id);
            updateStatus(id, "disconnected", null, 0);
        }

        log.info("MCP server toggled: name={}, enabled={}", entity.getName(), enabled);
        return entity;
    }

    /**
     * Set the disclosure tier ({@code core} / {@code extension}) for the whole
     * server's tool group. No reconnect needed — tiering only affects how the
     * tools are advertised to the LLM.
     */
    public McpServerEntity setDisclosureTier(Long id, String tier) {
        McpServerEntity entity = getById(id);
        entity.setDisclosureTier(vip.mate.tool.disclosure.DisclosureTier.fromToken(tier).token());
        mcpServerMapper.updateById(entity);
        log.info("MCP server disclosure tier set: name={}, tier={}", entity.getName(), entity.getDisclosureTier());
        return entity;
    }

    // ==================== Runtime Operations ====================

    public ConnectionResult testConnection(McpServerEntity entity) {
        log.info("Testing MCP server connection: name={}", entity.getName());
        return mcpClientManager.testConnection(entity);
    }

    public ConnectionResult testConnectionById(Long id) {
        McpServerEntity entity = getById(id);
        return testConnection(entity);
    }

    /**
     * List the tools the given MCP server has surfaced to the runtime.
     *
     * <p>Reads from {@link McpClientManager#getServerTools(Long)} which
     * already caches the {@code listTools()} response on connect/refresh,
     * so this is a constant-time lookup with no network roundtrip. The
     * returned list is empty when the server is disconnected, in error
     * state, or simply has no tools — never throws on those paths so the
     * UI can render "no tools yet" rather than an error.
     *
     * <p>{@link #getById} is invoked first so a stale id (deleted server)
     * still returns a 404 from the controller layer rather than silently
     * "no tools".
     */
    public List<vip.mate.tool.mcp.model.McpToolDescriptor> listToolsByServer(Long id) {
        getById(id); // throws if the server is gone — preserves 404 semantics
        return mcpClientManager.getServerTools(id).stream()
                .map(t -> new vip.mate.tool.mcp.model.McpToolDescriptor(
                        t.name(),
                        t.description(),
                        t.inputSchema()))
                .toList();
    }

    /**
     * 刷新所有启用的 MCP server
     */
    public void refreshAll() {
        log.info("Refreshing all enabled MCP servers");
        mcpClientManager.closeAll();

        List<McpServerEntity> enabled = listEnabled();
        for (McpServerEntity server : enabled) {
            try {
                ConnectionResult result = mcpClientManager.connect(server);
                if (result.success()) {
                    onConnectSuccess(server.getId());
                } else {
                    updateStatus(server.getId(), "error", result.message(), 0);
                }
            } catch (Exception e) {
                log.warn("Failed to refresh MCP server '{}': {}", server.getName(), e.getMessage());
                updateStatus(server.getId(), "error", e.getMessage(), 0);
            }
        }
        log.info("MCP servers refresh complete: {} enabled, {} connected",
                enabled.size(), mcpClientManager.getActiveCount());
    }

    /**
     * 启动时初始化所有 enabled server（容错）
     */
    public void initEnabledServers() {
        List<McpServerEntity> enabled = listEnabled();
        if (enabled.isEmpty()) {
            log.info("No enabled MCP servers to initialize");
            return;
        }

        log.info("Initializing {} enabled MCP servers", enabled.size());
        for (McpServerEntity server : enabled) {
            try {
                ConnectionResult result = mcpClientManager.connect(server);
                if (result.success()) {
                    onConnectSuccess(server.getId());
                } else {
                    updateStatus(server.getId(), "error", result.message(), 0);
                }
            } catch (Exception e) {
                // 单个 server 失败不阻塞启动
                log.warn("Failed to initialize MCP server '{}': {}", server.getName(), e.getMessage());
                updateStatus(server.getId(), "error", e.getMessage(), 0);
            }
        }
        log.info("MCP servers initialization complete: {} connected / {} total",
                mcpClientManager.getActiveCount(), enabled.size());
    }

    // ==================== Sanitization ====================

    /**
     * 对返回给前端的 entity 做敏感信息脱敏
     */
    public McpServerEntity sanitize(McpServerEntity entity) {
        McpServerEntity copy = new McpServerEntity();
        copy.setId(entity.getId());
        copy.setName(entity.getName());
        copy.setDescription(entity.getDescription());
        copy.setTransport(entity.getTransport());
        copy.setUrl(entity.getUrl());
        copy.setCommand(entity.getCommand());
        copy.setCwd(entity.getCwd());
        copy.setEnabled(entity.getEnabled());
        copy.setConnectTimeoutSeconds(entity.getConnectTimeoutSeconds());
        copy.setReadTimeoutSeconds(entity.getReadTimeoutSeconds());
        copy.setLastStatus(entity.getLastStatus());
        copy.setLastError(entity.getLastError());
        copy.setLastConnectedTime(entity.getLastConnectedTime());
        copy.setToolCount(entity.getToolCount());
        copy.setBuiltin(entity.getBuiltin());
        // Disclosure tier is not sensitive and the UI relies on it to render the
        // per-server core/extension pill — dropping it made the field always null.
        copy.setDisclosureTier(entity.getDisclosureTier());
        copy.setCreateTime(entity.getCreateTime());
        copy.setUpdateTime(entity.getUpdateTime());

        // Mask sensitive JSON fields
        copy.setHeadersJson(maskJsonValues(entity.getHeadersJson()));
        copy.setArgsJson(entity.getArgsJson()); // args are not sensitive
        copy.setEnvJson(maskJsonValues(entity.getEnvJson()));

        return copy;
    }

    public List<McpServerEntity> sanitizeList(List<McpServerEntity> entities) {
        return entities.stream().map(this::sanitize).toList();
    }

    // ==================== Internal ====================

    private void connectSync(McpServerEntity server) {
        // 同步连接，阻塞调用线程。后续可改为 @Async + 线程池实现真异步。
        try {
            ConnectionResult result = mcpClientManager.connect(server);
            if (result.success()) {
                onConnectSuccess(server.getId());
            } else {
                mcpClientManager.remove(server.getId());
                updateStatus(server.getId(), "error", result.message(), 0);
            }
        } catch (Exception e) {
            log.warn("Failed to connect MCP server '{}': {}", server.getName(), e.getMessage());
            mcpClientManager.remove(server.getId());
            updateStatus(server.getId(), "error", e.getMessage(), 0);
        }
    }

    private void reconnectSync(McpServerEntity server) {
        try {
            ConnectionResult result = mcpClientManager.replace(server);
            if (result.success()) {
                onConnectSuccess(server.getId());
            } else {
                mcpClientManager.remove(server.getId());
                updateStatus(server.getId(), "error", result.message(), 0);
            }
        } catch (Exception e) {
            log.warn("Failed to reconnect MCP server '{}': {}", server.getName(), e.getMessage());
            mcpClientManager.remove(server.getId());
            updateStatus(server.getId(), "error", e.getMessage(), 0);
        }
    }

    /**
     * Common success path for every connect entry point: snapshot the
     * just-discovered tools into the {@code tools_cache_json} column in
     * the same DB roundtrip as the status update, so downstream code that
     * reads from the entity sees both pieces consistently.
     *
     * <p>Cache is only ever overwritten on success — failures preserve the
     * last successful snapshot, keeping the agent picker rendering
     * something useful while the upstream server is briefly down.
     */
    private void onConnectSuccess(Long serverId) {
        List<McpSchema.Tool> tools = mcpClientManager.getServerTools(serverId);
        String cacheJson = serializeToolsCache(tools);
        updateStatusWithCache(serverId, "connected", null, tools.size(), cacheJson);
    }

    private void updateStatus(Long id, String status, String error, int toolCount) {
        // Failure paths do NOT touch the tools cache — keep the last
        // successful snapshot so the picker stays populated.
        updateStatusWithCache(id, status, error, toolCount, null);
    }

    private void updateStatusWithCache(Long id, String status, String error, int toolCount, String cacheJson) {
        try {
            LambdaUpdateWrapper<McpServerEntity> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(McpServerEntity::getId, id);
            wrapper.set(McpServerEntity::getLastStatus, status);
            wrapper.set(McpServerEntity::getLastError, error);
            wrapper.set(McpServerEntity::getToolCount, toolCount);
            if ("connected".equals(status)) {
                wrapper.set(McpServerEntity::getLastConnectedTime, LocalDateTime.now());
            }
            if (cacheJson != null) {
                wrapper.set(McpServerEntity::getToolsCacheJson, cacheJson);
                wrapper.set(McpServerEntity::getToolsCacheUpdatedAt, LocalDateTime.now());
            }
            wrapper.set(McpServerEntity::getUpdateTime, LocalDateTime.now());
            mcpServerMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("Failed to update MCP server status: {}", e.getMessage());
        }
    }

    /**
     * Serialize the list returned by the upstream {@code listTools()} call
     * into a stable JSON shape: an array of {@code {name, description,
     * inputSchema}} entries. Schema is stored as the JSON text the upstream
     * surfaces (already a JSON-Schema object) so the picker can show it
     * verbatim without re-stringifying.
     */
    private String serializeToolsCache(List<McpSchema.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> rows = new ArrayList<>(tools.size());
        for (McpSchema.Tool t : tools) {
            if (t == null || t.name() == null || t.name().isBlank()) continue;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", t.name());
            row.put("description", t.description() != null ? t.description() : "");
            // inputSchema in the MCP record is a JsonSchema record; let the
            // JSON utility serialize it, falling back to "{}" if it can't.
            try {
                row.put("inputSchema", t.inputSchema() != null
                        ? JSONUtil.parse(JSONUtil.toJsonStr(t.inputSchema()))
                        : "{}");
            } catch (Exception e) {
                row.put("inputSchema", "{}");
            }
            rows.add(row);
        }
        return JSONUtil.toJsonStr(rows);
    }

    private void validateServer(McpServerEntity entity) {
        if (entity.getName() == null || entity.getName().isBlank()) {
            throw new MateClawException("err.mcp.name_required", "MCP server 名称不能为空");
        }
        if (entity.getTransport() == null || entity.getTransport().isBlank()) {
            throw new MateClawException("err.mcp.transport_required", "传输类型不能为空");
        }
        if (!List.of("stdio", "sse", "streamable_http").contains(entity.getTransport())) {
            throw new MateClawException("err.mcp.transport_unsupported", "不支持的传输类型: " + entity.getTransport());
        }
        if ("stdio".equals(entity.getTransport())) {
            if (entity.getCommand() == null || entity.getCommand().isBlank()) {
                throw new MateClawException("err.mcp.stdio_command_required", "stdio 类型必须指定 command");
            }
        } else {
            if (entity.getUrl() == null || entity.getUrl().isBlank()) {
                throw new MateClawException("err.mcp.http_url_required", "HTTP/SSE 类型必须指定 url");
            }
        }
        // Validate JSON fields — 不仅要求合法 JSON，还要求正确的结构类型
        if (entity.getHeadersJson() != null && !entity.getHeadersJson().isBlank()) {
            if (!JSONUtil.isTypeJSONObject(entity.getHeadersJson())) {
                throw new MateClawException("headers 必须是合法的 JSON 对象（如 {\"key\": \"value\"}）");
            }
        }
        if (entity.getArgsJson() != null && !entity.getArgsJson().isBlank()) {
            if (!JSONUtil.isTypeJSONArray(entity.getArgsJson())) {
                throw new MateClawException("args 必须是合法的 JSON 数组（如 [\"-y\", \"@mcp/server\"]）");
            }
        }
        if (entity.getEnvJson() != null && !entity.getEnvJson().isBlank()) {
            if (!JSONUtil.isTypeJSONObject(entity.getEnvJson())) {
                throw new MateClawException("env 必须是合法的 JSON 对象（如 {\"API_KEY\": \"xxx\"}）");
            }
        }
    }

    /**
     * 脱敏 JSON 中的值（保留 key，mask value）
     */
    static String maskJsonValues(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            if (!JSONUtil.isTypeJSON(json)) {
                return json;
            }
            Map<String, String> map = JSONUtil.toBean(json,
                    new cn.hutool.core.lang.TypeReference<Map<String, String>>() {}, false);
            Map<String, String> masked = new java.util.LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                masked.put(entry.getKey(), maskValue(entry.getValue()));
            }
            return JSONUtil.toJsonStr(masked);
        } catch (Exception e) {
            return "***";
        }
    }

    /**
     * 脱敏单个值：显示前 2-3 字符和后 4 字符，中间用 * 填充
     */
    static String maskValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int len = value.length();
        if (len <= 8) {
            return "*".repeat(len);
        }
        int prefixLen = (len > 2 && value.charAt(2) == '-') ? 3 : 2;
        String prefix = value.substring(0, prefixLen);
        String suffix = value.substring(len - 4);
        int maskedLen = Math.max(len - prefixLen - 4, 4);
        return prefix + "*".repeat(maskedLen) + suffix;
    }

    private String resolveSensitiveJsonUpdate(String currentValue, String incomingValue) {
        if (incomingValue == null) {
            return currentValue;
        }
        String maskedCurrent = maskJsonValues(currentValue);
        if (maskedCurrent != null && maskedCurrent.equals(incomingValue)) {
            return currentValue;
        }
        return incomingValue;
    }
}
