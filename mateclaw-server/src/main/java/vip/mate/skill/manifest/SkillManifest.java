package vip.mate.skill.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * RFC-090 Phase 2 — parsed SKILL.md manifest (source of truth, §14.6).
 *
 * <p>Maps the YAML frontmatter shape from §5.1 onto a typed model.
 * Persisted to {@code mate_skill.manifest_json}; legacy columns
 * (skill_type / icon / version / author) are projected from this
 * after each resolve.
 *
 * <p>All collection fields default to empty so consumers don't need
 * null guards. Unknown YAML keys are preserved in
 * {@link #extras} for forward compatibility.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SkillManifest {

    // ==================== Identity ====================

    private String id;
    private String name;
    private String description;
    private String icon;
    private String version;
    private String author;

    /** prompt | code | mcp | acp | knowledge */
    private String type;

    /** file | web | data | content | comm | system | ... (free-form tag) */
    private String category;

    // ==================== Tools / dependencies ====================

    /** Anthropic-compatible {@code allowed-tools} list. */
    @Builder.Default
    private List<String> allowedTools = List.of();

    /** Top-level {@code requires} entries. */
    @Builder.Default
    private List<RequirementDef> requires = List.of();

    /** Top-level {@code platforms} list (overall package compatibility). */
    @Builder.Default
    private List<String> platforms = List.of();

    /**
     * v3.1 {@code features[]} matrix. Empty list means
     * "no explicit feature partitioning" — the resolver synthesizes a
     * single default feature carrying the top-level {@link #requires}
     * and {@link #platforms} so legacy skills behave unchanged.
     */
    @Builder.Default
    private List<FeatureDef> features = List.of();

    // ==================== User-facing settings ====================

    @Builder.Default
    private List<SettingDef> settings = List.of();

    // ==================== Provider routing ====================

    @Builder.Default
    private List<String> requiresModel = List.of();

    // ==================== Dashboard ====================

    @Builder.Default
    private List<DashboardMetric> dashboardMetrics = List.of();

    // ==================== v3 self-evolution ====================

    @Builder.Default
    private SelfEvolution selfEvolution = SelfEvolution.defaults();

    // ==================== v3.1 knowledge type ====================

    private KnowledgeBinding knowledge;

    // ==================== Phase 7b — type=acp binding ====================

    /** Set when {@code type=acp}. Resolves to a {@code mate_acp_endpoint} row. */
    private AcpBinding acp;

    // ==================== type=code script entrypoints ====================

    /**
     * Declared script entrypoints from the {@code scripts} frontmatter
     * block. Each entry is exposed to the model as a typed wrapper tool —
     * the model fills schema-described fields and the runtime serializes
     * them into process arguments, so a script consuming a JSON payload
     * never depends on the model hand-crafting a JSON string.
     */
    @Builder.Default
    private List<ScriptDef> scripts = List.of();

    // ==================== Forward-compat catch-all ====================

    /** Unknown frontmatter keys are stashed here so a future field
     *  doesn't drop on parse — the JSON round-trips intact. */
    @Builder.Default
    private Map<String, Object> extras = Map.of();

    // ==================== Nested types ====================

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class RequirementDef {
        /** Required: stable identifier referenced by {@code features[*].requires}. */
        private String key;
        /** binary | env_var | api_key */
        private String type;
        /** Probe target — for binary, the executable name; for env_var, the env name. */
        private String check;
        /** Optional means it only blocks features that reference it explicitly. */
        @Builder.Default
        private boolean optional = false;
        private String description;
        @Builder.Default
        private Map<String, String> install = Map.of();
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class FeatureDef {
        private String id;
        private String label;
        @Builder.Default
        private List<String> requires = List.of();
        @Builder.Default
        private List<String> platforms = List.of();
        /** Tools advertised only when this feature is READY. Empty
         *  means "inherit the manifest-level allowed-tools as-is". */
        @Builder.Default
        private List<String> tools = List.of();
        private String fallbackMessage;
        private String unsupportedMessage;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class SettingDef {
        private String key;
        private String label;
        /** select | text | secret | toggle */
        private String type;
        private Object defaultValue;
        @Builder.Default
        private List<Map<String, Object>> options = List.of();
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class DashboardMetric {
        private String label;
        private String memoryKey;
        private String format;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class SelfEvolution {
        @Builder.Default
        private boolean lessonsEnabled = true;
        @Builder.Default
        private int lessonsMaxEntries = 50;
        @Builder.Default
        private boolean memoryWritesAllowed = true;

        public static SelfEvolution defaults() {
            return SelfEvolution.builder().build();
        }
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class AcpBinding {
        /** ACP endpoint slug (matches {@code mate_acp_endpoint.name}). */
        private String endpoint;
        /**
         * Optional system-prompt override delivered to the upstream agent
         * before the user's message. Useful when a single ACP CLI is
         * shared across several MateClaw skills with different personas.
         */
        private String systemPrefix;
        /**
         * Working directory hint for the spawned ACP process; defaults
         * to the current MateClaw workspace path when null.
         */
        private String cwd;
        /**
         * Resolved endpoint id, written at install time. Null when the
         * endpoint slug couldn't be resolved.
         */
        private Long resolvedEndpointId;
    }

    /**
     * One script entrypoint declared under the {@code scripts} block. The
     * resolver turns each into a typed wrapper tool named
     * {@code skill_<skill>_<id>}.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class ScriptDef {
        /** Stable id; forms the suffix of the generated wrapper tool name. */
        private String id;
        /** Human-readable label for the entrypoint. */
        private String label;
        /** Script path relative to the skill directory (e.g. {@code scripts/run.py}). */
        private String path;
        /** What the entrypoint does — surfaced as the wrapper tool description. */
        private String description;
        /**
         * Literal arguments prepended before the typed arguments. Lets one
         * dispatcher script back several entrypoints — e.g. a fixed method
         * name as {@code argv[1]} with the typed JSON payload as {@code argv[2]}.
         */
        @Builder.Default
        private List<String> fixedArgs = List.of();
        /**
         * Raw JSON Schema object describing the entrypoint's parameters,
         * forwarded verbatim as the wrapper tool's input schema.
         */
        @Builder.Default
        private Map<String, Object> parameters = Map.of();
        /**
         * How typed arguments reach the script process:
         * {@code json} (default) forwards one compact JSON argument;
         * {@code flags} forwards each property as a {@code --key value} pair.
         */
        @Builder.Default
        private String argStyle = "json";
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class KnowledgeBinding {
        /** KB slug declared in manifest (e.g. {@code tcm-classics}). */
        private String bindKb;
        /** vector | bm25 | hybrid */
        private String retrieval;
        @Builder.Default
        private int topK = 6;
        /** required | optional | none */
        @Builder.Default
        private String citation = "optional";
        @Builder.Default
        private boolean rerank = false;

        /** Resolved KB id, written at install time after slug lookup
         *  (RFC-090 §14.4). Null until resolved. */
        private Long boundKbId;
    }
}
