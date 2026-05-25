package vip.mate.skill.manifest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RFC-090 Phase 2 — typed parser for SKILL.md manifests.
 *
 * <p>Builds a {@link SkillManifest} from the raw frontmatter map produced
 * by {@link SkillFrontmatterParser}. Unknown keys are stashed in
 * {@link SkillManifest#getExtras()} so we can JSON round-trip safely.
 *
 * <p>This is additive: it does not modify {@code SkillFrontmatterParser}
 * and does not change runtime behavior on its own. Callers wire in via
 * {@link SkillPackageResolver}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillManifestParser {

    private static final Set<String> KNOWN_KEYS = Set.of(
            "id", "name", "description", "icon", "version", "author",
            "type", "category",
            "allowed-tools", "allowed_tools",
            "requires", "platforms", "features",
            "settings", "requires-model", "requires_model",
            "dependencies",
            "dashboard", "self-evolution", "self_evolution",
            "knowledge",
            "acp",
            "scripts",
            // legacy / housekeeping fields that aren't manifest-relevant
            "metadata"
    );

    private final SkillFrontmatterParser frontmatterParser;

    /**
     * Parse the SKILL.md content into a typed manifest.
     *
     * <p>Returns {@code null} when the content has no frontmatter or
     * fails to parse — callers should fall back to legacy handling.
     */
    public SkillManifest parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
        return parseFromFrontmatter(parsed);
    }

    /**
     * Build a manifest from an already-parsed frontmatter object so we
     * don't re-run the YAML parse when the resolver already has the
     * map handy.
     */
    public SkillManifest parseFromFrontmatter(SkillFrontmatterParser.ParsedSkillMd parsed) {
        if (parsed == null || parsed.getFrontmatter() == null) {
            return null;
        }
        Map<String, Object> fm = parsed.getFrontmatter();

        // RFC-090 §5.4 — Anthropic-compatible `allowed-tools` is preferred, but
        // 99% of legacy SKILL.md files declare tools via `dependencies.tools`
        // (per the original SkillFrontmatterParser shape). Fall back to that
        // list when v3 fields are absent so old skills stop rendering an
        // empty Tools tab in the detail drawer.
        List<String> v3AllowedTools = stringList(coalesce(fm, "allowed-tools", "allowed_tools"));
        List<String> effectiveAllowedTools = v3AllowedTools.isEmpty()
                && parsed.getDependencies() != null
                && !parsed.getDependencies().getTools().isEmpty()
                ? new ArrayList<>(parsed.getDependencies().getTools())
                : v3AllowedTools;

        SkillManifest.SkillManifestBuilder b = SkillManifest.builder()
                .id(string(fm, "id"))
                .name(string(fm, "name"))
                .description(string(fm, "description"))
                .icon(string(fm, "icon"))
                .version(string(fm, "version"))
                .author(string(fm, "author"))
                .type(string(fm, "type"))
                .category(string(fm, "category"))
                .allowedTools(effectiveAllowedTools)
                .platforms(parsed.getPlatforms() == null ? List.of() : parsed.getPlatforms())
                .requires(parseRequires(fm.get("requires"), parsed.getDependencies()))
                .features(parseFeatures(fm.get("features")))
                .settings(parseSettings(fm.get("settings")))
                .requiresModel(stringList(coalesce(fm, "requires-model", "requires_model")))
                .dashboardMetrics(parseDashboard(fm.get("dashboard")))
                .selfEvolution(parseSelfEvolution(coalesce(fm, "self-evolution", "self_evolution")))
                .knowledge(parseKnowledge(fm.get("knowledge")))
                .acp(parseAcp(fm.get("acp")))
                .scripts(parseScripts(fm.get("scripts")))
                .extras(extractUnknown(fm));

        return b.build();
    }

    // ==================== requires ====================

    @SuppressWarnings("unchecked")
    private List<SkillManifest.RequirementDef> parseRequires(
            Object rawRequires, SkillFrontmatterParser.SkillDependencies legacyDeps) {
        List<SkillManifest.RequirementDef> out = new ArrayList<>();

        if (rawRequires instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> m = (Map<String, Object>) map;
                Map<String, Object> install = m.get("install") instanceof Map<?, ?> i
                        ? toStringObjectMap((Map<?, ?>) i) : Map.of();
                Map<String, String> installStr = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : install.entrySet()) {
                    if (e.getValue() != null) installStr.put(e.getKey(), e.getValue().toString());
                }
                out.add(SkillManifest.RequirementDef.builder()
                        .key(string(m, "key"))
                        .type(string(m, "type"))
                        .check(string(m, "check"))
                        .optional(bool(m, "optional", false))
                        .description(string(m, "description"))
                        .install(installStr)
                        .build());
            }
        }

        // Backward compat: synthesize requires from legacy
        // dependencies.{commands,env} when no v3 requires[] block is present.
        if (out.isEmpty() && legacyDeps != null) {
            for (String cmd : legacyDeps.getCommands()) {
                out.add(SkillManifest.RequirementDef.builder()
                        .key("cmd:" + cmd)
                        .type("binary")
                        .check(cmd)
                        .build());
            }
            for (String env : legacyDeps.getEnv()) {
                out.add(SkillManifest.RequirementDef.builder()
                        .key("env:" + env)
                        .type("env_var")
                        .check(env)
                        .build());
            }
        }
        return out;
    }

    // ==================== features ====================

    @SuppressWarnings("unchecked")
    private List<SkillManifest.FeatureDef> parseFeatures(Object rawFeatures) {
        if (!(rawFeatures instanceof List<?> list)) return List.of();
        List<SkillManifest.FeatureDef> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> m = (Map<String, Object>) map;
            out.add(SkillManifest.FeatureDef.builder()
                    .id(string(m, "id"))
                    .label(string(m, "label"))
                    .requires(stringList(m.get("requires")))
                    .platforms(stringList(m.get("platforms")))
                    .tools(stringList(m.get("tools")))
                    .fallbackMessage(string(m, "fallback_message"))
                    .unsupportedMessage(string(m, "unsupported_message"))
                    .build());
        }
        return out;
    }

    // ==================== settings ====================

    @SuppressWarnings("unchecked")
    private List<SkillManifest.SettingDef> parseSettings(Object rawSettings) {
        if (!(rawSettings instanceof List<?> list)) return List.of();
        List<SkillManifest.SettingDef> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> m = (Map<String, Object>) map;
            List<Map<String, Object>> options = new ArrayList<>();
            if (m.get("options") instanceof List<?> opts) {
                for (Object opt : opts) {
                    if (opt instanceof Map<?, ?> om) {
                        options.add(toStringObjectMap((Map<?, ?>) om));
                    }
                }
            }
            out.add(SkillManifest.SettingDef.builder()
                    .key(string(m, "key"))
                    .label(string(m, "label"))
                    .type(string(m, "type"))
                    .defaultValue(m.get("default"))
                    .options(options)
                    .build());
        }
        return out;
    }

    // ==================== dashboard ====================

    @SuppressWarnings("unchecked")
    private List<SkillManifest.DashboardMetric> parseDashboard(Object rawDashboard) {
        if (!(rawDashboard instanceof Map<?, ?> dashMap)) return List.of();
        Object metrics = ((Map<String, Object>) dashMap).get("metrics");
        if (!(metrics instanceof List<?> list)) return List.of();
        List<SkillManifest.DashboardMetric> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> m = (Map<String, Object>) map;
            out.add(SkillManifest.DashboardMetric.builder()
                    .label(string(m, "label"))
                    .memoryKey(string(m, "memory_key"))
                    .format(string(m, "format"))
                    .build());
        }
        return out;
    }

    // ==================== self-evolution ====================

    @SuppressWarnings("unchecked")
    private SkillManifest.SelfEvolution parseSelfEvolution(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return SkillManifest.SelfEvolution.defaults();
        Map<String, Object> m = (Map<String, Object>) map;
        return SkillManifest.SelfEvolution.builder()
                .lessonsEnabled(bool(m, "lessons_enabled", true))
                .lessonsMaxEntries(intVal(m, "lessons_max_entries", 50))
                .memoryWritesAllowed(bool(m, "memory_writes_allowed", true))
                .build();
    }

    // ==================== knowledge ====================

    @SuppressWarnings("unchecked")
    private SkillManifest.KnowledgeBinding parseKnowledge(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> m = (Map<String, Object>) map;
        Object kbId = m.get("boundKbId");
        Long resolvedId = null;
        if (kbId instanceof Number n) resolvedId = n.longValue();
        else if (kbId instanceof String s && !s.isBlank()) {
            try { resolvedId = Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { /* leave null */ }
        }
        return SkillManifest.KnowledgeBinding.builder()
                .bindKb(string(m, "bind_kb"))
                .retrieval(string(m, "retrieval"))
                .topK(intVal(m, "top_k", 6))
                .citation(stringOrDefault(m, "citation", "optional"))
                .rerank(bool(m, "rerank", false))
                .boundKbId(resolvedId)
                .build();
    }

    // ==================== scripts ====================

    /**
     * Parse the {@code scripts} block — a list of script entrypoint maps.
     * The per-entry {@code parameters} map is carried through as a raw
     * JSON Schema object; nested maps / lists from the YAML parse stay
     * intact so the wrapper factory can serialize them verbatim.
     */
    @SuppressWarnings("unchecked")
    private List<SkillManifest.ScriptDef> parseScripts(Object rawScripts) {
        if (!(rawScripts instanceof List<?> list)) return List.of();
        List<SkillManifest.ScriptDef> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> m = (Map<String, Object>) map;
            Map<String, Object> parameters = m.get("parameters") instanceof Map<?, ?> p
                    ? toStringObjectMap((Map<?, ?>) p) : Map.of();
            out.add(SkillManifest.ScriptDef.builder()
                    .id(string(m, "id"))
                    .label(string(m, "label"))
                    .path(string(m, "path"))
                    .description(string(m, "description"))
                    .fixedArgs(stringList(coalesce(m, "fixed_args", "fixedArgs")))
                    .parameters(parameters)
                    .argStyle(stringOrDefault(m, "arg_style",
                            stringOrDefault(m, "argStyle", "json")))
                    .build());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private SkillManifest.AcpBinding parseAcp(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> m = (Map<String, Object>) map;
        Long resolvedId = null;
        Object idVal = m.get("resolvedEndpointId");
        if (idVal == null) idVal = m.get("resolved_endpoint_id");
        if (idVal instanceof Number n) resolvedId = n.longValue();
        else if (idVal instanceof String s && !s.isBlank()) {
            try { resolvedId = Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { /* leave null */ }
        }
        return SkillManifest.AcpBinding.builder()
                .endpoint(string(m, "endpoint"))
                .systemPrefix(stringOrDefault(m, "system_prefix",
                        stringOrDefault(m, "systemPrefix", null)))
                .cwd(string(m, "cwd"))
                .resolvedEndpointId(resolvedId)
                .build();
    }

    // ==================== helpers ====================

    private Map<String, Object> extractUnknown(Map<String, Object> fm) {
        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fm.entrySet()) {
            if (!KNOWN_KEYS.contains(e.getKey())) extras.put(e.getKey(), e.getValue());
        }
        return extras.isEmpty() ? Map.of() : extras;
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static String stringOrDefault(Map<String, Object> map, String key, String fallback) {
        String v = string(map, key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static int intVal(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) out.add(item.toString());
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) return List.of(s);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    /** Pick the first non-null value among the listed keys. */
    private static Object coalesce(Map<String, Object> fm, String... keys) {
        for (String k : keys) {
            Object v = fm.get(k);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Convenience accessor for downstream code that wants a quick set of
     * required requirement keys (not yet status-aware).
     */
    public static Set<String> requiredKeys(SkillManifest manifest) {
        if (manifest == null) return Collections.emptySet();
        Set<String> keys = new HashSet<>();
        for (SkillManifest.RequirementDef r : manifest.getRequires()) {
            if (r.getKey() != null) keys.add(r.getKey());
        }
        return keys;
    }

    /** Hook mirror for {@link #parseFromFrontmatter} when callers only have a raw map. */
    public SkillManifest parseRawMap(Map<String, Object> frontmatter, List<String> platforms,
                                     SkillFrontmatterParser.SkillDependencies deps) {
        if (frontmatter == null) return null;
        SkillFrontmatterParser.ParsedSkillMd shim = SkillFrontmatterParser.ParsedSkillMd.builder()
                .frontmatter(new HashMap<>(frontmatter))
                .platforms(platforms == null ? List.of() : platforms)
                .dependencies(deps == null ? SkillFrontmatterParser.SkillDependencies.empty() : deps)
                .build();
        return parseFromFrontmatter(shim);
    }
}
