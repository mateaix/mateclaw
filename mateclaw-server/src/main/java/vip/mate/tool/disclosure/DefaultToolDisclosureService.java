package vip.mate.tool.disclosure;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpHashCollisionDetector;
import vip.mate.tool.mcp.service.McpServerService;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.service.ToolService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default tier resolver. Tier data is read from {@code mate_tool} and
 * {@code mate_mcp_server} and cached in a short-lived snapshot so the per-turn
 * {@link #split} does not hit the DB on every reasoning step. The PATCH
 * endpoints call {@link #invalidate()} after changing a tier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultToolDisclosureService implements ToolDisclosureService {

    private static final long CACHE_TTL_MS = 30_000L;

    /**
     * Meta-tools that must always stay core: hiding them would make progressive
     * disclosure unrecoverable (the model could neither search/call a deferred
     * tool nor load a skill).
     */
    private static final Set<String> ALWAYS_CORE = Set.of(
            "enable_tool", "load_skill", "tool_search", "tool_describe", "tool_call");

    /**
     * Code-level extension defaults for builtin tools that may not yet have a
     * {@code mate_tool} row when first resolved. A persisted
     * {@code disclosure_tier} always overrides these.
     */
    private static final Set<String> BUILTIN_EXTENSION_DEFAULTS = Set.of(
            "image_generate", "music_generate", "video_generate", "model3d_generate", "browser_use");

    private final ToolService toolService;
    private final McpServerService mcpServerService;
    private final ToolRegistry toolRegistry;
    private final ToolUsageRecencyTracker usageRecencyTracker;

    @Value("${mateclaw.tools.disclosure.mode:progressive}")
    private String disclosureMode;

    private volatile Snapshot snapshot;

    private boolean legacyMode() {
        return "legacy".equalsIgnoreCase(disclosureMode);
    }

    @Override
    public DisclosureTier resolveTier(ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null) {
            return DisclosureTier.CORE;
        }
        return resolveTierByName(callback.getToolDefinition().name());
    }

    @Override
    public DisclosureTier resolveTierByName(String toolName) {
        if (toolName == null || toolName.isBlank() || legacyMode()) {
            return DisclosureTier.CORE;
        }
        if (ALWAYS_CORE.contains(toolName)) {
            return DisclosureTier.CORE;
        }
        Snapshot snap = snapshot();
        DisclosureTier dbTier = snap.builtinTierByName.get(toolName);
        if (dbTier != null) {
            return dbTier;
        }
        if (BUILTIN_EXTENSION_DEFAULTS.contains(toolName)) {
            return DisclosureTier.EXTENSION;
        }
        Long serverId = snap.mcpToolToServerId.get(toolName);
        if (serverId != null) {
            // Move 5: MCP tools default to EXTENSION (on-demand exposure).
            // A server with 20 tools flooding the CORE tool list makes it
            // harder for the model to find the right builtin tool, and the
            // MCP schemas are typically the heaviest part of the prompt.
            // Users who want a server's tools visible by default can set
            // disclosure_tier = core on the mate_mcp_server row.
            return snap.serverTierById.getOrDefault(serverId, DisclosureTier.EXTENSION);
        }
        // Unknown source (ACP / dynamic-skill / plugin) — keep visible.
        return DisclosureTier.CORE;
    }

    @Override
    public ToolDisclosureSplit split(AgentToolSet baseSet, Set<String> enabledExtensions) {
        return split(baseSet, enabledExtensions, Set.of());
    }

    @Override
    public ToolDisclosureSplit split(AgentToolSet baseSet, Set<String> enabledExtensions,
                                     Set<String> autoDemoted) {
        List<ToolCallback> all = baseSet == null ? List.of() : baseSet.callbacks();
        if (legacyMode()) {
            return new ToolDisclosureSplit(all, List.of());
        }
        Set<String> enabled = enabledExtensions == null ? Set.of() : enabledExtensions;
        Set<String> demoted = autoDemoted == null ? Set.of() : autoDemoted;
        List<ToolCallback> active = new ArrayList<>(all.size());
        List<ToolCallback> extensionCatalog = new ArrayList<>();
        for (ToolCallback cb : all) {
            String name = cb.getToolDefinition().name();
            if (resolveTier(cb) == DisclosureTier.EXTENSION || demoted.contains(name)) {
                extensionCatalog.add(cb);
                if (enabled.contains(name)) {
                    active.add(cb);
                }
            } else {
                active.add(cb);
            }
        }
        return new ToolDisclosureSplit(active, extensionCatalog);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Protection set: only the {@link #ALWAYS_CORE} recovery/bridge tools.
     * An explicit {@code disclosure_tier = core} remains a preference, but it
     * cannot override the hard per-request schema ceiling. MCP tools default
     * to EXTENSION (Move 5) so they only enter the CORE list when an operator
     * explicitly sets {@code disclosure_tier = core} on the server — in that
     * case they are still demotable, since MCP schemas are typically the
     * heaviest part of the advertisement.
     */
    @Override
    public Set<String> computeAutoDemotions(AgentToolSet baseSet, Integer budgetTokens) {
        if (legacyMode() || baseSet == null || budgetTokens == null
                || budgetTokens <= 0 || budgetTokens == Integer.MAX_VALUE) {
            return Set.of();
        }
        List<ToolCallback> core = split(baseSet, Set.of()).activeCallbacks();
        int coreTokens = TokenEstimator.estimateToolsTokens(core);
        if (coreTokens <= budgetTokens) {
            return Set.of();
        }
        Snapshot snap = snapshot();
        List<ToolCallback> candidates = core.stream()
                .filter(cb -> isDemotable(cb.getToolDefinition().name(), snap))
                .sorted(demotionOrder())
                .toList();
        Set<String> demoted = new LinkedHashSet<>();
        int remainingTokens = coreTokens;
        for (ToolCallback cb : candidates) {
            if (remainingTokens <= budgetTokens) {
                break;
            }
            remainingTokens -= TokenEstimator.estimateToolsTokens(List.of(cb));
            demoted.add(cb.getToolDefinition().name());
        }
        if (!demoted.isEmpty()) {
            log.info("[ToolDisclosure] 工具 schema 估算 {} tokens 超出预算 {}——已将 {} 个最少使用的工具"
                            + "降级到渐进目录(tool_call 可当轮调用): {}",
                    coreTokens, budgetTokens, demoted.size(), demoted);
        }
        return demoted;
    }

    private boolean isDemotable(String toolName, Snapshot snap) {
        if (toolName == null || ALWAYS_CORE.contains(toolName)) {
            return false;
        }
        return true;
    }

    /** Never-used tools demote first, then least recently used; name-tiebreak keeps builds deterministic. */
    private Comparator<ToolCallback> demotionOrder() {
        return Comparator
                .<ToolCallback, Long>comparing(cb -> {
                    Long lastUsed = usageRecencyTracker == null
                            ? null : usageRecencyTracker.lastUsedAt(cb.getToolDefinition().name());
                    return lastUsed == null ? Long.MIN_VALUE : lastUsed;
                })
                .thenComparing(cb -> cb.getToolDefinition().name());
    }

    @Override
    public String renderExtensionCatalog(AgentToolSet baseSet, Integer maxInputTokens) {
        return renderExtensionCatalog(baseSet, maxInputTokens, Set.of());
    }

    @Override
    public String renderExtensionCatalog(AgentToolSet baseSet, Integer maxInputTokens,
                                         Set<String> autoDemoted) {
        if (legacyMode() || baseSet == null) {
            return "";
        }
        List<ToolCallback> extension = split(baseSet, Set.of(), autoDemoted).extensionCatalog();
        if (extension.isEmpty()) {
            return "";
        }
        int limit = catalogEntryLimit(maxInputTokens);
        Snapshot snap = snapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Extension Tools\n");
        sb.append("Full schemas are hidden until needed. If the exact name is known, call ");
        sb.append("`tool_call(toolName=\"<name>\", arguments={...})` to execute it in this same round. ");
        sb.append("Use `tool_search` to discover by capability and `tool_describe` only when arguments are unclear.\n\n");
        sb.append("| Tool | Source | Description |\n");
        sb.append("|------|--------|-------------|\n");
        int shown = 0;
        for (ToolCallback cb : extension) {
            if (shown >= limit) break;
            String name = cb.getToolDefinition().name();
            sb.append("| `").append(name).append("` | ")
                    .append(sourceLabel(name, snap)).append(" | ");
            String desc = cb.getToolDefinition().description();
            if (desc != null && !desc.isBlank()) {
                String d = desc.length() > 80 ? desc.substring(0, 80) + "..." : desc;
                sb.append(d.replace("|", "\\|").replace("\n", " "));
            }
            sb.append(" |\n");
            shown++;
        }
        if (extension.size() > shown) {
            sb.append("\nShowing ").append(shown).append(" of ").append(extension.size())
                    .append(" extension tools.\n");
        }
        return sb.toString();
    }

    @Override
    public void invalidate() {
        this.snapshot = null;
    }

    private String sourceLabel(String toolName, Snapshot snap) {
        Long serverId = snap.mcpToolToServerId.get(toolName);
        if (serverId != null) {
            String serverName = snap.serverNameById.get(serverId);
            return serverName != null && !serverName.isBlank() ? "mcp:" + serverName : "mcp";
        }
        return "builtin";
    }

    private static int catalogEntryLimit(Integer maxInputTokens) {
        if (maxInputTokens == null || maxInputTokens <= 0) return 20;
        if (maxInputTokens < 8_000) return 12;
        if (maxInputTokens < 32_000) return 25;
        return 40;
    }

    private Snapshot snapshot() {
        Snapshot snap = this.snapshot;
        if (snap != null && (System.currentTimeMillis() - snap.builtAtMillis) < CACHE_TTL_MS) {
            return snap;
        }
        Snapshot rebuilt = buildSnapshot();
        this.snapshot = rebuilt;
        return rebuilt;
    }

    private Snapshot buildSnapshot() {
        // resolveTier() queries by the runtime function name (cb.getToolDefinition().name()),
        // but mate_tool stores the Java class name (e.g. "ImageGenerateTool") and bean name
        // (e.g. "imageGenerateTool"). Bridge both onto the function name(s) via a built-in
        // alias index so a persisted tier actually reaches the runtime split. Do not call
        // ToolRegistry.getEnabledToolSet() here: it synchronously enumerates MCP providers.
        Map<String, DisclosureTier> builtinTierByName = new LinkedHashMap<>();
        Map<String, Set<String>> builtinFunctionIndex = Map.of();
        try {
            builtinFunctionIndex = toolRegistry.enabledToolBeanFunctionNameIndex();
        } catch (Exception e) {
            log.warn("ToolDisclosureService: built-in tier name bridge unavailable: {}", e.getMessage());
        }
        try {
            for (ToolEntity t : toolService.listTools()) {
                if (t.getName() == null || t.getDisclosureTier() == null || t.getDisclosureTier().isBlank()) {
                    continue;
                }
                DisclosureTier tier = DisclosureTier.fromToken(t.getDisclosureTier());
                // Key by the raw stored name too — harmless, and covers rows that already
                // store a function name.
                builtinTierByName.put(t.getName(), tier);
                Set<String> aliases = new LinkedHashSet<>();
                aliases.add(t.getName());
                if (t.getBeanName() != null && !t.getBeanName().isBlank()) {
                    aliases.add(t.getBeanName());
                }
                for (String alias : aliases) {
                    Set<String> functionNames = builtinFunctionIndex.get(alias);
                    if (functionNames == null) {
                        continue;
                    }
                    for (String functionName : functionNames) {
                        builtinTierByName.put(functionName, tier);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ToolDisclosureService: failed to read mate_tool tiers, defaulting builtin tools to core: {}",
                    e.getMessage());
        }

        Map<String, Long> mcpToolToServerId = new LinkedHashMap<>();
        try {
            for (McpServerEntity server : mcpServerService.listEnabled()) {
                if (server == null || server.getId() == null || server.getToolsCacheJson() == null
                        || server.getToolsCacheJson().isBlank()) {
                    continue;
                }
                for (String toolName : cachedMcpToolNames(server)) {
                    mcpToolToServerId.put(toolName, server.getId());
                }
            }
        } catch (Exception e) {
            log.warn("ToolDisclosureService: failed to map MCP tools to servers: {}", e.getMessage());
        }

        Map<Long, DisclosureTier> serverTierById = new LinkedHashMap<>();
        Map<Long, String> serverNameById = new LinkedHashMap<>();
        try {
            for (McpServerEntity s : mcpServerService.listAll()) {
                // Move 5: only record an explicit tier. Servers with null
                // disclosure_tier are intentionally absent from the map so
                // resolveTierByName's getOrDefault(serverId, EXTENSION)
                // applies the new on-demand default. Putting CORE here
                // (via fromToken(null) → CORE) would override the default.
                if (s.getDisclosureTier() != null && !s.getDisclosureTier().isBlank()) {
                    serverTierById.put(s.getId(), DisclosureTier.fromToken(s.getDisclosureTier()));
                }
                serverNameById.put(s.getId(), s.getName());
            }
        } catch (Exception e) {
            log.warn("ToolDisclosureService: failed to read MCP server tiers, defaulting to extension: {}",
                    e.getMessage());
        }

        return new Snapshot(builtinTierByName, mcpToolToServerId, serverTierById, serverNameById,
                System.currentTimeMillis());
    }

    private List<String> cachedMcpToolNames(McpServerEntity server) {
        try {
            JSONArray arr = JSONUtil.parseArray(server.getToolsCacheJson());
            List<String> rawNames = new ArrayList<>(arr.size());
            for (Object o : arr) {
                if (!(o instanceof JSONObject jo)) {
                    continue;
                }
                String name = jo.getStr("name");
                if (name != null && !name.isBlank()) {
                    rawNames.add(name);
                }
            }
            if (rawNames.isEmpty()) {
                return List.of();
            }
            return McpHashCollisionDetector.classify(server.getId(), rawNames).stream()
                    .filter(McpHashCollisionDetector.Decision::bindable)
                    .map(McpHashCollisionDetector.Decision::prefixedName)
                    .toList();
        } catch (Exception e) {
            log.debug("ToolDisclosureService: failed to parse MCP tools cache for server {}: {}",
                    server.getId(), e.getMessage());
            return List.of();
        }
    }

    private record Snapshot(Map<String, DisclosureTier> builtinTierByName,
                            Map<String, Long> mcpToolToServerId,
                            Map<Long, DisclosureTier> serverTierById,
                            Map<Long, String> serverNameById,
                            long builtAtMillis) {
    }
}
