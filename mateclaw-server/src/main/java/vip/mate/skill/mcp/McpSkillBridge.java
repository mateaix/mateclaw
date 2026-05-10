package vip.mate.skill.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;
import vip.mate.tool.mcp.service.McpServerService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFC-090 §3.2 / §5.7 / §10.2 Q2 — MCP server → virtual skill bridge.
 *
 * <p>MCP servers and skills are the same thing from the user's
 * perspective: capability supply for digital employees. The protocol
 * ({@code mate_mcp_server}) is implementation detail. This bridge
 * makes that consistent: every enabled MCP server becomes a virtual
 * {@link SkillEntity} + {@link ResolvedSkill} and shows up on the
 * Skills page exactly like a built-in or uploaded skill.
 *
 * <p>Why "virtual" not "persisted":
 * <ul>
 *   <li>Single source of truth — MCP server definition lives in
 *       {@code mate_mcp_server}, not duplicated to {@code mate_skill}.
 *       Avoids the double-write drift §14.6 warns about.</li>
 *   <li>Discovered tools change with the upstream server, not at our
 *       cadence. A virtual skill rebuilds on every list call so the
 *       tool count stays fresh.</li>
 *   <li>Settings ▸ MCP Connections remains the only place to edit
 *       transport / command / args / env. Skills page is read-only
 *       for MCP entries (View opens drawer; "Configure connection"
 *       links back to the MCP page).</li>
 * </ul>
 *
 * <p>ID namespace: virtual skill ids use a high sentinel
 * {@link #VIRTUAL_ID_BASE} + mcpServerId so they can never collide
 * with real {@code mate_skill.id} values (Snowflake longs are bounded
 * well below this base). Negative numbers were considered but several
 * existing endpoints {@code abs()} the id for path constraints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpSkillBridge {

    /**
     * High sentinel for virtual id space. Snowflake ids fit in 63 bits
     * but in practice never approach this magnitude, so anything
     * {@code >= VIRTUAL_ID_BASE} is unambiguously a bridged MCP skill.
     */
    public static final long VIRTUAL_ID_BASE = 9_000_000_000_000_000_000L;

    private final McpServerService mcpServerService;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;

    /**
     * @return true iff the given id falls inside the virtual MCP skill
     *     range. Cheap O(1) check, callers use it to route lookups
     *     between the real DB and this bridge.
     */
    public static boolean isVirtualMcpSkillId(Long id) {
        return id != null && id >= VIRTUAL_ID_BASE;
    }

    /** Inverse mapping: extract the original MCP server id. */
    public static Long extractMcpServerId(Long virtualId) {
        if (!isVirtualMcpSkillId(virtualId)) return null;
        return virtualId - VIRTUAL_ID_BASE;
    }

    public static long virtualIdFor(McpServerEntity server) {
        return VIRTUAL_ID_BASE + server.getId();
    }

    /**
     * Snapshot every enabled MCP server as a virtual {@link SkillEntity}.
     * Used by the Skills list endpoint; rows are non-persistent and
     * regenerated on each call.
     */
    public List<SkillEntity> listMcpDerivedSkillEntities() {
        return listEnabledServers().stream().map(this::serverToEntity).toList();
    }

    /**
     * Snapshot every enabled MCP server as a virtual {@link ResolvedSkill}
     * with synthesized manifest, ready to be merged into the runtime
     * status feed. Status reflects connection health: OK → READY default
     * feature; ERROR / disconnected → SETUP_NEEDED with a diagnostic
     * missing-dependency entry.
     */
    public List<ResolvedSkill> listMcpDerivedResolvedSkills() {
        return listEnabledServers().stream().map(this::serverToResolved).toList();
    }

    /**
     * Lookup a single virtual ResolvedSkill by virtual id; null when
     * the id is out of range or the server has been removed.
     */
    public ResolvedSkill findResolvedById(Long virtualId) {
        Long serverId = extractMcpServerId(virtualId);
        if (serverId == null) return null;
        try {
            McpServerEntity server = mcpServerService.getById(serverId);
            return server != null ? serverToResolved(server) : null;
        } catch (Exception e) {
            log.debug("MCP bridge lookup failed for virtual id {}: {}", virtualId, e.getMessage());
            return null;
        }
    }

    private List<McpServerEntity> listEnabledServers() {
        try {
            return mcpServerService.listEnabled();
        } catch (Exception e) {
            log.warn("MCP bridge could not list enabled servers: {}", e.getMessage());
            return List.of();
        }
    }

    private SkillEntity serverToEntity(McpServerEntity server) {
        SkillEntity s = new SkillEntity();
        s.setId(virtualIdFor(server));
        s.setName(slugify(server.getName()));
        s.setNameEn(displayName(server));
        s.setNameZh(displayName(server));
        s.setDescription(buildDescription(server));
        s.setSkillType("mcp");
        s.setIcon(iconFor(server));
        s.setVersion("1.0.0");
        s.setAuthor("mcp-bridge");
        s.setEnabled(Boolean.TRUE.equals(server.getEnabled()));
        s.setBuiltin(false);
        s.setTags("mcp");
        s.setSecurityScanStatus("PASSED"); // MCP servers don't go through SkillSecurityService
        s.setConfigJson(buildConfigJson(server));
        s.setManifestJson(serializeManifest(buildManifestFrom(server, readToolRawNames(server))));
        return s;
    }

    private ResolvedSkill serverToResolved(McpServerEntity server) {
        List<String> rawNames = readToolRawNames(server);
        Map<String, String> toolDisplayNames = new LinkedHashMap<>();
        for (String raw : rawNames) {
            String prefixed = McpToolNameResolver.prefixedName(server.getId(), raw);
            toolDisplayNames.put(prefixed, prefixed + " (" + raw + ")");
        }
        SkillManifest manifest = buildManifestFrom(server, rawNames);
        boolean connected = "connected".equalsIgnoreCase(nullSafe(server.getLastStatus()));
        boolean errored = "error".equalsIgnoreCase(nullSafe(server.getLastStatus()))
                || (server.getLastError() != null && !server.getLastError().isBlank());

        Map<String, String> featureStatuses = new LinkedHashMap<>();
        featureStatuses.put("default", connected ? "READY" : (errored ? "SETUP_NEEDED" : "SETUP_NEEDED"));
        java.util.Set<String> active = new LinkedHashSet<>();
        if (connected) active.add("default");

        List<String> missing = new ArrayList<>();
        if (!connected) {
            missing.add("mcp:" + server.getName() + " (status: "
                    + nullSafe(server.getLastStatus()) + ")");
        }

        return ResolvedSkill.builder()
                .id(virtualIdFor(server))
                .name(slugify(server.getName()))
                .description(buildDescription(server))
                .content("") // no SKILL.md
                .source("mcp")
                .skillDir(null)
                .configuredSkillDir(null)
                .runtimeAvailable(connected)
                .resolutionError(connected ? null : nullSafe(server.getLastError()))
                .references(Map.of())
                .scripts(Map.of())
                .enabled(Boolean.TRUE.equals(server.getEnabled()))
                .icon(iconFor(server))
                .builtin(false)
                .securityBlocked(false)
                .securitySummary("MCP-derived skill (bypasses SkillSecurityService)")
                .dependencyReady(connected)
                .missingDependencies(missing)
                .dependencySummary(connected
                        ? "MCP server '" + server.getName() + "' connected"
                        : "MCP server '" + server.getName() + "' not connected")
                .manifest(manifest)
                .featureStatuses(featureStatuses)
                .activeFeatures(active)
                .toolDisplayNames(toolDisplayNames)
                .build();
    }

    /**
     * Auto-generate the minimal manifest from the MCP server's most-recent
     * tool snapshot. The tool list is sourced in priority order:
     * <ol>
     *   <li>{@code mate_mcp_server.tools_cache_json} — present whenever the
     *       server has connected at least once. Lets the picker stay
     *       populated through brief disconnects.</li>
     *   <li>The runtime in-memory cache (current connection's
     *       {@code listTools()} result).</li>
     * </ol>
     *
     * <p>Tool names emitted into {@code manifest.allowedTools} go through
     * {@link McpToolNameResolver#prefixedName(long, String)} so they match
     * the runtime callback names registered by
     * {@link McpClientManager#getAllToolCallbacks()}. Without this, a
     * resolved skill's effective allowlist would carry raw names that
     * don't appear in any agent's callbacks at chat time, and the LLM
     * would see no MCP tools even though the bindings were saved.
     */
    private SkillManifest buildManifestFrom(McpServerEntity server, List<String> rawNames) {
        List<String> toolNames = new ArrayList<>(rawNames.size());
        for (String raw : rawNames) {
            toolNames.add(McpToolNameResolver.prefixedName(server.getId(), raw));
        }

        SkillManifest.FeatureDef defaultFeature = SkillManifest.FeatureDef.builder()
                .id("default")
                .label(displayName(server))
                .requires(List.of("mcp:" + server.getName()))
                .platforms(List.of())
                .tools(toolNames)
                .build();

        SkillManifest.RequirementDef mcpRequirement = SkillManifest.RequirementDef.builder()
                .key("mcp:" + server.getName())
                .type("mcp")
                .check(server.getName())
                .description("MCP server '" + server.getName() + "' must be connected. Configure in Settings ▸ MCP Connections.")
                .build();

        return SkillManifest.builder()
                .id(slugify(server.getName()))
                .name(slugify(server.getName()))
                .description(buildDescription(server))
                .icon(iconFor(server))
                .version("1.0.0")
                .author("mcp-bridge")
                .type("mcp")
                .category(categoryFor(server))
                .allowedTools(toolNames)
                .requires(List.of(mcpRequirement))
                .features(List.of(defaultFeature))
                .selfEvolution(SkillManifest.SelfEvolution.builder()
                        // MCP-derived skills don't write LESSONS.md — the
                        // upstream protocol layer is the canonical source.
                        .lessonsEnabled(false)
                        .lessonsMaxEntries(0)
                        .memoryWritesAllowed(true)
                        .build())
                .extras(Map.of("mcpServerId", server.getId()))
                .build();
    }

    /**
     * Resolve the raw tool name list for a server with cache-first / live-fallback
     * semantics. Returns an empty list (never null) so the manifest builder
     * stays simple.
     */
    private List<String> readToolRawNames(McpServerEntity server) {
        List<String> fromCache = parseCachedToolNames(server.getToolsCacheJson());
        if (!fromCache.isEmpty()) {
            return fromCache;
        }
        try {
            List<McpSchema.Tool> discovered = mcpClientManager.getServerTools(server.getId());
            List<String> names = new ArrayList<>(discovered.size());
            for (McpSchema.Tool t : discovered) {
                if (t == null) continue;
                String n = t.name();
                if (n != null && !n.isBlank()) names.add(n);
            }
            return names;
        } catch (Exception e) {
            log.debug("MCP bridge manifest build: getServerTools({}) failed: {}",
                    server.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Parse the {@code tools_cache_json} column written by
     * {@code McpServerService} after each successful connect. Returns an
     * empty list if the column is null/blank/malformed — the bridge is
     * required to keep working when the cache hasn't been populated yet
     * (e.g. first-ever connect just succeeded a moment ago).
     */
    private List<String> parseCachedToolNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(json);
            List<String> out = new ArrayList<>(arr.size());
            for (Object obj : arr) {
                if (!(obj instanceof cn.hutool.json.JSONObject jo)) continue;
                String name = jo.getStr("name");
                if (name != null && !name.isBlank()) out.add(name);
            }
            return out;
        } catch (Exception e) {
            log.debug("MCP bridge: failed to parse tools_cache_json: {}", e.getMessage());
            return List.of();
        }
    }

    private String slugify(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private String displayName(McpServerEntity server) {
        return server.getName() != null ? server.getName() : "mcp-" + server.getId();
    }

    private String buildDescription(McpServerEntity server) {
        if (server.getDescription() != null && !server.getDescription().isBlank()) {
            return server.getDescription();
        }
        int toolCount = server.getToolCount() == null ? 0 : server.getToolCount();
        return "MCP server " + server.getName()
                + (toolCount > 0 ? " · provides " + toolCount + " tools" : "")
                + ". Configure in Settings ▸ MCP Connections.";
    }

    private String iconFor(McpServerEntity server) {
        // Light heuristic — pick something recognisable for the most
        // popular MCP servers, fall back to the generic plug emoji.
        String n = nullSafe(server.getName()).toLowerCase(Locale.ROOT);
        if (n.contains("github")) return "🐙";
        if (n.contains("gitlab")) return "🦊";
        if (n.contains("filesystem") || n.contains("file")) return "📁";
        if (n.contains("postgres") || n.contains("mysql") || n.contains("sql") || n.contains("db")) return "🗄️";
        if (n.contains("slack")) return "💬";
        if (n.contains("notion")) return "📝";
        if (n.contains("memory")) return "🧠";
        if (n.contains("brave") || n.contains("search")) return "🔍";
        if (n.contains("puppeteer") || n.contains("browser")) return "🌐";
        return "🔌";
    }

    private String categoryFor(McpServerEntity server) {
        String n = nullSafe(server.getName()).toLowerCase(Locale.ROOT);
        if (n.contains("github") || n.contains("gitlab")) return "system";
        if (n.contains("file")) return "file";
        if (n.contains("sql") || n.contains("postgres") || n.contains("db")) return "data";
        if (n.contains("search") || n.contains("brave")) return "web";
        if (n.contains("slack") || n.contains("notion")) return "comm";
        return "system";
    }

    private String buildConfigJson(McpServerEntity server) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "mcpServerId", server.getId(),
                    "transport", nullSafe(server.getTransport()),
                    "source", Map.of("type", "mcp")
            ));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String serializeManifest(SkillManifest manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
