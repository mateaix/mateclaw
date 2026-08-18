package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stable, small-schema bridge for progressively disclosed tools.
 *
 * <p>The catalog is rebuilt from the current agent's effective tool set on
 * every call, so search/describe cannot reveal tools outside its binding.
 * {@code tool_call} is intentionally not executed here: the graph executor
 * unwraps it before guard/approval/audit and invokes the real callback in the
 * same action round. That keeps the bridge from becoming a security bypass.
 */
@Component
@RequiredArgsConstructor
public class ProgressiveToolBridgeTool {

    public static final String SEARCH = "tool_search";
    public static final String DESCRIBE = "tool_describe";
    public static final String CALL = "tool_call";
    public static final Set<String> BRIDGE_NAMES = Set.of(SEARCH, DESCRIBE, CALL);
    /** Executor-owned, immutable callback snapshot carried through ToolContext. */
    public static final String SCOPED_TOOL_CALLBACKS_CONTEXT_KEY =
            "mateclaw.progressiveToolCallbacks";

    private static final int DEFAULT_LIMIT = 8;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final AgentBindingService agentBindingService;
    private final ToolGuardConfigService toolGuardConfigService;

    @Tool(name = SEARCH, description = """
        Search the current agent's tool catalog by capability. Returns compact
        names and one-line descriptions, not full schemas. If you already know
        the exact tool name from the Extension Tools catalog, skip this search
        and call tool_call directly.
        """)
    public String search(
            @ToolParam(description = "Capability or keywords to search for", required = false)
            String query,
            @ToolParam(description = "Maximum results (default 8, maximum 20)", required = false)
            Integer limit,
            @Nullable ToolContext ctx) {
        int safeLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, 20));
        List<String> terms = terms(query);
        boolean browseCatalog = query == null || query.isBlank();
        List<ToolCallback> candidates = effectiveToolSet(ctx).callbacks().stream()
                .filter(cb -> !BRIDGE_NAMES.contains(cb.getToolDefinition().name()))
                .toList();
        List<ScoredTool> matches = (browseCatalog ? rank(candidates, List.of())
                        : terms.isEmpty() ? List.<ScoredTool>of() : rank(candidates, terms)).stream()
                .filter(st -> browseCatalog || st.score() > 0.0d)
                .sorted(Comparator.comparingDouble(ScoredTool::score).reversed()
                        .thenComparing(st -> st.callback().getToolDefinition().name()))
                .limit(safeLimit)
                .toList();

        List<Map<String, Object>> rows = new ArrayList<>(matches.size());
        for (ScoredTool match : matches) {
            var def = match.callback().getToolDefinition();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", def.name());
            row.put("description", compact(def.description(), 180));
            rows.add(row);
        }
        return json(Map.of("query", query == null ? "" : query, "tools", rows,
                "hint", "Use tool_describe only when arguments are unclear; use tool_call to execute in this round."));
    }

    @Tool(name = DESCRIBE, description = """
        Return the full JSON input schema for one exact tool name. Use only
        when its arguments are unclear; description is not a prerequisite for
        tool_call.
        """)
    public String describe(
            @ToolParam(description = "Exact tool function name") String toolName,
            @Nullable ToolContext ctx) {
        ToolCallback callback = effectiveToolSet(ctx).callbackByName().get(toolName);
        if (callback == null || BRIDGE_NAMES.contains(toolName)) {
            return json(Map.of("error", "Tool is not available to this agent", "toolName", safe(toolName)));
        }
        var def = callback.getToolDefinition();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", def.name());
        result.put("description", def.description());
        try {
            result.put("inputSchema", OBJECT_MAPPER.readTree(def.inputSchema()));
        } catch (Exception ignored) {
            result.put("inputSchema", def.inputSchema());
        }
        return json(result);
    }

    @Tool(name = CALL, description = """
        Execute a tool that is listed in the current agent's tool catalog,
        including progressively disclosed tools whose full schema is hidden.
        The real tool is invoked in this same action round. Pass arguments as
        a JSON object. Use tool_describe first only if you do not know them.
        """)
    public String call(
            @ToolParam(description = "Exact target tool function name") String toolName,
            @ToolParam(description = "Arguments for the target tool as a JSON object") Map<String, Object> arguments,
            @Nullable ToolContext ctx) {
        // Defense in depth. Normal graph execution intercepts this call and
        // routes it through the real tool's guard/approval path.
        return "Error: tool_call must be handled by the graph tool executor.";
    }

    private AgentToolSet effectiveToolSet(ToolContext ctx) {
        AgentToolSet scoped = scopedToolSet(ctx);
        if (scoped != null) {
            return scoped;
        }
        // Compatibility fallback for direct/unit invocations outside a graph.
        // Normal graph execution always supplies the executor-owned snapshot.
        AgentToolSet set = toolRegistry.getEnabledToolSet();
        Long agentId = ChatOrigin.from(ctx).agentId();
        Set<String> denied = new LinkedHashSet<>(toolGuardConfigService.getDeniedTools());
        if (agentId != null) {
            denied.addAll(agentBindingService.getSkillDiscoveryDeniedTools(agentId));
        }
        set = set.withDeniedToolsFiltered(denied);
        if (agentId != null) {
            set = set.withAllowedToolsOnly(agentBindingService.getEffectiveToolNames(agentId));
        }
        return set;
    }

    private static AgentToolSet scopedToolSet(ToolContext ctx) {
        if (ctx == null) return null;
        Object value = ctx.getContext().get(SCOPED_TOOL_CALLBACKS_CONTEXT_KEY);
        if (!(value instanceof Map<?, ?> raw)) return null;
        List<ToolCallback> callbacks = raw.values().stream()
                .filter(ToolCallback.class::isInstance)
                .map(ToolCallback.class::cast)
                .toList();
        return AgentToolSet.fromCallbacks(List.of(), callbacks);
    }

    /** Small in-memory BM25 index; catalogs are normally below a few hundred tools. */
    private static List<ScoredTool> rank(List<ToolCallback> callbacks, List<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return callbacks.stream().map(cb -> new ScoredTool(cb, 0.0d)).toList();
        }
        List<CatalogEntry> entries = callbacks.stream().map(ProgressiveToolBridgeTool::catalogEntry).toList();
        double avgLength = entries.stream().mapToInt(e -> e.tokens().size()).average().orElse(1.0d);
        Map<String, Long> documentFrequency = queryTerms.stream().collect(Collectors.toMap(
                Function.identity(),
                term -> entries.stream().filter(e -> e.frequencies().containsKey(term)).count(),
                (a, b) -> a,
                LinkedHashMap::new));
        int documentCount = Math.max(1, entries.size());
        List<ScoredTool> result = new ArrayList<>(entries.size());
        for (CatalogEntry entry : entries) {
            double score = 0.0d;
            for (String term : queryTerms) {
                int frequency = entry.frequencies().getOrDefault(term, 0);
                long df = documentFrequency.getOrDefault(term, 0L);
                if (frequency > 0) {
                    double idf = Math.log(1.0d + (documentCount - df + 0.5d) / (df + 0.5d));
                    double denominator = frequency + 1.5d
                            * (1.0d - 0.75d + 0.75d * entry.tokens().size() / avgLength);
                    score += idf * frequency * 2.5d / denominator;
                }
                if (entry.normalizedName().equals(term)) score += 8.0d;
                else if (entry.normalizedName().contains(term)) score += 2.0d;
            }
            result.add(new ScoredTool(entry.callback(), score));
        }
        return result;
    }

    private static CatalogEntry catalogEntry(ToolCallback callback) {
        var definition = callback.getToolDefinition();
        List<String> tokens = new ArrayList<>();
        tokens.addAll(terms(definition.name().replace('_', ' ')));
        tokens.addAll(terms(definition.description()));
        try {
            var schema = OBJECT_MAPPER.readTree(definition.inputSchema());
            var properties = schema.path("properties");
            if (properties.isObject()) {
                properties.fieldNames().forEachRemaining(name ->
                        tokens.addAll(terms(name.replace('_', ' '))));
            }
        } catch (Exception ignored) {
            // Third-party schemas may be malformed; name/description remain searchable.
        }
        Map<String, Integer> frequencies = new HashMap<>();
        tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
        return new CatalogEntry(callback, definition.name().toLowerCase(Locale.ROOT),
                List.copyOf(tokens), Map.copyOf(frequencies));
    }

    private static List<String> terms(String query) {
        if (query == null || query.isBlank()) return List.of();
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT)
                        .split("[^\\p{L}\\p{N}]+"))
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    private static String compact(String value, int max) {
        String normalized = safe(value).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 3) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"Failed to render tool catalog\"}";
        }
    }

    private record CatalogEntry(ToolCallback callback, String normalizedName,
                                List<String> tokens, Map<String, Integer> frequencies) {}

    private record ScoredTool(ToolCallback callback, double score) {}
}
