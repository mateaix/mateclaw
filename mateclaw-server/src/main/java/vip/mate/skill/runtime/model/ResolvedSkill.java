package vip.mate.skill.runtime.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import vip.mate.skill.manifest.SkillManifest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行时已解析的技能包
 * 包含解析状态、安全扫描结果、依赖检查结果
 */
@Data
@Builder
public class ResolvedSkill {

    // ==================== 基础信息 ====================

    /** RFC-090 Phase 2 — 技能实体主键，便于按 id 反查。 */
    private Long id;

    /** 技能名称 */
    private String name;

    /** 技能描述（从 SKILL.md frontmatter 解析） */
    private String description;

    /** SKILL.md 完整内容 */
    private String content;

    /**
     * 解析后的来源类型：directory / database
     */
    private String source;

    /** 技能目录路径（如果是目录型 skill） */
    @JsonIgnore
    private Path skillDir;

    /** configJson 中配置的 skillDir（原始值） */
    private String configuredSkillDir;

    /** 是否运行时可用（综合：解析成功 + 未被安全阻断 + 依赖就绪） */
    private boolean runtimeAvailable;

    /** 解析错误信息 */
    private String resolutionError;

    /** 技能目录路径字符串（用于 JSON 序列化） */
    public String getSkillDirPath() {
        return skillDir != null ? skillDir.toString() : null;
    }

    /** references/ 目录树 */
    private Map<String, Object> references;

    /** scripts/ 目录树 */
    private Map<String, Object> scripts;

    /** 是否启用 */
    private boolean enabled;

    /** 图标 */
    private String icon;

    /** 是否为内置技能 */
    @Builder.Default
    private boolean builtin = false;

    /**
     * Owning workspace, copied from {@code mate_skill.workspace_id}. Builtin
     * skills are global, so for them this is informational only. {@code null}
     * for virtual MCP-derived skills (MCP servers carry no workspace) — the
     * runtime treats a null workspace as globally visible.
     */
    private Long workspaceId;

    /**
     * Skill row create timestamp, copied from {@code mate_skill.create_time}.
     * Used by the prompt-catalog ranker to surface freshly installed skills
     * before they accumulate any usage stats — without this, a brand-new
     * skill stays invisible behind the recent/frequent/alphabetical sort
     * and the LLM ends up replying "no such skill" right after the user
     * installed it. Null for virtual MCP/ACP skills that don't own a row.
     */
    private LocalDateTime createTime;

    // ==================== 安全扫描状态 ====================

    /** 是否被安全扫描阻断 */
    @Builder.Default
    private boolean securityBlocked = false;

    /** 安全扫描最高严重级别 */
    private String securitySeverity;

    /** 安全扫描发现摘要 */
    private String securitySummary;

    /** 安全扫描发现列表（JSON 友好） */
    private List<SecurityFinding> securityFindings;

    /** 安全警告列表 */
    private List<String> securityWarnings;

    // ==================== 依赖检查状态 ====================

    /** 依赖是否全部就绪 */
    @Builder.Default
    private boolean dependencyReady = true;

    /** 缺失依赖列表 */
    private List<String> missingDependencies;

    /** 依赖状态摘要 */
    private String dependencySummary;

    // ==================== RFC-090 §14.1 — features 矩阵 ====================

    /**
     * Parsed manifest (RFC-090 §14.6 SoT). Null for legacy skills with no
     * frontmatter; in that case feature/manifest-aware code falls back to
     * legacy fields above.
     */
    private SkillManifest manifest;

    /**
     * Per-feature status keyed by {@code feature.id}. Values come from
     * {@code SkillDependencyChecker.FeatureCheckResult.status}: one of
     * {@code READY / SETUP_NEEDED / UNSUPPORTED}.
     *
     * <p>For backward compat: if the manifest declares no {@code features[]}
     * block, the resolver synthesizes a single feature {@code "default"}
     * carrying the top-level requires + platforms.
     */
    @Builder.Default
    private Map<String, String> featureStatuses = Map.of();

    /**
     * Set of feature IDs whose status is READY. Derived from
     * {@link #featureStatuses}; populated by the resolver.
     */
    @Builder.Default
    private Set<String> activeFeatures = Set.of();

    /**
     * Per-tool display-name decoration table, keyed by the prefixed callback
     * name and valued by the human-readable form (e.g.
     * {@code "mcp_4_fs_a1b2c3"} → {@code "mcp_4_fs_a1b2c3 (read_file)"}).
     *
     * <p>Populated by skill source providers that have a recoverable raw
     * name (currently MCP-bridged skills); other sources leave it empty,
     * in which case {@link #getEffectiveAllowedToolsDisplay()} falls
     * through to the prefixed names unchanged.
     *
     * <p>Held internally rather than serialized: the wire shape exposes
     * the decorated set via the derived getter, which keeps the
     * source-of-truth (the feature filter in
     * {@link #getEffectiveAllowedTools()}) in one place.
     */
    @JsonIgnore
    @Builder.Default
    private Map<String, String> toolDisplayNames = Map.of();

    /** RFC-090 §14.1 — replacement filter for {@code dependencyReady}. */
    public boolean hasAnyActiveFeature() {
        return activeFeatures != null && !activeFeatures.isEmpty();
    }

    /**
     * RFC-090 §14.2 — tools that should be advertised to the LLM, given
     * the current feature statuses.
     *
     * <p>Behavior:
     * <ul>
     *   <li>No manifest at all → {@link Set#of()} (caller should treat as
     *       "no skill-derived tools" and rely on legacy paths).</li>
     *   <li>Manifest with no {@code features[]} → return {@code allowedTools}
     *       wholesale (legacy behavior).</li>
     *   <li>Manifest with features → only tools whose owning feature is
     *       READY. A feature with empty {@code feature.tools} is treated as
     *       "inherit the manifest-level allowed-tools" so a single-feature
     *       skill matches the no-features case.</li>
     * </ul>
     */
    public Set<String> getEffectiveAllowedTools() {
        if (manifest == null) return Set.of();
        List<String> base = manifest.getAllowedTools();
        if (base == null) base = List.of();

        // No features → wholesale allowedTools
        if (manifest.getFeatures() == null || manifest.getFeatures().isEmpty()) {
            return base.isEmpty() ? Set.of() : new LinkedHashSet<>(base);
        }

        Set<String> out = new LinkedHashSet<>();
        Set<String> active = activeFeatures == null ? Set.of() : activeFeatures;

        // First pass: collect tools claimed by *any* feature that
        // declares an explicit tool subset. Anything in this set is
        // owned by a specific feature, so the inherit branch below must
        // exclude these unless their owning feature is READY.
        // RFC-090 §14.2 / §10.2 Q8 — the LLM must not see tools that
        // belong to a SETUP_NEEDED / UNSUPPORTED feature.
        Set<String> claimedByAnyFeature = new LinkedHashSet<>();
        Set<String> claimedByActive = new LinkedHashSet<>();
        for (SkillManifest.FeatureDef f : manifest.getFeatures()) {
            List<String> tools = f.getTools();
            if (tools == null || tools.isEmpty()) continue;
            claimedByAnyFeature.addAll(tools);
            if (active.contains(f.getId())) claimedByActive.addAll(tools);
        }
        out.addAll(claimedByActive);

        // Second pass: any READY feature with no explicit tool list
        // means "inherit manifest-level allowed-tools" — but inheritance
        // is *fenced*: it doesn't pull in tools that another feature
        // already claimed (and isn't itself READY).
        boolean anyInheritor = manifest.getFeatures().stream().anyMatch(
                f -> active.contains(f.getId())
                        && (f.getTools() == null || f.getTools().isEmpty()));
        if (anyInheritor) {
            for (String t : base) {
                if (claimedByAnyFeature.contains(t) && !claimedByActive.contains(t)) {
                    // This tool belongs to a feature that's not READY —
                    // inheritance must NOT re-expose it.
                    continue;
                }
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Display-friendly companion to {@link #getEffectiveAllowedTools()}.
     * Each prefixed callback name is replaced by its decorated form (e.g.
     * {@code "mcp_4_fs_a1b2c3 (read_file)"}) when {@link #toolDisplayNames}
     * carries an entry for it; names without a decoration entry are kept
     * verbatim. Feature-filter semantics match the prefixed getter, so a
     * tool that is hidden by a SETUP_NEEDED feature stays hidden here too.
     */
    public Set<String> getEffectiveAllowedToolsDisplay() {
        Set<String> base = getEffectiveAllowedTools();
        if (base.isEmpty() || toolDisplayNames == null || toolDisplayNames.isEmpty()) {
            return base;
        }
        Set<String> out = new LinkedHashSet<>(base.size());
        for (String name : base) {
            out.add(toolDisplayNames.getOrDefault(name, name));
        }
        return out;
    }

    // ==================== 综合状态 ====================

    /**
     * 综合运行时状态标签
     * 用于前端 badge 显示
     */
    public String getRuntimeStatusLabel() {
        if (!enabled) return "Disabled";
        if (securityBlocked) return "Security Blocked";
        if (!dependencyReady) return "Dependencies Missing";
        if (resolutionError != null && !runtimeAvailable) return "Unresolved";
        if (securityFindings != null && !securityFindings.isEmpty()) return "Security Warning";
        if (runtimeAvailable) return "Ready";
        return "Unknown";
    }

    // ==================== 内部 DTO ====================

    /**
     * 安全发现（前端展示用，SkillValidationResult.Finding 的序列化友好版本）
     */
    @Data
    @Builder
    public static class SecurityFinding {
        private String ruleId;
        private String severity;
        private String category;
        private String title;
        private String description;
        private String filePath;
        private Integer lineNumber;
        private String snippet;
        private String remediation;
    }
}
