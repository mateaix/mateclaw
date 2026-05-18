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
 * <p>ID namespace: virtual ids encode a 2-bit type tag in the top two
 * bits of a {@code long}, leaving 62 bits to carry the underlying
 * mcpServerId:
 * <pre>
 *   bit 63 (sign) | bit 62 | bits 0..61
 *   --------------+--------+--------------------------------
 *         0       |   0    |  real persisted skill (Snowflake)
 *         1       |   0    |  virtual MCP-derived skill
 *         1       |   1    |  virtual ACP-derived skill
 * </pre>
 *
 * <p>The earlier {@code 9e18 + serverId} addition scheme broke once
 * Snowflake-issued mcpServerIds crossed ~{@code 2e18} (the sum then
 * overflowed signed long, wrapping to a negative number that no longer
 * satisfied {@code id >= 9e18} — every detail / lookup of a freshly
 * created MCP server 500'd with "技能不存在"). The bit-tagged layout
 * has no arithmetic and survives any 62-bit server id.
 *
 * <p>The constants are arranged so that {@code BASE + smallId} still
 * equals {@code BASE | smallId} for any {@code smallId < 2^62}, so
 * test fixtures that build virtual ids by addition keep working.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpSkillBridge {

    /**
     * Type tag for the MCP virtual id space: bit 63 set, bit 62 clear.
     * Equal to {@link Long#MIN_VALUE}; named for the historical
     * "base sentinel" idiom callers still use.
     */
    public static final long VIRTUAL_ID_BASE = Long.MIN_VALUE; // 0x8000000000000000L

    /** Selects the top-two type-tag bits. */
    private static final long TAG_MASK = 0xC000000000000000L;
    /** Selects the bottom 62 bits that carry the original server id. */
    private static final long ID_MASK = 0x3FFFFFFFFFFFFFFFL;

    private final McpServerService mcpServerService;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;

    /**
     * @return true iff the given id carries the MCP virtual-skill type
     *     tag (bit 63 set, bit 62 clear). Cheap O(1) bit-mask check.
     */
    public static boolean isVirtualMcpSkillId(Long id) {
        return id != null && (id & TAG_MASK) == VIRTUAL_ID_BASE;
    }

    /** Inverse mapping: extract the original MCP server id. */
    public static Long extractMcpServerId(Long virtualId) {
        if (!isVirtualMcpSkillId(virtualId)) return null;
        return virtualId & ID_MASK;
    }

    public static long virtualIdFor(McpServerEntity server) {
        long sid = server.getId();
        if ((sid & TAG_MASK) != 0L) {
            throw new IllegalStateException(
                    "MCP server id 0x" + Long.toHexString(sid)
                            + " uses the top two bits — would collide with the virtual id type tag");
        }
        return VIRTUAL_ID_BASE | sid;
    }

    /**
     * Snapshot every MCP server as a virtual {@link SkillEntity}. Used by
     * the Skills list endpoint; rows are non-persistent and regenerated on
     * each call. Disabled servers are included so a skill the user toggled
     * off still shows on the Skills page (as a disabled card) and can be
     * toggled back on — {@code enabled} mirrors the server's flag.
     */
    public List<SkillEntity> listMcpDerivedSkillEntities() {
        return listAllServers().stream().map(this::serverToEntity).toList();
    }

    /**
     * Snapshot every MCP server as a virtual {@link ResolvedSkill} with
     * synthesized manifest, ready to be merged into the runtime status
     * feed. Status reflects connection health: OK → READY default feature;
     * ERROR / disconnected → SETUP_NEEDED with a diagnostic missing-dependency
     * entry. Disabled servers are included for the admin status view; the
     * active-skill gate ({@code SkillRuntimeService.passesActiveGate}) keeps
     * them out of the agent runtime.
     */
    public List<ResolvedSkill> listMcpDerivedResolvedSkills() {
        return listAllServers().stream().map(this::serverToResolved).toList();
    }

    /**
     * Enable or disable the MCP server behind a virtual MCP skill.
     *
     * <p>A virtual MCP skill has no {@code mate_skill} row — its enabled
     * state is the underlying MCP server's {@code enabled} flag. Toggling
     * the skill therefore toggles the server, which also connects or
     * disconnects it. Returns the rebuilt virtual {@link SkillEntity}
     * reflecting the new state.
     */
    public SkillEntity toggleVirtualSkill(Long virtualId, boolean enabled) {
        Long serverId = extractMcpServerId(virtualId);
        if (serverId == null) {
            throw new IllegalArgumentException("Not a virtual MCP skill id: " + virtualId);
        }
        McpServerEntity updated = mcpServerService.toggle(serverId, enabled);
        return serverToEntity(updated);
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

    private List<McpServerEntity> listAllServers() {
        try {
            return mcpServerService.listAll();
        } catch (Exception e) {
            log.warn("MCP bridge could not list servers: {}", e.getMessage());
            return List.of();
        }
    }

    private SkillEntity serverToEntity(McpServerEntity server) {
        List<McpToolDescriptor> tools = readToolDescriptors(server);
        SkillEntity s = new SkillEntity();
        s.setId(virtualIdFor(server));
        s.setName(slugForServer(server));
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
        s.setSkillContent(buildSkillContent(server, tools));
        s.setConfigJson(buildConfigJson(server));
        s.setManifestJson(serializeManifest(buildManifestFrom(server, toRawNames(tools))));
        return s;
    }

    private ResolvedSkill serverToResolved(McpServerEntity server) {
        List<McpToolDescriptor> tools = readToolDescriptors(server);
        List<String> rawNames = toRawNames(tools);
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
                .name(slugForServer(server))
                .description(buildDescription(server))
                .content(buildSkillContent(server, tools))
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

        String slug = slugForServer(server);
        return SkillManifest.builder()
                .id(slug)
                .name(slug)
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
     * Resolve the tool list for a server with cache-first / live-fallback
     * semantics. Each entry carries the raw name and (when available) the
     * upstream description. Returns an empty list (never null) so callers
     * stay simple.
     */
    private List<McpToolDescriptor> readToolDescriptors(McpServerEntity server) {
        List<McpToolDescriptor> fromCache = parseCachedToolDescriptors(server.getToolsCacheJson());
        if (!fromCache.isEmpty()) {
            return fromCache;
        }
        try {
            List<McpSchema.Tool> discovered = mcpClientManager.getServerTools(server.getId());
            List<McpToolDescriptor> out = new ArrayList<>(discovered.size());
            for (McpSchema.Tool t : discovered) {
                if (t == null) continue;
                String n = t.name();
                if (n != null && !n.isBlank()) {
                    out.add(new McpToolDescriptor(n, t.description()));
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("MCP bridge manifest build: getServerTools({}) failed: {}",
                    server.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Parse the {@code tools_cache_json} column written by
     * {@code McpServerService} after each successful connect — an array of
     * {@code {name, description, inputSchema}} entries. Returns an empty
     * list if the column is null/blank/malformed — the bridge is required
     * to keep working when the cache hasn't been populated yet (e.g.
     * first-ever connect just succeeded a moment ago).
     */
    private List<McpToolDescriptor> parseCachedToolDescriptors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(json);
            List<McpToolDescriptor> out = new ArrayList<>(arr.size());
            for (Object obj : arr) {
                if (!(obj instanceof cn.hutool.json.JSONObject jo)) continue;
                String name = jo.getStr("name");
                if (name != null && !name.isBlank()) {
                    out.add(new McpToolDescriptor(name, jo.getStr("description")));
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("MCP bridge: failed to parse tools_cache_json: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<String> toRawNames(List<McpToolDescriptor> tools) {
        List<String> names = new ArrayList<>(tools.size());
        for (McpToolDescriptor t : tools) {
            names.add(t.name());
        }
        return names;
    }

    /**
     * Synthesize a SKILL.md body for an MCP-derived virtual skill.
     *
     * <p>Persisted and uploaded skills ship a hand-written SKILL.md that the
     * agent serves on demand through {@code readSkillFile}; it tells the
     * model what the skill is for and how to drive it. An MCP-derived skill
     * has no such file — the upstream server only exposes a tool list — so
     * without a synthesized body {@code readSkillFile} returns "content not
     * available" and the model has nothing beyond the one-line description
     * to reason about.
     *
     * <p>This builds an equivalent body from the live tool snapshot: a
     * one-line summary, the tool catalog with per-tool descriptions, and a
     * short usage note. Regenerated on every list call, so it tracks the
     * upstream tool set with no persistence step.
     */
    private String buildSkillContent(McpServerEntity server, List<McpToolDescriptor> tools) {
        String displayName = displayName(server);
        StringBuilder md = new StringBuilder();
        md.append("# ").append(displayName).append("\n\n");
        md.append(buildDescription(server)).append("\n\n");
        md.append("This capability is provided by the MCP server **").append(displayName).append("**");
        String transport = nullSafe(server.getTransport());
        if (!transport.isBlank()) {
            md.append(" (").append(transport).append(" transport)");
        }
        md.append(". Its tools are available to you as ordinary function calls — ")
                .append("invoke them directly by name; no shell or scripts are involved.\n\n");

        md.append("## Available Tools\n\n");
        if (tools.isEmpty()) {
            md.append("The tool list is not available yet. The MCP server may be ")
                    .append("disconnected or still starting up — check its status in ")
                    .append("Settings ▸ MCP Connections.\n");
            return md.toString();
        }
        md.append("This server exposes ").append(tools.size())
                .append(tools.size() == 1 ? " tool:\n\n" : " tools:\n\n");
        for (McpToolDescriptor t : tools) {
            md.append("- **").append(t.name()).append("**");
            String desc = oneLine(t.description());
            if (!desc.isBlank()) {
                md.append(" — ").append(desc);
            }
            md.append("\n");
        }
        md.append("\n## Usage Notes\n\n");
        md.append("- These tools appear in your tool list under `mcp_`-prefixed names; ")
                .append("pick whichever one matches the user's request.\n");
        md.append("- If a call fails with a connection error, the MCP server is likely ")
                .append("disconnected — it can be reconnected in Settings ▸ MCP Connections.\n");
        return md.toString();
    }

    /** Collapse whitespace and clamp a tool description to a prompt-friendly length. */
    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String collapsed = s.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 200 ? collapsed.substring(0, 200) + "…" : collapsed;
    }

    /** Minimal MCP tool projection: just what the manifest and SKILL.md body need. */
    private record McpToolDescriptor(String name, String description) {}

    private String slugify(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    /**
     * Stable slug for an MCP server. Falls back to {@code mcp-{id}} when
     * the source name has no ASCII letter/digit (e.g. pure CJK), because
     * the naive slugify would otherwise return a run of dashes — making
     * two differently-named all-CJK servers collide on the same display
     * key and breaking name-based skill lookup.
     */
    private String slugForServer(McpServerEntity server) {
        String slug = slugify(server.getName());
        return hasAsciiAlphaNumeric(slug) ? slug : "mcp-" + server.getId();
    }

    private static boolean hasAsciiAlphaNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        }
        return false;
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
