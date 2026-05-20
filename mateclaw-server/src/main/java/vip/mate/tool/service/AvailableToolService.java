package vip.mate.tool.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpHashCollisionDetector;
import vip.mate.tool.mcp.service.McpServerService;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.model.ToolEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregator behind {@code GET /api/v1/tools/available}.
 *
 * <p>Returns one DTO per atomic tool the agent edit picker can offer:
 * built-in tools (from {@link ToolService#listEnabledTools()}) plus every
 * MCP tool persisted in {@link McpServerEntity#getToolsCacheJson()}.
 *
 * <p>Reads the cache rather than making a live MCP {@code listTools()}
 * roundtrip so the picker stays fast and stable through brief upstream
 * disconnects. The {@code stale} flag tells the UI when the entry came
 * from a server that isn't currently connected.
 *
 * <p>Hash collisions are handled by reusing the same
 * {@link McpHashCollisionDetector} the runtime uses, so an entry the
 * runtime would skip never appears in the picker as bindable. Without
 * this, the user could save a {@code mate_agent_tool.tool_name} that
 * resolves to nothing at chat time.
 *
 * <p><b>Scope</b>: this aggregator covers the two tool sources users can
 * bind from the agent edit screen — built-in {@code @Tool} beans
 * (persisted in {@code mate_tool}) and MCP-discovered tools (cached on
 * the server row). Plugin-registered {@code ToolCallback} beans surfaced
 * by other parts of the runtime are intentionally NOT listed here: those
 * are not user-bindable from the agent picker today, and the picker's
 * "saved name == runtime callback key" contract only needs to hold for
 * the rows the picker actually emits. If plugin tools later become
 * user-bindable, extend this aggregator (or accept that they go through
 * a separate config path) — see {@code AgentBindingService}'s
 * {@code SYSTEM_LEVEL_TOOLS} carve-out for the same reasoning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailableToolService {

    private final ToolService toolService;
    private final McpServerService mcpServerService;

    public List<AvailableToolDTO> listAvailable() {
        List<AvailableToolDTO> out = new ArrayList<>();
        appendBuiltinTools(out);
        appendMcpTools(out);
        return out;
    }

    private void appendBuiltinTools(List<AvailableToolDTO> out) {
        for (ToolEntity t : toolService.listEnabledTools()) {
            if (t == null || t.getName() == null || t.getName().isBlank()) continue;
            // Dispatch by toolType so channel-native tools (registered by
            // ChannelToolService) land in their own picker group rather
            // than getting lumped under "Built-in".
            if ("channel".equals(t.getToolType())) {
                out.add(AvailableToolDTO.fromChannel(t));
            } else {
                out.add(AvailableToolDTO.fromBuiltin(t));
            }
        }
    }

    private void appendMcpTools(List<AvailableToolDTO> out) {
        List<McpServerEntity> servers;
        try {
            servers = mcpServerService.listEnabled();
        } catch (Exception e) {
            log.warn("AvailableToolService: listEnabled MCP servers failed: {}", e.getMessage());
            return;
        }

        for (McpServerEntity s : servers) {
            try {
                appendOneMcpServer(out, s);
            } catch (Exception e) {
                log.warn("AvailableToolService: skipping MCP server {} due to: {}",
                        s.getId(), e.getMessage());
            }
        }
    }

    private void appendOneMcpServer(List<AvailableToolDTO> out, McpServerEntity server) {
        List<CachedTool> cached = parseCache(server.getToolsCacheJson());
        if (cached.isEmpty()) {
            return;
        }
        boolean stale = !"connected".equalsIgnoreCase(nullSafe(server.getLastStatus()));
        String groupLabel = "MCP · " + nullSafe(server.getName());
        String groupKey = "mcp:" + server.getId();

        // Run the collision check on the same raw-name list the runtime uses
        // when it registers callbacks. Sharing this exact decision shape is
        // what guarantees picker rows and AgentToolSet entries stay in sync.
        List<String> rawNames = new ArrayList<>(cached.size());
        for (CachedTool c : cached) rawNames.add(c.name);
        List<McpHashCollisionDetector.Decision> decisions =
                McpHashCollisionDetector.classify(server.getId(), rawNames);

        // Walk cache and decisions in lockstep — classify() drops blank
        // raws, so advance the decision pointer only when the cache row's
        // name is non-blank. This is the same alignment McpClientManager's
        // wrapServerCallbacks uses; both must agree on which entry got
        // which decision when the same raw appears more than once.
        int dIdx = 0;
        int rowIdx = 0;
        for (CachedTool c : cached) {
            if (c.name == null || c.name.isBlank()) {
                continue;
            }
            if (dIdx >= decisions.size()) {
                break;
            }
            McpHashCollisionDetector.Decision d = decisions.get(dIdx++);
            out.add(buildMcpDto(server, groupLabel, groupKey, stale, c, d, rowIdx++));
        }
    }

    private AvailableToolDTO buildMcpDto(McpServerEntity server, String groupLabel, String groupKey,
                                         boolean stale, CachedTool cached,
                                         McpHashCollisionDetector.Decision decision, int rowIdx) {
        // rowId distinguishes rows that share the same prefixed `name` but
        // arose from distinct raw entries (e.g. duplicate-raw, hash
        // collision). Without it, a Vue v-for keyed on `name` reuses DOM
        // for the unavailable twin and selection/disabled state goes
        // stale. Including the raw and a per-server index makes the key
        // stable across re-renders without depending on array order.
        String rowId = groupKey + "#" + rowIdx + "#" + cached.name;
        return AvailableToolDTO.builder()
                .rowId(rowId)
                .source("mcp")
                .providerId(server.getId())
                .providerName(server.getName())
                .name(decision.prefixedName())
                .rawName(cached.name)
                .description(cached.description)
                .group(groupLabel)
                .groupId(groupKey)
                .stale(stale)
                .available(decision.bindable())
                .unavailableReason(decision.unavailableReason())
                .build();
    }

    /**
     * Parse the {@code tools_cache_json} column written by
     * {@link vip.mate.tool.mcp.service.McpServerService}. Returns an empty
     * list when the column is null/blank/malformed — the picker can render
     * a server with no tools just as well as one with tools.
     */
    private static List<CachedTool> parseCache(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<CachedTool> out = new ArrayList<>(arr.size());
            for (Object o : arr) {
                if (!(o instanceof JSONObject jo)) continue;
                String name = jo.getStr("name");
                if (name == null || name.isBlank()) continue;
                String desc = jo.getStr("description", "");
                out.add(new CachedTool(name, desc != null ? desc : ""));
            }
            return out;
        } catch (Exception e) {
            log.debug("AvailableToolService: failed to parse tools_cache_json: {}", e.getMessage());
            return List.of();
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Trivial bag struct for the cached tool fields the picker needs. */
    private record CachedTool(String name, String description) {}
}
