package vip.mate.skill.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.lessons.SkillLessonsService;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.usage.SkillUsageService;

import vip.mate.skill.workspace.SkillWorkspaceEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 技能运行时服务
 * 管理 active skills 运行时视图，提供缓存和刷新机制
 */
@Slf4j
@Service
public class SkillRuntimeService {

    private final SkillService skillService;
    private final SkillPackageResolver packageResolver;
    /**
     * {@code @Lazy} — SkillLessonsService depends on SkillWorkspaceManager,
     * which is constructed early; the lazy proxy avoids a chicken-and-egg
     * cycle when the runtime service initializes alongside the skill
     * service stack. Using setter-style injection through the
     * constructor below.
     */
    private final SkillLessonsService lessonsService;
    /**
     * RFC-090 §3.2 / §10.2 Q2 — MCP-server → virtual-skill bridge.
     * {@code @Lazy} because the bridge depends on McpClientManager
     * which boots later in the lifecycle.
     */
    private final McpSkillBridge mcpSkillBridge;
    /**
     * RFC-090 §3.2 (parallel) — ACP-endpoint → virtual-skill bridge.
     * Same {@code @Lazy} treatment as the MCP bridge.
     */
    private final AcpSkillBridge acpSkillBridge;
    private final SkillUsageService usageService;

    @Autowired
    public SkillRuntimeService(SkillService skillService,
                               SkillPackageResolver packageResolver,
                               @Lazy SkillLessonsService lessonsService,
                               @Lazy McpSkillBridge mcpSkillBridge,
                               @Lazy AcpSkillBridge acpSkillBridge,
                               @Lazy SkillUsageService usageService) {
        this.skillService = skillService;
        this.packageResolver = packageResolver;
        this.lessonsService = lessonsService;
        this.mcpSkillBridge = mcpSkillBridge;
        this.acpSkillBridge = acpSkillBridge;
        this.usageService = usageService;
    }

    // 缓存已解析的 active skills（5分钟过期）
    private final Cache<String, List<ResolvedSkill>> activeSkillsCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(10)
        .build();

    private static final String CACHE_KEY = "active_skills";

    /**
     * Debounce window for {@link #onWorkspaceEvent}. Startup typically
     * fires one event per bundled skill (30+ in a row), and there's
     * nothing useful to do until the whole batch settles. 500 ms is
     * long enough to swallow the burst without making admin re-syncs
     * feel laggy.
     */
    private static final long REFRESH_DEBOUNCE_MS = 500;

    private final ScheduledExecutorService refreshScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "skill-refresh-debouncer");
                t.setDaemon(true);
                return t;
            });

    /** Most recent pending refresh future — atomically swapped on each
     *  event so the previous one can be cancelled. */
    private final AtomicReference<ScheduledFuture<?>> pendingRefresh = new AtomicReference<>();

    @PostConstruct
    public void init() {
        log.info("SkillRuntimeService initialized");
        // 设置反向引用，避免循环依赖
        skillService.setRuntimeService(this);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 延迟到 ApplicationReady 事件触发，确保 SQL 初始化脚本已执行完毕
        refreshActiveSkills();
    }

    @EventListener(SkillWorkspaceEvent.class)
    public void onWorkspaceEvent(SkillWorkspaceEvent event) {
        // Coalesce bursts of workspace events (every bundled-skill sync at
        // startup fires one) into a single refresh. Without debounce, a
        // 30-skill startup triggered 30 sequential refreshActiveSkills()
        // calls — each running the whole resolve+scan loop. With 500 ms
        // debounce it collapses to one.
        log.debug("Workspace event: {} {} (refresh scheduled in {}ms)",
                event.type(), event.skillName(), REFRESH_DEBOUNCE_MS);
        ScheduledFuture<?> previous = pendingRefresh.get();
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
        ScheduledFuture<?> task = refreshScheduler.schedule(() -> {
            try {
                refreshActiveSkills();
            } catch (Exception e) {
                log.warn("Debounced refresh failed: {}", e.getMessage());
            }
        }, REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingRefresh.set(task);
    }

    @PreDestroy
    public void shutdown() {
        // Drain any pending refresh so JVM shutdown doesn't hang on the
        // daemon thread, even though it's marked daemon and would die anyway.
        ScheduledFuture<?> pending = pendingRefresh.getAndSet(null);
        if (pending != null) pending.cancel(true);
        refreshScheduler.shutdown();
        try {
            if (!refreshScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                refreshScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            refreshScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * RFC-090 §14.1 — single source of truth for "is this resolved
     * skill currently exposable to an agent". Both the global
     * {@link #refreshActiveSkills()} cache and the per-agent
     * {@link #buildSkillPromptEnhancement(Set)} branch route through
     * here so the two views can never disagree.
     *
     * <p>Rules:
     * <ol>
     *   <li>row enabled, runtime resolved, not security-blocked — required</li>
     *   <li>manifest present → at least one feature READY ({@code hasAnyActiveFeature})</li>
     *   <li>manifest absent (legacy SKILL.md) → fall back to
     *       {@code dependencyReady} so old skills behave unchanged</li>
     * </ol>
     */
    public static boolean passesActiveGate(ResolvedSkill s) {
        if (s == null) return false;
        if (!s.isEnabled() || !s.isRuntimeAvailable() || s.isSecurityBlocked()) return false;
        if (s.getManifest() == null) return s.isDependencyReady();
        return s.hasAnyActiveFeature();
    }

    /**
     * 获取当前启用的技能列表（运行时视图）
     */
    public List<ResolvedSkill> getActiveSkills() {
        List<ResolvedSkill> cached = activeSkillsCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        return refreshActiveSkills();
    }

    /**
     * 刷新 active skills 缓存
     * 进入 active set 的 skill 必须同时满足：
     * 1. enabled == true
     * 2. runtimeAvailable == true
     * 3. securityBlocked == false
     * 4. dependencyReady == true
     */
    public List<ResolvedSkill> refreshActiveSkills() {
        List<SkillEntity> enabledSkills = skillService.listEnabledSkills();

        List<ResolvedSkill> resolved = enabledSkills.stream()
            .map(packageResolver::resolve)
            .filter(SkillRuntimeService::passesActiveGate)
            .collect(Collectors.toList());
        // Track real skill names so a same-named bridged virtual skill is
        // suppressed (real wins). Without this, a real SKILL.md packaged
        // alongside a same-name MCP/ACP server produces two cards on the
        // Skills page.
        Set<String> realNames = resolved.stream()
            .map(ResolvedSkill::getName)
            .collect(Collectors.toSet());
        try {
            // MCP-derived virtual skills go through the same active gate
            // so a disconnected MCP server doesn't pollute the prompt
            // enhancement. Same-name virtuals are suppressed by the real
            // skill above.
            for (ResolvedSkill virt : mcpSkillBridge.listMcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                if (passesActiveGate(virt)) resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("MCP skill bridge active merge failed: {}", e.getMessage());
        }
        try {
            // ACP-derived virtual skills. Same dedup as MCP.
            for (ResolvedSkill virt : acpSkillBridge.listAcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                if (passesActiveGate(virt)) resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("ACP skill bridge active merge failed: {}", e.getMessage());
        }

        activeSkillsCache.put(CACHE_KEY, resolved);
        log.info("Refreshed active skills: {} enabled", resolved.size());

        return resolved;
    }

    /**
     * 解析所有技能的运行时状态（管理页面使用，包含 disabled 和 error 信息）
     *
     * <p>RFC-090 §3.2 — appends virtual MCP-derived skills so the Skills
     * page can render MCP servers as first-class skill cards. Real
     * skills resolve through the full pipeline; virtual ones come
     * pre-built from {@link McpSkillBridge}.
     */
    public List<ResolvedSkill> resolveAllSkillsStatus() {
        List<SkillEntity> allSkills = skillService.listSkills();
        List<ResolvedSkill> resolved = allSkills.stream()
            .map(packageResolver::resolve)
            .collect(Collectors.toList());
        // Same dedup-by-name as refreshActiveSkills(): a real skill with
        // the same name as a bridged virtual one suppresses the virtual,
        // so the Skills admin page never shows two cards for the same name.
        Set<String> realNames = resolved.stream()
            .map(ResolvedSkill::getName)
            .collect(Collectors.toSet());
        try {
            for (ResolvedSkill virt : mcpSkillBridge.listMcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("MCP skill bridge merge failed: {}", e.getMessage());
        }
        try {
            for (ResolvedSkill virt : acpSkillBridge.listAcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("ACP skill bridge merge failed: {}", e.getMessage());
        }
        return resolved;
    }

    /**
     * Rescan one skill on demand (RFC-042 §2.3.4) — runs the full resolver
     * pipeline (content + security + dependency), which writes the updated
     * scan result to DB as a side-effect, and then invalidates the active
     * skills cache so subsequent reads reflect the new status.
     */
    public ResolvedSkill rescanSingle(SkillEntity skill) {
        ResolvedSkill resolved = packageResolver.resolve(skill);
        activeSkillsCache.invalidateAll();
        log.info("Rescanned skill '{}' (id={}): status={}, blocked={}",
                skill.getName(), skill.getId(),
                skill.getSecurityScanStatus(), resolved.isSecurityBlocked());
        return resolved;
    }

    /**
     * RFC-090 review #3 — explicit lifecycle hook so SkillService
     * can deregister wrapper tools without poking at the resolver
     * directly. Safe to call for skill ids that never had wrappers.
     */
    public void deregisterSkillWrappers(Long skillId) {
        try {
            packageResolver.deregisterSkillWrappers(skillId);
        } catch (Exception e) {
            log.warn("Failed to deregister wrappers for skill {}: {}", skillId, e.getMessage());
        }
    }

    /**
     * 根据名称查找 active skill
     */
    public ResolvedSkill findActiveSkill(String name) {
        return getActiveSkills().stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * 构建技能 prompt 增强片段（全局，向后兼容）
     */
    public String buildSkillPromptEnhancement() {
        return buildSkillPromptEnhancement(null);
    }

    /**
     * 构建技能 prompt 增强片段（支持 per-agent 过滤）
     *
     * @param boundSkillIds Agent 绑定的 skill ID 集合。null 表示使用全局默认（无绑定）。
     *                      非 null 时仅包含指定 ID 的 skill。
     */
    public String buildSkillPromptEnhancement(Set<Long> boundSkillIds) {
        return buildSkillPromptEnhancement(boundSkillIds, null, null, null);
    }

    /**
     * 构建技能目录提示片段。
     *
     * @param boundSkillIds Agent 绑定的 skill ID 集合。null 表示使用全局默认（无绑定）。
     * @param effectiveToolNames 当前 agent 可见的工具名集合。null 表示不按 agent 绑定限制过滤。
     * @param maxInputTokens 当前模型最大输入窗口，用于控制目录大小。
     */
    public String buildSkillPromptEnhancement(Set<Long> boundSkillIds,
                                              Set<String> effectiveToolNames,
                                              Integer maxInputTokens) {
        return buildSkillPromptEnhancement(boundSkillIds, effectiveToolNames, maxInputTokens, null, null);
    }

    public String buildSkillPromptEnhancement(Set<Long> boundSkillIds,
                                              Set<String> effectiveToolNames,
                                              Integer maxInputTokens,
                                              Long agentId) {
        return buildSkillPromptEnhancement(boundSkillIds, effectiveToolNames, maxInputTokens, agentId, null);
    }

    /**
     * 构建技能目录提示片段（支持按 Agent 工作区隔离）。
     *
     * @param agentWorkspaceId 调用 Agent 的工作区 ID。非 null 时，目录只保留
     *                         内置技能（全局）与该工作区拥有的技能；其他工作区
     *                         的技能不会注入 prompt。null 表示不做工作区过滤
     *                         （调试预览等全局场景）。
     */
    public String buildSkillPromptEnhancement(Set<Long> boundSkillIds,
                                              Set<String> effectiveToolNames,
                                              Integer maxInputTokens,
                                              Long agentId,
                                              Long agentWorkspaceId) {
        List<ResolvedSkill> activeSkills;
        if (boundSkillIds != null) {
            // Per-agent filter: pick the agent's bound subset from the
            // already-merged active set (real + MCP/ACP virtual). Using
            // getActiveSkills() — instead of a fresh
            // skillService.listEnabledSkills() walk — is what makes bound
            // virtual skills surface in the prompt catalog. The earlier
            // implementation only looked at mate_skill rows, so a user
            // who explicitly checked an MCP/ACP card in the agent picker
            // got its tools (via AgentBindingService.getEffectiveToolNames)
            // but lost the corresponding `## Skills` catalog row, which
            // confused the LLM when it tried to dispatch by skill name.
            // Cache-backed get + same passesActiveGate semantics, so this
            // is strictly additive for real skills.
            activeSkills = getActiveSkills().stream()
                    .filter(s -> s.getId() != null && boundSkillIds.contains(s.getId()))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            activeSkills = getActiveSkills();
        }
        // Platform filter — drop skills whose `platforms:` frontmatter
        // names a different OS than the runtime host. apple-notes /
        // findmy etc. are macOS-only; surfacing them on Linux just
        // burns prompt tokens for skills the user can never run.
        // Empty / missing `platforms:` means "all platforms" (the default).
        String currentOs = currentOsCanonical();
        activeSkills = activeSkills.stream()
                .filter(s -> matchesCurrentPlatform(s, currentOs))
                .collect(java.util.stream.Collectors.toList());
        // Workspace filter — a workspace-B agent must not see workspace-A's
        // skills in its catalog. Builtin skills are global; virtual MCP
        // skills carry no workspace (null) and stay globally visible. Only
        // applied when the caller supplies the agent's workspace; the debug
        // preview passes null to keep its global view.
        if (agentWorkspaceId != null) {
            activeSkills = activeSkills.stream()
                    .filter(s -> matchesWorkspace(s, agentWorkspaceId))
                    .collect(java.util.stream.Collectors.toList());
        }
        if (activeSkills.isEmpty()) {
            return "";
        }

        List<ResolvedSkill> visibleSkills = activeSkills.stream()
                .filter(s -> isVisibleWithTools(s, effectiveToolNames))
                .collect(java.util.stream.Collectors.toList());
        if (visibleSkills.isEmpty()) return "";

        Set<Long> boundIds = boundSkillIds == null ? Set.of() : boundSkillIds;
        int maxEntries = promptCatalogEntryLimit(maxInputTokens);
        int descLimit = promptDescriptionLimit(maxInputTokens);
        Set<String> recentNames = usageService.recentLoadedSkillNames(agentId, 8);
        Set<String> frequentNames = usageService.frequentlyLoadedSkillNames(8);
        // Boost freshly installed skills for a short window so a skill the
        // user *just* added is visible in the compact catalog before it has
        // any usage history. Without this, qwen-turbo-style 8-entry budgets
        // hide new skills behind 40+ existing ones, and the LLM tells the
        // user "no such skill" minutes after they uploaded it.
        java.time.LocalDateTime recencyCutoff = java.time.LocalDateTime.now().minus(NEW_SKILL_BOOST_WINDOW);
        List<ResolvedSkill> sorted = SkillCatalogSorter.sortResolved(visibleSkills, SkillCatalogSort.RECOMMENDED)
                .stream()
                .sorted(java.util.Comparator
                        .comparingInt((ResolvedSkill s) -> isRecentlyInstalled(s, recencyCutoff) ? 0 : 1)
                        .thenComparingInt(s -> recentNames.contains(s.getName()) ? 0 : 1)
                        .thenComparingInt(s -> frequentNames.contains(s.getName()) ? 0 : 1)
                        .thenComparing(SkillCatalogSorter.resolvedComparator(SkillCatalogSort.RECOMMENDED)))
                .toList();
        List<ResolvedSkill> pinned = sorted.stream()
                .filter(s -> s.getId() != null && boundIds.contains(s.getId()))
                .toList();
        LinkedHashSet<ResolvedSkill> selected = new LinkedHashSet<>();
        selected.addAll(pinned);
        for (ResolvedSkill skill : sorted) {
            if (selected.size() >= Math.max(maxEntries, pinned.size())) break;
            selected.add(skill);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Skills\n");
        sb.append("This is a compact catalog. If a listed skill matches the task, ");
        sb.append("first call `readSkillFile(skillName=<name>, filePath=\"SKILL.md\")` and follow its instructions. ");
        sb.append("If none of these skills match, call `listAvailableSkills()` to inspect the broader catalog ");
        sb.append("(it accepts `keyword=<part of name>` and `limit=` up to 50 — use them to search by topic ");
        sb.append("when the default page is truncated). ");
        sb.append("If the user names a specific skill that isn't in this table, ");
        sb.append("call `readSkillFile(skillName=\"<exact-name>\", filePath=\"SKILL.md\")` directly — ");
        sb.append("the catalog above is intentionally compact and doesn't list every active skill. ");
        sb.append("Skills are documentation packages — calling a skill name as a tool will fail. ");
        sb.append("Skills with a `scripts/` directory expose `runSkillScript`; SKILL.md will name the script when needed.\n\n");
        sb.append("| Skill | Status | Description |\n");
        sb.append("|-------|--------|-------------|\n");
        for (ResolvedSkill skill : selected) {
            sb.append("| `").append(skill.getName()).append("`");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            sb.append(" | ").append(statusToken(skill)).append(" | ");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription();
                if (desc.length() > descLimit) {
                    desc = desc.substring(0, descLimit) + "...";
                }
                // Escape pipe and newline so a multi-line description doesn't
                // break the table layout.
                sb.append(desc.replace("|", "\\|").replace("\n", " "));
            }
            sb.append(" |\n");
        }
        if (selected.size() < visibleSkills.size()) {
            sb.append("\nShowing ").append(selected.size()).append(" of ")
                    .append(visibleSkills.size())
                    .append(" available skills. Use `listAvailableSkills()` for the full catalog.\n");
        }

        List<ResolvedSkill> lessonSkills = sorted.stream()
                .filter(s -> (s.getId() != null && boundIds.contains(s.getId())) || recentNames.contains(s.getName()))
                .toList();
        appendLessonsBlock(sb, lessonSkills);

        return sb.toString();
    }

    private static boolean isVisibleWithTools(ResolvedSkill skill, Set<String> effectiveToolNames) {
        if (effectiveToolNames == null) return true;
        Set<String> tools = skill.getEffectiveAllowedTools();
        return tools == null || tools.isEmpty() || effectiveToolNames.containsAll(tools);
    }

    /**
     * Treat skills installed within this window as "new" for the prompt
     * catalog ranker. Long enough that a user who installs on Friday and
     * comes back Monday still sees the boost; short enough that the
     * catalog reverts to usage-based ordering before the boost slot
     * crowds out genuinely useful skills.
     */
    public static final java.time.Duration NEW_SKILL_BOOST_WINDOW = java.time.Duration.ofDays(7);

    /**
     * Returns true if the skill's row was created after {@code cutoff}.
     * Builtins and virtual MCP/ACP skills typically have no createTime;
     * they are not boosted (the user didn't just install them). Public so
     * the user-facing {@code listAvailableSkills} catalog can apply the
     * same boost as the prompt enhancement.
     */
    public static boolean isRecentlyInstalled(ResolvedSkill skill, java.time.LocalDateTime cutoff) {
        if (skill == null || skill.getCreateTime() == null) return false;
        if (skill.isBuiltin()) return false;
        return skill.getCreateTime().isAfter(cutoff);
    }

    private static int promptCatalogEntryLimit(Integer maxInputTokens) {
        int max = maxInputTokens != null && maxInputTokens > 0 ? maxInputTokens : 8192;
        if (max <= 8192) return 8;
        if (max <= 16384) return 12;
        if (max <= 32768) return 20;
        return 32;
    }

    private static int promptDescriptionLimit(Integer maxInputTokens) {
        int max = maxInputTokens != null && maxInputTokens > 0 ? maxInputTokens : 8192;
        if (max <= 8192) return 80;
        if (max <= 16384) return 100;
        if (max <= 32768) return 140;
        return 160;
    }

    private static String statusToken(ResolvedSkill skill) {
        if (skill.isSecurityBlocked()) return "blocked";
        if (!skill.isEnabled()) return "disabled";
        if (!passesActiveGate(skill)) return "setup-needed";
        return "ready";
    }

    /**
     * Append a "## Lessons learned" block to the prompt enhancement
     * with one subsection per active skill that has lessons recorded.
     *
     * <p>Skills opt in via {@code self-evolution.lessons_enabled} (default
     * true). Skills with no LESSONS.md content contribute nothing — we
     * never emit an empty subsection.
     */
    private void appendLessonsBlock(StringBuilder sb, List<ResolvedSkill> activeSkills) {
        if (lessonsService == null || activeSkills == null || activeSkills.isEmpty()) return;
        StringBuilder lessons = new StringBuilder();
        for (ResolvedSkill skill : activeSkills) {
            SkillManifest manifest = skill.getManifest();
            boolean enabled = manifest == null
                    || manifest.getSelfEvolution() == null
                    || manifest.getSelfEvolution().isLessonsEnabled();
            if (!enabled) continue;
            String body = lessonsService.readLessonsBody(skill);
            if (body == null || body.isBlank()) continue;
            lessons.append("\n### ").append(skill.getName()).append("\n");
            lessons.append(body).append("\n");
        }
        if (lessons.length() > 0) {
            sb.append("\n\n## Lessons learned\n");
            sb.append("Past observations the agent recorded for these skills. ");
            sb.append("Treat them as advisory hints — the canonical SKILL.md still wins on conflict.\n");
            sb.append(lessons);
        }
    }

    /**
     * Map {@code System.getProperty("os.name")} to one of the canonical
     * tokens used in SKILL.md {@code platforms:} ({@code macos / linux /
     * windows}). Anything unrecognised → {@code "other"} which never
     * matches a declared platform list, so the skill stays visible only
     * if its platforms list is empty (the "all platforms" default).
     */
    static String currentOsCanonical() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix")) return "linux";
        if (os.contains("win")) return "windows";
        return "other";
    }

    /**
     * True when the skill is compatible with {@code currentOs}. A skill
     * with empty / null {@code platforms:} matches every OS (legacy
     * default). Otherwise the canonical OS token must appear in the list.
     */
    static boolean matchesCurrentPlatform(ResolvedSkill skill, String currentOs) {
        SkillManifest manifest = skill.getManifest();
        if (manifest == null) return true;
        List<String> platforms = manifest.getPlatforms();
        if (platforms == null || platforms.isEmpty()) return true;
        for (String p : platforms) {
            if (p == null) continue;
            if (currentOs.equalsIgnoreCase(p.trim())) return true;
        }
        return false;
    }

    /**
     * True when the skill is visible to an agent in {@code agentWorkspaceId}.
     * Builtin skills are global, virtual MCP-derived skills carry no
     * workspace ({@code null}) and are likewise global; every other skill is
     * visible only inside its owning workspace.
     */
    static boolean matchesWorkspace(ResolvedSkill skill, long agentWorkspaceId) {
        if (skill.isBuiltin()) return true;
        Long skillWs = skill.getWorkspaceId();
        if (skillWs == null) return true;
        return skillWs == agentWorkspaceId;
    }
}
