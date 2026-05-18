package vip.mate.skill.runtime;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import vip.mate.skill.knowledge.AcpSkillWrapperToolFactory;
import vip.mate.skill.knowledge.WikiSkillWrapperToolFactory;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.manifest.SkillManifestParser;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 技能包解析器
 * 将 SkillEntity 解析为 ResolvedSkill（运行时可用的技能包）
 * <p>
 * 三级解析流程：
 * <ol>
 *   <li>显式 skillDir（configJson.skillDir）→ source="directory"</li>
 *   <li>约定路径（{workspace-root}/{skillName}/）→ source="convention"</li>
 *   <li>数据库 skillContent → source="database"</li>
 * </ol>
 * 解析后依次执行：安全扫描 → 依赖检查
 */
@Slf4j
@Component
public class SkillPackageResolver {

    private final SkillFrontmatterParser frontmatterParser;
    private final SkillManifestParser manifestParser;
    private final SkillDirectoryScanner directoryScanner;
    private final SkillSecurityService securityService;
    private final SkillDependencyChecker dependencyChecker;
    private final ObjectMapper objectMapper;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillMapper skillMapper;
    /**
     * RFC-090 §14.4 — knowledge-skill wrapper tool factory.
     * {@code @Lazy} because WikiSkillWrapperToolFactory pulls in Wiki
     * services that lag this bean's construction order.
     */
    private final WikiSkillWrapperToolFactory wikiWrapperFactory;
    /**
     * RFC-090 Phase 7b — type=acp skill wrapper factory.
     * Same {@code @Lazy} treatment because it pulls in
     * AcpEndpointService → mybatis mapper which boots later in the
     * Spring lifecycle.
     */
    private final AcpSkillWrapperToolFactory acpWrapperFactory;
    /**
     * {@code @Lazy} on ToolRegistry — same lazy-resolution loop as
     * {@code SkillDependencyChecker}; without this we'd reach for the
     * registry before its plugin tools have wired up.
     */
    private final ToolRegistry toolRegistry;

    /**
     * Tracks which wrapper-tool names we've already registered for
     * each skill id, so re-resolves diff-update the registry cleanly
     * (deregister stale + register current) without registering twice.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Set<String>> registeredWrappers
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Content-hash → cached scan outcome, so a refresh on unchanged
     * SKILL.md content reuses the previous result instead of running
     * the security scanner again. Builtin skills already short-circuit
     * up-front; this cache helps user-installed dynamic skills, where
     * the same row gets re-resolved on every refresh.
     */
    private final ConcurrentMap<Long, CachedScanOutcome> securityScanCache = new ConcurrentHashMap<>();

    private record CachedScanOutcome(String contentHash, SkillValidationResult result) {}

    @Autowired
    public SkillPackageResolver(SkillFrontmatterParser frontmatterParser,
                                 SkillManifestParser manifestParser,
                                 SkillDirectoryScanner directoryScanner,
                                 SkillSecurityService securityService,
                                 SkillDependencyChecker dependencyChecker,
                                 ObjectMapper objectMapper,
                                 SkillWorkspaceManager workspaceManager,
                                 SkillMapper skillMapper,
                                 @Lazy WikiSkillWrapperToolFactory wikiWrapperFactory,
                                 @Lazy AcpSkillWrapperToolFactory acpWrapperFactory,
                                 @Lazy ToolRegistry toolRegistry) {
        this.frontmatterParser = frontmatterParser;
        this.manifestParser = manifestParser;
        this.directoryScanner = directoryScanner;
        this.securityService = securityService;
        this.dependencyChecker = dependencyChecker;
        this.objectMapper = objectMapper;
        this.workspaceManager = workspaceManager;
        this.skillMapper = skillMapper;
        this.wikiWrapperFactory = wikiWrapperFactory;
        this.acpWrapperFactory = acpWrapperFactory;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 解析技能实体为运行时技能包（完整流程）
     */
    public ResolvedSkill resolve(SkillEntity entity) {
        String configuredDir = extractSkillDirString(entity);
        Path skillDir = configuredDir != null ? Paths.get(configuredDir) : null;

        ResolvedSkill resolved;

        // 三级解析：explicit → convention → database
        if (skillDir != null && Files.exists(skillDir) && Files.isDirectory(skillDir)) {
            // 1. 显式配置的 skillDir
            resolved = resolveFromDirectory(entity, skillDir, configuredDir, "directory");
        } else {
            // 2. 约定路径 {workspace-root}/{skillName}/
            Path conventionPath = workspaceManager.resolveConventionPath(entity.getName());
            if (Files.exists(conventionPath) && Files.isDirectory(conventionPath)) {
                resolved = resolveFromDirectory(entity, conventionPath, conventionPath.toString(), "convention");
            } else {
                // 3. 数据库 skillContent
                resolved = resolveFromDatabase(entity, configuredDir);
            }
        }

        // 2. 安全扫描
        applySecurity(resolved);

        // 3. 依赖检查
        applyDependencyCheck(resolved);

        // 4. RFC-090 §14.1 — manifest + features 矩阵
        applyManifestAndFeatures(resolved);

        // 5. 综合判定 runtimeAvailable
        resolveRuntimeAvailability(resolved);

        // 6. RFC-042 §2.3 + RFC-090 §14.6 — persist scan outcome and
        //    project manifest_json back to the row so legacy columns
        //    stay in sync.
        persistScanOutcome(entity, resolved);

        return resolved;
    }

    /**
     * Write back the latest scan status / findings JSON / timestamp when
     * they differ from what's already on the row. Keeps the DB in sync
     * without re-writing on every idempotent refresh.
     *
     * <p>Diff-based so a fresh resolve loop across N enabled skills is
     * effectively free when nothing has changed on disk. Errors here are
     * non-fatal — the scan result is already attached to {@code resolved},
     * so the UI will still see it for this request.
     */
    private void persistScanOutcome(SkillEntity entity, ResolvedSkill resolved) {
        if (entity == null || entity.getId() == null) return;

        String newStatus = deriveScanStatus(resolved);
        String newJson = serializeFindings(resolved.getSecurityFindings());
        String newManifestJson = serializeManifest(resolved.getManifest());

        boolean statusChanged = !Objects.equals(entity.getSecurityScanStatus(), newStatus);
        boolean findingsChanged = !Objects.equals(entity.getSecurityScanResult(), newJson);
        boolean manifestChanged = !Objects.equals(entity.getManifestJson(), newManifestJson);

        // RFC-090 §14.6 — column projection from manifest (SoT).
        // Snapshot pre-projection values so we know which legacy columns
        // need a row-level update. Skipped when the manifest is null.
        //
        // Icon is a special case: the UI lets users pick a custom icon
        // (emoji / pixelarticons / URL) per skill. If we unconditionally
        // re-project the manifest icon every resolve, the user's pick
        // gets silently reverted as soon as the runtime cache refreshes.
        // So we only seed the icon from manifest when the row's icon is
        // empty — meaning user-set icons stick, and clearing the icon
        // ("no icon" in the picker) explicitly opts back into the
        // manifest default on the next resolve.
        String newSkillType = entity.getSkillType();
        String newIcon = entity.getIcon();
        String newVersion = entity.getVersion();
        String newAuthor = entity.getAuthor();
        if (resolved.getManifest() != null) {
            SkillManifest m = resolved.getManifest();
            if (m.getType() != null && !m.getType().isBlank()) newSkillType = m.getType();
            boolean rowIconBlank = entity.getIcon() == null || entity.getIcon().isBlank();
            if (rowIconBlank && m.getIcon() != null && !m.getIcon().isBlank()) {
                newIcon = m.getIcon();
            }
            if (m.getVersion() != null && !m.getVersion().isBlank()) newVersion = m.getVersion();
            if (m.getAuthor() != null && !m.getAuthor().isBlank()) newAuthor = m.getAuthor();
        }
        boolean projectionChanged = !Objects.equals(entity.getSkillType(), newSkillType)
                || !Objects.equals(entity.getIcon(), newIcon)
                || !Objects.equals(entity.getVersion(), newVersion)
                || !Objects.equals(entity.getAuthor(), newAuthor);

        if (!statusChanged && !findingsChanged && !manifestChanged && !projectionChanged) {
            return;
        }

        try {
            // Whitelist via LambdaUpdateWrapper (issue #45): SkillEntity has
            // several @TableField(updateStrategy = FieldStrategy.ALWAYS)
            // columns (skill_content, config_json, source_code, name_zh,
            // name_en, security_scan_result, manifest_json). Calling updateById
            // with a partial entity would tell MyBatis Plus to write NULL to
            // every ALWAYS column not set on the partial — wiping the imported
            // skill content on every scan write-back.
            LocalDateTime now = LocalDateTime.now();
            LambdaUpdateWrapper<SkillEntity> wrapper = new LambdaUpdateWrapper<SkillEntity>()
                    .eq(SkillEntity::getId, entity.getId());
            if (statusChanged) wrapper.set(SkillEntity::getSecurityScanStatus, newStatus);
            if (findingsChanged) wrapper.set(SkillEntity::getSecurityScanResult, newJson);
            if (manifestChanged) wrapper.set(SkillEntity::getManifestJson, newManifestJson);
            if (projectionChanged) {
                wrapper.set(SkillEntity::getSkillType, newSkillType)
                        .set(SkillEntity::getIcon, newIcon)
                        .set(SkillEntity::getVersion, newVersion)
                        .set(SkillEntity::getAuthor, newAuthor);
            }
            // Always update scan time when something changed so we have a
            // freshness indicator for the admin UI.
            if (statusChanged || findingsChanged) wrapper.set(SkillEntity::getSecurityScanTime, now);
            skillMapper.update(null, wrapper);
            // Keep the in-memory entity coherent with the DB so the next
            // resolve in the same tick doesn't redundantly write again.
            if (statusChanged) entity.setSecurityScanStatus(newStatus);
            if (findingsChanged) entity.setSecurityScanResult(newJson);
            if (statusChanged || findingsChanged) entity.setSecurityScanTime(now);
            if (manifestChanged) entity.setManifestJson(newManifestJson);
            if (projectionChanged) {
                entity.setSkillType(newSkillType);
                entity.setIcon(newIcon);
                entity.setVersion(newVersion);
                entity.setAuthor(newAuthor);
            }
        } catch (Exception e) {
            log.warn("Failed to persist scan outcome for skill '{}': {}", entity.getName(), e.getMessage());
        }
    }

    private String serializeManifest(SkillManifest manifest) {
        if (manifest == null) return null;
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            log.debug("Failed to serialize manifest: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Collapse the resolver's rich security state back into the {@code
     * PASSED / FAILED / null} tri-state used on the row.
     */
    private String deriveScanStatus(ResolvedSkill resolved) {
        if (resolved.isSecurityBlocked()) return "FAILED";
        List<ResolvedSkill.SecurityFinding> findings = resolved.getSecurityFindings();
        if (findings != null && !findings.isEmpty()) return "PASSED"; // scanned and found non-blocking issues
        // No block, no findings — treat as scanned-clean (still PASSED so
        // listEnabledSkills() doesn't treat it as never-scanned).
        return "PASSED";
    }

    private String serializeFindings(List<ResolvedSkill.SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (Exception e) {
            log.debug("Failed to serialize findings: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 阶段 1：内容解析 ====================

    private ResolvedSkill resolveFromDirectory(SkillEntity entity, Path skillDir, String configuredDir, String source) {
        Path skillMd = skillDir.resolve("SKILL.md");

        String content = "";
        String description = entity.getDescription();

        if (Files.exists(skillMd)) {
            try {
                content = Files.readString(skillMd);
                SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
                if (!parsed.getDescription().isBlank()) {
                    description = parsed.getDescription();
                }
            } catch (Exception e) {
                log.warn("Failed to read SKILL.md from {}: {}", skillMd, e.getMessage());
            }
        }

        Map<String, Object> references = directoryScanner.buildDirectoryTree(skillDir.resolve("references"));
        Map<String, Object> scripts = directoryScanner.buildDirectoryTree(skillDir.resolve("scripts"));

        return ResolvedSkill.builder()
            .id(entity.getId())
            .name(entity.getName())
            .description(description)
            .content(content)
            .source(source)
            .skillDir(skillDir)
            .configuredSkillDir(configuredDir)
            .runtimeAvailable(true) // 暂定，后续安全/依赖检查可能改写
            .resolutionError(null)
            .references(references)
            .scripts(scripts)
            .enabled(Boolean.TRUE.equals(entity.getEnabled()))
            .icon(entity.getIcon())
            .builtin(Boolean.TRUE.equals(entity.getBuiltin()))
            .workspaceId(entity.getWorkspaceId())
            .createTime(entity.getCreateTime())
            .build();
    }

    private ResolvedSkill resolveFromDatabase(SkillEntity entity, String configuredDir) {
        String content = entity.getSkillContent() != null ? entity.getSkillContent() : "";
        String description = entity.getDescription();

        if (!content.isBlank()) {
            SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
            if (!parsed.getDescription().isBlank()) {
                description = parsed.getDescription();
            }
        }

        boolean hasContent = !content.isBlank();
        String error = null;
        if (configuredDir != null) {
            error = "Configured skillDir not found: " + configuredDir;
            if (hasContent) {
                error += " (fallback to database skillContent)";
            }
        } else if (!hasContent) {
            error = "No skillDir configured and no skillContent available";
        }

        return ResolvedSkill.builder()
            .id(entity.getId())
            .name(entity.getName())
            .description(description)
            .content(content)
            .source("database")
            .skillDir(null)
            .configuredSkillDir(configuredDir)
            .runtimeAvailable(hasContent || (description != null && !description.isBlank()))
            .resolutionError(error)
            .references(Map.of())
            .scripts(Map.of())
            .enabled(Boolean.TRUE.equals(entity.getEnabled()))
            .icon(entity.getIcon())
            .builtin(Boolean.TRUE.equals(entity.getBuiltin()))
            .workspaceId(entity.getWorkspaceId())
            .createTime(entity.getCreateTime())
            .build();
    }

    // ==================== 阶段 2：安全扫描 ====================

    private void applySecurity(ResolvedSkill resolved) {
        // Builtin skills come from classpath/jar — they're version-controlled
        // upstream and the resolver flow already maps blocked findings to
        // `trustedBuiltin` (warn but don't block). Running the full scanner
        // on every refresh is pure overhead; with 30+ shipped skills this
        // dominates the refresh budget. Mark them trusted up-front and
        // skip — the persisted scan_result on mate_skill (written by the
        // initial resolve) keeps the UI's audit trail intact.
        if (resolved.isBuiltin()) {
            resolved.setSecurityBlocked(false);
            resolved.setSecuritySummary("Trusted builtin (runtime scan skipped)");
            resolved.setSecurityWarnings(List.of());
            return;
        }
        try {
            // Content-hash cache: refreshes on unchanged SKILL.md content
            // skip the (potentially expensive) scanner. Hash drifts → fall
            // through to a fresh scan and replace the cached entry.
            String contentHash = sha256(resolved.getContent());
            CachedScanOutcome cached = resolved.getId() != null
                    ? securityScanCache.get(resolved.getId())
                    : null;
            SkillValidationResult result;
            if (cached != null && cached.contentHash().equals(contentHash)) {
                result = cached.result();
            } else {
                result = securityService.validate(resolved);
                if (resolved.getId() != null) {
                    securityScanCache.put(resolved.getId(), new CachedScanOutcome(contentHash, result));
                }
            }
            boolean trustedBuiltin = resolved.isBuiltin() && result.isBlocked();
            resolved.setSecurityBlocked(result.isBlocked() && !trustedBuiltin);
            resolved.setSecuritySeverity(result.getMaxSeverity() != null ? result.getMaxSeverity().name() : null);
            if (trustedBuiltin) {
                resolved.setSecuritySummary("Builtin skill trusted: " + result.getSummary());
            } else {
                resolved.setSecuritySummary(result.getSummary());
            }
            resolved.setSecurityWarnings(result.getWarnings());

            // 转换 findings 为 JSON 友好格式
            if (result.getFindings() != null && !result.getFindings().isEmpty()) {
                List<ResolvedSkill.SecurityFinding> secFindings = result.getFindings().stream()
                    .map(f -> ResolvedSkill.SecurityFinding.builder()
                        .ruleId(f.getRuleId())
                        .severity(f.getSeverity() != null ? f.getSeverity().name() : null)
                        .category(f.getCategory())
                        .title(f.getTitle())
                        .description(f.getDescription())
                        .filePath(f.getFilePath())
                        .lineNumber(f.getLineNumber())
                        .snippet(f.getSnippet())
                        .remediation(f.getRemediation())
                        .build())
                    .collect(Collectors.toList());
                resolved.setSecurityFindings(secFindings);
            }

            if (trustedBuiltin) {
                log.warn("Builtin skill '{}' bypassed security block: {}", resolved.getName(), result.getSummary());
            } else if (result.isBlocked()) {
                log.warn("Skill '{}' blocked by security scan: {}", resolved.getName(), result.getSummary());
            } else if (result.getFindings() != null && !result.getFindings().isEmpty()) {
                log.info("Skill '{}' security scan: {} finding(s)", resolved.getName(), result.getFindings().size());
            }
        } catch (Exception e) {
            log.error("Security scan failed for skill '{}': {}", resolved.getName(), e.getMessage());
            resolved.setSecurityWarnings(List.of("Security scan error: " + e.getMessage()));
        }
    }

    // ==================== 阶段 3：依赖检查 ====================

    private void applyDependencyCheck(ResolvedSkill resolved) {
        try {
            // 解析 frontmatter 获取依赖声明
            String content = resolved.getContent();
            if (content == null || content.isBlank()) {
                resolved.setDependencyReady(true);
                resolved.setDependencySummary("No dependencies declared");
                return;
            }

            SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
            SkillFrontmatterParser.SkillDependencies deps = parsed.getDependencies();
            List<String> platforms = parsed.getPlatforms();

            if ((deps == null || deps.isEmpty()) && (platforms == null || platforms.isEmpty())) {
                resolved.setDependencyReady(true);
                resolved.setDependencySummary("No dependencies declared");
                return;
            }

            SkillDependencyChecker.DependencyCheckResult result =
                dependencyChecker.check(deps, platforms, resolved.getName());

            resolved.setDependencyReady(result.isSatisfied());
            resolved.setMissingDependencies(result.getMissing());
            resolved.setDependencySummary(result.getSummary());

            if (!result.isSatisfied()) {
                log.info("Skill '{}' dependencies not satisfied: {}", resolved.getName(), result.getSummary());
            }
        } catch (Exception e) {
            log.error("Dependency check failed for skill '{}': {}", resolved.getName(), e.getMessage());
            resolved.setDependencyReady(true); // 检查失败不阻断
            resolved.setDependencySummary("Dependency check error: " + e.getMessage());
        }
    }

    // ==================== 阶段 3.5：manifest + features (RFC-090 §14.1 / §14.6) ====================

    /**
     * Parse the SKILL.md frontmatter into a typed manifest and evaluate
     * the {@code features[]} matrix.
     *
     * <p>Backward compat: when the manifest declares no {@code features[]},
     * we synthesize a single feature {@code "default"} that inherits the
     * top-level requires + platforms — giving legacy skills the same status
     * (READY iff all top-level requirements are satisfied) without code
     * changes upstream.
     */
    private void applyManifestAndFeatures(ResolvedSkill resolved) {
        try {
            String content = resolved.getContent();
            SkillManifest manifest = manifestParser.parse(content);
            if (manifest == null) {
                // No frontmatter at all: leave manifest null and let
                // legacy callers continue using dependencyReady. Also
                // make sure no wrapper tools linger from a prior shape.
                deregisterSkillWrappers(resolved.getId());
                return;
            }
            resolved.setManifest(manifest);

            // RFC-090 §14.4 — for knowledge skills, resolve bind_kb
            // and (re)register skill-scoped wrapper tools. This must
            // happen *before* the feature evaluation below so the
            // wrapper names are part of allowedTools when
            // getEffectiveAllowedTools() runs.
            applyKnowledgeWrappers(resolved, manifest);

            // RFC-090 Phase 7b — type=acp gets its own wrapper that
            // delegates to the configured ACP endpoint. Parallel
            // structure to knowledge wrappers; tracked under the same
            // registeredWrappers map so deregistration covers both.
            applyAcpWrappers(resolved, manifest);

            // Build requirement lookup for feature checks.
            Map<String, SkillManifest.RequirementDef> reqByKey = new LinkedHashMap<>();
            for (SkillManifest.RequirementDef r : manifest.getRequires()) {
                if (r.getKey() != null) reqByKey.put(r.getKey(), r);
            }

            List<SkillManifest.FeatureDef> features = manifest.getFeatures();
            List<SkillManifest.FeatureDef> effectiveFeatures;
            if (features == null || features.isEmpty()) {
                // Synthesized "default" feature carrying top-level requires
                // + platforms so legacy skills get the same status path.
                List<String> defaultRequires = new ArrayList<>();
                for (SkillManifest.RequirementDef r : manifest.getRequires()) {
                    if (r.getKey() != null && !r.isOptional()) defaultRequires.add(r.getKey());
                }
                effectiveFeatures = List.of(SkillManifest.FeatureDef.builder()
                        .id("default")
                        .label(manifest.getName() != null ? manifest.getName() : "default")
                        .requires(defaultRequires)
                        .platforms(manifest.getPlatforms())
                        .build());
                // Write the synthesized feature back so the UI's Features
                // tab and the LLM's prompt enhancement both see one row
                // instead of "no features declared". Functionally
                // equivalent to the no-features-declared case, but
                // observable in the manifest_json projection.
                manifest.setFeatures(effectiveFeatures);
            } else {
                effectiveFeatures = features;
            }

            Map<String, String> statuses = new LinkedHashMap<>();
            Set<String> active = new LinkedHashSet<>();
            for (SkillManifest.FeatureDef f : effectiveFeatures) {
                if (f.getId() == null || f.getId().isBlank()) continue;
                SkillDependencyChecker.FeatureCheckResult res = dependencyChecker.checkFeature(f, reqByKey);
                statuses.put(f.getId(), res.getStatus());
                if ("READY".equals(res.getStatus())) active.add(f.getId());
            }
            resolved.setFeatureStatuses(statuses);
            resolved.setActiveFeatures(active);
        } catch (Exception e) {
            log.warn("Manifest/feature evaluation failed for skill '{}': {}",
                    resolved.getName(), e.getMessage());
        }
    }

    /**
     * RFC-090 §14.4 — register / refresh / deregister wrapper tools
     * for a single knowledge skill.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>{@code type != knowledge} or skill disabled → deregister
     *       any prior wrappers, leave {@code allowedTools} alone.</li>
     *   <li>{@code bind_kb} unresolvable → deregister + emit warning;
     *       feature evaluation will then mark the skill SETUP_NEEDED.</li>
     *   <li>Otherwise: deregister prior set, register fresh, append
     *       wrapper names to {@code manifest.allowedTools} so
     *       {@code getEffectiveAllowedTools()} surfaces them.</li>
     * </ul>
     *
     * <p>Wrappers are registered as plugin tools with an availability
     * supplier that returns true while the entity stays enabled. That
     * way a toggle-off doesn't require an explicit re-resolve to hide
     * the tools — the supplier is evaluated each time the agent tool
     * set is built.
     */
    private void applyKnowledgeWrappers(ResolvedSkill resolved, SkillManifest manifest) {
        boolean isKnowledge = "knowledge".equalsIgnoreCase(manifest.getType())
                && manifest.getKnowledge() != null
                && manifest.getKnowledge().getBindKb() != null
                && !manifest.getKnowledge().getBindKb().isBlank();
        if (!isKnowledge || !resolved.isEnabled()) {
            deregisterSkillWrappers(resolved.getId());
            return;
        }

        Long kbId = manifest.getKnowledge().getBoundKbId();
        if (kbId == null) {
            kbId = wikiWrapperFactory.resolveKbId(manifest.getKnowledge().getBindKb());
            if (kbId == null) {
                String slug = manifest.getKnowledge().getBindKb();
                log.warn("Skill '{}' has type=knowledge but bind_kb '{}' did not resolve to a KB",
                        resolved.getName(), slug);
                deregisterSkillWrappers(resolved.getId());
                markBindingFailure(resolved,
                        "kb:" + slug,
                        "Knowledge skill bind_kb '" + slug + "' did not resolve to any Wiki KB");
                return;
            }
            manifest.getKnowledge().setBoundKbId(kbId);
        }

        // Fresh build to keep wrapper state in lockstep with the
        // current kbId — if the user repointed bind_kb, the old
        // wrappers must go.
        deregisterSkillWrappers(resolved.getId());

        java.util.List<ToolCallback> wrappers = wikiWrapperFactory.buildWrappers(manifest, kbId);
        if (wrappers.isEmpty()) {
            return;
        }
        java.util.Set<String> registered = new java.util.LinkedHashSet<>();
        Long entityId = resolved.getId();
        for (ToolCallback cb : wrappers) {
            String name = cb.getToolDefinition().name();
            registered.add(name);
            // Availability supplier: skill must still resolve to an
            // enabled row. Worst case: a disable racing with a tool
            // call simply returns "tool unavailable" for one turn.
            toolRegistry.registerPluginTool(cb, () ->
                    entityId != null && resolved.isEnabled());
        }
        if (entityId != null) {
            registeredWrappers.put(entityId, registered);
        }

        // Make wrapper names visible to ResolvedSkill.getEffectiveAllowedTools.
        // We *append* rather than replace so a knowledge skill can also
        // declare extra allowed-tools alongside the auto-generated KB
        // surface (Q9 in §10.2 答 ✅).
        java.util.List<String> mergedAllowed = new java.util.ArrayList<>(
                manifest.getAllowedTools() == null ? java.util.List.of() : manifest.getAllowedTools());
        for (String wrapperName : wikiWrapperFactory.wrapperNames(manifest)) {
            if (!mergedAllowed.contains(wrapperName)) mergedAllowed.add(wrapperName);
        }
        manifest.setAllowedTools(mergedAllowed);
    }

    /**
     * RFC-090 review #2 — when a knowledge / acp skill's external
     * binding (bind_kb / endpoint) cannot be resolved, downgrade the
     * resolved view so {@code passesActiveGate} fails. Without this,
     * the synthesized default feature still evaluates READY (because
     * the unresolved binding isn't expressed as a manifest requirement
     * the dependency checker can fail), the skill enters the active
     * set, and the LLM advertises tools it can never actually use.
     *
     * <p>Triple-belt-and-braces:
     * <ol>
     *   <li>{@code missingDependencies} populated for the UI.</li>
     *   <li>{@code dependencyReady=false} so legacy gate path also fails.</li>
     *   <li>{@code runtimeAvailable=false} so the manifest-aware path
     *       in {@code passesActiveGate} fails too — even though the
     *       feature would otherwise be READY.</li>
     * </ol>
     * The later {@code resolveRuntimeAvailability} step won't undo
     * these because it only flips {@code runtimeAvailable=false}, it
     * never flips it back to true.
     */
    private void markBindingFailure(ResolvedSkill resolved, String missingKey, String summary) {
        resolved.setMissingDependencies(java.util.List.of(missingKey));
        resolved.setDependencyReady(false);
        resolved.setDependencySummary(summary);
        resolved.setRuntimeAvailable(false);
        if (resolved.getResolutionError() == null) {
            resolved.setResolutionError(summary);
        }
    }

    /**
     * RFC-090 review #3 — explicit deregistration entry point. Called
     * from {@code SkillService.toggleSkill (disable)} / uninstall /
     * hardDelete so wrapper tools don't outlive the skill's lifecycle.
     *
     * <p>Without this, the {@code availabilityCheck} supplier closes
     * over the {@link ResolvedSkill} captured at registration time
     * and keeps returning {@code enabled=true} forever. Stale wrapper
     * advertisements survive a disable/uninstall.
     */
    public void deregisterSkillWrappers(Long skillId) {
        if (skillId == null) return;
        // Evict the security-scan cache entry too — a re-installed skill
        // with the same id but different content needs a fresh scan, and
        // a permanently-deleted skill should free the slot.
        securityScanCache.remove(skillId);
        java.util.Set<String> previous = registeredWrappers.remove(skillId);
        if (previous == null || previous.isEmpty()) return;
        for (String name : previous) {
            try {
                toolRegistry.unregisterPluginTool(name);
            } catch (Exception e) {
                log.debug("unregister wrapper {} failed: {}", name, e.getMessage());
            }
        }
        log.info("Deregistered {} wrapper tool(s) for skill id={}", previous.size(), skillId);
    }

    /** Hex-encoded SHA-256 of the input string (UTF-8). Empty/null → "". */
    private static String sha256(String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 is mandatory in the JRE — should never throw.
            // Fall back to identity so the cache is just always-miss.
            return input;
        }
    }

    /**
     * RFC-090 Phase 7b — register / refresh / deregister wrapper tools
     * for a single ACP skill. Mirrors {@link #applyKnowledgeWrappers}.
     *
     * <p>Endpoint resolution semantics match the knowledge path:
     * <ul>
     *   <li>{@code type != acp} or skill disabled → deregister.</li>
     *   <li>{@code endpoint} unresolvable → deregister + add a missing-
     *       dependency hint so the UI shows SETUP_NEEDED.</li>
     *   <li>Otherwise: register one wrapper, append name to
     *       {@code manifest.allowedTools} so the LLM advertisement
     *       picks it up.</li>
     * </ul>
     */
    private void applyAcpWrappers(ResolvedSkill resolved, SkillManifest manifest) {
        boolean isAcp = "acp".equalsIgnoreCase(manifest.getType())
                && manifest.getAcp() != null
                && manifest.getAcp().getEndpoint() != null
                && !manifest.getAcp().getEndpoint().isBlank();
        if (!isAcp || !resolved.isEnabled()) {
            // applyKnowledgeWrappers already cleared registrations for
            // type=knowledge; we don't double-clear when this branch
            // also runs for the same skill — type is single-valued so
            // only one branch ever holds wrappers at a time.
            return;
        }

        Long endpointId = manifest.getAcp().getResolvedEndpointId();
        if (endpointId == null) {
            endpointId = acpWrapperFactory.resolveEndpointId(manifest.getAcp().getEndpoint());
            if (endpointId == null) {
                String slug = manifest.getAcp().getEndpoint();
                log.warn("Skill '{}' type=acp but endpoint '{}' did not resolve",
                        resolved.getName(), slug);
                deregisterSkillWrappers(resolved.getId());
                markBindingFailure(resolved,
                        "acp:" + slug,
                        "ACP skill endpoint '" + slug + "' did not resolve to any registered ACP endpoint");
                return;
            }
            manifest.getAcp().setResolvedEndpointId(endpointId);
        }

        // Fresh build: drop any prior knowledge-wrapper registration
        // (impossible in practice since type is single-valued, but
        // makes the lifecycle safe across edits where the user flips
        // type from knowledge to acp).
        deregisterSkillWrappers(resolved.getId());

        java.util.List<ToolCallback> wrappers = acpWrapperFactory.buildWrappers(manifest);
        if (wrappers.isEmpty()) return;
        java.util.Set<String> registered = new java.util.LinkedHashSet<>();
        Long entityId = resolved.getId();
        for (ToolCallback cb : wrappers) {
            String name = cb.getToolDefinition().name();
            registered.add(name);
            toolRegistry.registerPluginTool(cb, () ->
                    entityId != null && resolved.isEnabled());
        }
        if (entityId != null) {
            registeredWrappers.put(entityId, registered);
        }

        // Append wrapper names to allowedTools so getEffectiveAllowedTools
        // surfaces them like any other manifest-declared tool.
        java.util.List<String> mergedAllowed = new java.util.ArrayList<>(
                manifest.getAllowedTools() == null ? java.util.List.of() : manifest.getAllowedTools());
        for (String wrapperName : acpWrapperFactory.wrapperNames(manifest)) {
            if (!mergedAllowed.contains(wrapperName)) mergedAllowed.add(wrapperName);
        }
        manifest.setAllowedTools(mergedAllowed);
    }

    // ==================== 阶段 4：综合判定 ====================

    private void resolveRuntimeAvailability(ResolvedSkill resolved) {
        // 安全阻断 → 不可用
        if (resolved.isSecurityBlocked()) {
            resolved.setRuntimeAvailable(false);
            if (resolved.getResolutionError() == null) {
                resolved.setResolutionError("Security blocked: " + resolved.getSecuritySummary());
            }
            return;
        }

        // 依赖不满足 → 不可用
        if (!resolved.isDependencyReady()) {
            resolved.setRuntimeAvailable(false);
            if (resolved.getResolutionError() == null) {
                resolved.setResolutionError("Dependencies missing: " + resolved.getDependencySummary());
            }
        }

        // 其他情况保留原有 runtimeAvailable 判定
    }

    // ==================== 工具方法 ====================

    private String extractSkillDirString(SkillEntity entity) {
        String configJson = entity.getConfigJson();
        if (configJson == null || configJson.isBlank()) {
            return null;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);

            String pathStr = null;
            if (config.containsKey("skillDir")) {
                pathStr = config.get("skillDir").toString();
            } else if (config.containsKey("path")) {
                pathStr = config.get("path").toString();
            } else if (config.containsKey("directory")) {
                pathStr = config.get("directory").toString();
            }

            if (pathStr != null && !pathStr.isBlank()) {
                return pathStr;
            }
        } catch (Exception e) {
            log.debug("Failed to parse configJson for skill {}: {}", entity.getName(), e.getMessage());
        }

        return null;
    }
}
