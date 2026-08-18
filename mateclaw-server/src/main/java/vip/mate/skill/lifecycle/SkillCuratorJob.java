package vip.mate.skill.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillOrigin;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.system.service.SystemSettingService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily sweep that ages idle, agent-created skills through the lifecycle
 * state machine. Three gates guard the sweep: the config-level
 * {@code enabled} switch, an operational {@code paused} kill switch, and a
 * first-run throttle that keeps the pre-activation dry-run from flooding the
 * report directory.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCuratorJob {

    /** Admin flipped the curator from preview-only to applying transitions. */
    static final String FIRST_RUN_KEY = "skill.curator.firstRunCompleted";
    /** Runtime kill switch — pauses the scheduled sweep without a redeploy. */
    static final String PAUSED_KEY = "skill.curator.paused";
    /** Runtime override for the consolidation pass (falls back to config). */
    static final String CONSOLIDATE_KEY = "skill.curator.consolidate";
    /** ISO-8601 timestamp of the last auto dry-run, for throttling. */
    static final String LAST_DRY_RUN_KEY = "skill.curator.lastDryRunAt";
    /** ISO-8601 timestamp of the first sweep observation after install. */
    static final String LAST_OBSERVED_KEY = "skill.curator.lastObservedAt";
    /** ISO-8601 timestamp of the last sweep that produced a report. */
    static final String LAST_RUN_KEY = "skill.curator.lastRunAt";

    /** Minimum hours between auto dry-runs while the curator is not activated. */
    private static final long DRY_RUN_THROTTLE_HOURS = 23;

    private final SkillLifecycleService lifecycleService;
    private final SkillMapper skillMapper;
    private final SkillCuratorReportStore reportStore;
    private final SkillLifecycleProperties properties;
    private final SystemSettingService systemSettingService;
    private final AgentBindingService agentBindingService;
    private final SkillWorkspaceManager workspaceManager;
    private final CuratorRunNotifier notifier;
    private final SkillConsolidationService consolidationService;
    private final SkillSnapshotService snapshotService;

    @Scheduled(cron = "${mateclaw.skill.curator.cron:0 0 2 * * *}")
    @SchedulerLock(name = "skill-curator", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        // Gate 1: config-level enable.
        if (!properties.isEnabled() || "OFF".equals(properties.getScope())) {
            return;
        }
        for (Long workspaceId : curatorWorkspaceIds()) {
            try {
                runWorkspace(workspaceId);
            } catch (Exception e) {
                log.error("Curator failed for workspace {}: {}", workspaceId, e.getMessage(), e);
            }
        }
    }

    private void runWorkspace(Long workspaceId) {
        // Gate 2: operational pause, isolated per workspace.
        if (systemSettingService.getBool(key(PAUSED_KEY, workspaceId), false)) {
            log.debug("Curator paused for workspace {} — skipping this tick", workspaceId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        boolean activated = systemSettingService.getBool(key(FIRST_RUN_KEY, workspaceId), false);

        // Gate 3: first-run throttle. Before activation the sweep is
        // informational; bound it to once per ~day so the report directory
        // doesn't fill with identical previews.
        if (!activated) {
            LocalDateTime lastObserved = parseTs(systemSettingService.getString(key(LAST_OBSERVED_KEY, workspaceId), null));
            LocalDateTime lastDry = parseTs(systemSettingService.getString(key(LAST_DRY_RUN_KEY, workspaceId), null));
            if (lastObserved == null) {
                systemSettingService.saveString(key(LAST_OBSERVED_KEY, workspaceId), now.toString(),
                        "Skill curator first observed timestamp");
                log.info("Curator first observation — deferring; preview on demand via /curator/dry-run");
                return;
            }
            Duration sinceLastDry = lastDry == null
                    ? Duration.between(lastObserved, now)
                    : Duration.between(lastDry, now);
            if (sinceLastDry.toHours() < DRY_RUN_THROTTLE_HOURS) {
                log.debug("Curator dry-run throttled ({}h since last)", sinceLastDry.toHours());
                return;
            }
        }

        boolean dryRun = !activated;
        SkillCuratorReport report = sweep(now, dryRun, workspaceId);

        if (dryRun) {
            systemSettingService.saveString(key(LAST_DRY_RUN_KEY, workspaceId), now.toString(),
                    "Skill curator last dry-run timestamp");
        }
        systemSettingService.saveString(key(LAST_RUN_KEY, workspaceId), now.toString(),
                "Skill curator last run timestamp");
        notifier.onRunComplete(report, workspaceId);
    }

    /**
     * Run a dry-run sweep immediately, bypassing the first-run throttle and
     * the scheduler lock — for the admin "preview now" action.
     */
    public SkillCuratorReport dryRunNow() {
        return dryRunNow(1L);
    }

    public SkillCuratorReport dryRunNow(Long workspaceId) {
        SkillCuratorReport report = sweep(LocalDateTime.now(), true, normalizeWorkspaceId(workspaceId));
        notifier.onRunComplete(report, normalizeWorkspaceId(workspaceId));
        return report;
    }

    /** Flip the activation flag (preview-only ⇄ applying). */
    public void activate(boolean activate) {
        activate(1L, activate);
    }

    public void activate(Long workspaceId, boolean activate) {
        systemSettingService.saveBool(key(FIRST_RUN_KEY, workspaceId), activate, "Skill curator activated");
    }

    /** Set the runtime pause flag. */
    public void setPaused(boolean paused) {
        setPaused(1L, paused);
    }

    public void setPaused(Long workspaceId, boolean paused) {
        systemSettingService.saveBool(key(PAUSED_KEY, workspaceId), paused, "Skill curator paused");
    }

    /** Set the runtime consolidation flag (overrides the config default). */
    public void setConsolidate(boolean on) {
        setConsolidate(1L, on);
    }

    public void setConsolidate(Long workspaceId, boolean on) {
        systemSettingService.saveBool(key(CONSOLIDATE_KEY, workspaceId), on, "Skill curator consolidation enabled");
    }

    /** Effective consolidation switch: runtime override, falling back to config. */
    private boolean effectiveConsolidate(Long workspaceId) {
        return systemSettingService.getBool(key(CONSOLIDATE_KEY, workspaceId), properties.isConsolidate());
    }

    /** Aggregated control-panel state for the admin UI. */
    public Map<String, Object> status() {
        return status(1L);
    }

    public Map<String, Object> status(Long workspaceId) {
        workspaceId = normalizeWorkspaceId(workspaceId);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", properties.isEnabled());
        config.put("scope", properties.getScope());
        config.put("staleAfterDays", properties.getStaleAfterDays());
        config.put("archiveAfterDays", properties.getArchiveAfterDays());
        config.put("cron", properties.getCron());

        Map<String, Object> control = new LinkedHashMap<>();
        control.put("activated", systemSettingService.getBool(key(FIRST_RUN_KEY, workspaceId), false));
        control.put("paused", systemSettingService.getBool(key(PAUSED_KEY, workspaceId), false));
        control.put("consolidate", effectiveConsolidate(workspaceId));
        control.put("lastObservedAt", systemSettingService.getString(key(LAST_OBSERVED_KEY, workspaceId), null));
        control.put("lastDryRunAt", systemSettingService.getString(key(LAST_DRY_RUN_KEY, workspaceId), null));
        control.put("lastRunAt", systemSettingService.getString(key(LAST_RUN_KEY, workspaceId), null));
        control.put("nextScheduledRun", nextScheduledRun());

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("active", countState("active", workspaceId));
        counts.put("stale", countState("stale", workspaceId));
        counts.put("archived", countState("archived", workspaceId));
        counts.put("pinned", skillMapper.selectCount(
                new LambdaQueryWrapper<SkillEntity>().eq(SkillEntity::getPinned, true)
                        .eq(SkillEntity::getWorkspaceId, workspaceId)));
        // Count only archival-relevant skills held back by a binding — same
        // set the run report's blockedByBindings array shows, so the status
        // count and the report stay consistent (builtin / mcp / acp / pinned
        // skills are exempt regardless of bindings and are not counted here).
        counts.put("blockedByBindings",
                agentBindingService.blockedByBindingCandidates(LocalDateTime.now(), workspaceId).size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", config);
        out.put("control", control);
        out.put("counts", counts);
        String latest = reportStore.latestRunId(workspaceId);
        out.put("lastReport", latest == null ? null : Map.of(
                "id", latest,
                "url", "/api/v1/skills/curator/reports/" + latest));
        return out;
    }

    // ==================== Internals ====================

    private SkillCuratorReport sweep(LocalDateTime now, boolean dryRun, Long workspaceId) {
        SkillCuratorReport.Builder report = SkillCuratorReport.builder()
                .runAt(now)
                .dryRun(dryRun)
                .config(properties.getStaleAfterDays(), properties.getArchiveAfterDays(),
                        properties.getScope());

        // Capture a restore point before anything mutates. A dry run changes
        // nothing, so it needs none; a real sweep can archive and (with
        // consolidation on) rewrite skill bodies unattended, and this is the
        // only chance to record what they looked like beforehand.
        if (!dryRun) {
            snapshotService.captureRequired("pre-sweep", workspaceId);
        }

        reconcileOrphans(now, report, dryRun, workspaceId);

        List<SkillEntity> candidates = loadCandidates(workspaceId);
        int plannedStale = 0, plannedArchived = 0, plannedReactivate = 0;
        int appliedStale = 0, appliedArchived = 0, appliedReactivate = 0;
        int newlyObserved = 0;
        for (SkillEntity skill : candidates) {
            // A candidate no sweep has seen before starts its idle clock now
            // rather than being judged on time it spent outside curation.
            // planTransition already returns NONE for these; stamping the
            // anchor is what lets the next sweep judge it for real.
            //
            // A dry run must not write, but it must still reach the same
            // verdict a real run would — this report is what an operator reads
            // to decide whether widening the scope is safe, so predicting
            // archives that a real run would defer would be a lie.
            if (SkillLifecycleService.isUnobserved(skill)) {
                newlyObserved++;
                if (!dryRun) {
                    lifecycleService.markObserved(skill, now);
                }
                continue;
            }
            LifecycleTransition t = lifecycleService.planTransition(skill, now);
            report.add(skill, t);
            if (t == LifecycleTransition.TO_STALE) {
                plannedStale++;
            } else if (t == LifecycleTransition.TO_ARCHIVED) {
                plannedArchived++;
            } else if (t == LifecycleTransition.REACTIVATE) {
                plannedReactivate++;
            }
            if (dryRun) {
                continue;
            }
            boolean applied = lifecycleService.apply(skill, t, now);
            if (applied) {
                if (t == LifecycleTransition.TO_STALE) {
                    appliedStale++;
                } else if (t == LifecycleTransition.TO_ARCHIVED) {
                    appliedArchived++;
                } else if (t == LifecycleTransition.REACTIVATE) {
                    appliedReactivate++;
                }
            }
        }

        report.scanned(candidates.size())
                .newlyObserved(newlyObserved)
                .plannedCounts(plannedStale, plannedArchived, plannedReactivate)
                .appliedCounts(appliedStale, appliedArchived, appliedReactivate)
                .blockedByBindings(agentBindingService.blockedByBindingCandidates(now, workspaceId));

        // Consolidation pass (opt-in). Reload candidates so it sees the state
        // left by the aging pass above and never merges a just-archived skill.
        if (effectiveConsolidate(workspaceId)) {
            List<SkillEntity> mergeCandidates = loadCandidates(workspaceId).stream()
                    .filter(s -> !"archived".equals(s.getLifecycleState()))
                    .toList();
            consolidationService.consolidate(mergeCandidates, now, dryRun, report, workspaceId);
        }

        return reportStore.write(report.build(), workspaceId);
    }

    /**
     * Candidate skills for the state machine: not builtin, not pinned, not a
     * builtin/mcp/acp type, not bound to any enabled agent, and — under the
     * default {@code AGENT_CREATED} scope — written autonomously.
     *
     * <p>The scope filter keys on {@code origin}, not on the presence of a
     * source conversation. Both a skill the user asked for mid-chat and one
     * the background reviewer invented carry a conversation id, so the older
     * filter swept up user-requested work alongside the machine's own.
     */
    private List<SkillEntity> loadCandidates(Long workspaceId) {
        Set<Long> bindingProtected = agentBindingService.skillIdsBoundToEnabledAgents(workspaceId);

        LambdaQueryWrapper<SkillEntity> w = new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getBuiltin, false)
                .eq(SkillEntity::getWorkspaceId, workspaceId)
                .eq(SkillEntity::getPinned, false)
                .notIn(SkillEntity::getSkillType, List.of("builtin", "mcp", "acp"));
        if (!bindingProtected.isEmpty()) {
            w.notIn(SkillEntity::getId, bindingProtected);
        }
        if ("AGENT_CREATED".equals(properties.getScope())) {
            w.in(SkillEntity::getOrigin, SkillOrigin.curatorManagedCodes());
        }
        return skillMapper.selectList(w);
    }

    /**
     * Heal the unambiguous divergence class: a row marked {@code archived}
     * whose convention workspace is back in place (an admin moved a directory
     * or a re-install ran). The reverse class — workspace moved but the DB
     * write failed — is handled inline by the archive compensation path.
     */
    private void reconcileOrphans(LocalDateTime now, SkillCuratorReport.Builder report, boolean dryRun,
                                  Long workspaceId) {
        List<SkillEntity> archived = skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getLifecycleState, "archived")
                .eq(SkillEntity::getWorkspaceId, workspaceId));
        for (SkillEntity skill : archived) {
            if (skill.getName() == null || !workspaceManager.conventionWorkspaceExists(skill.getName(), skill.getWorkspaceId())) {
                continue;
            }
            report.reconciliation("skill '" + skill.getName() + "' (id=" + skill.getId()
                    + ") archived in DB but workspace present — reactivating");
            if (!dryRun) {
                skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                        .eq(SkillEntity::getId, skill.getId())
                        .set(SkillEntity::getLifecycleState, "active")
                        .set(SkillEntity::getEnabled, true)
                        .set(SkillEntity::getArchivedAt, null)
                        .set(SkillEntity::getLastActivityAt, now));
            }
        }
    }

    private long countState(String state, Long workspaceId) {
        return skillMapper.selectCount(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getLifecycleState, state)
                .eq(SkillEntity::getWorkspaceId, workspaceId));
    }

    private List<Long> curatorWorkspaceIds() {
        List<Long> ids = skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getBuiltin, false)
                        .select(SkillEntity::getWorkspaceId))
                .stream()
                .map(SkillEntity::getWorkspaceId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        return ids.isEmpty() ? List.of(1L) : ids;
    }

    private static Long normalizeWorkspaceId(Long workspaceId) {
        return workspaceId != null && workspaceId > 0 ? workspaceId : 1L;
    }

    private static String key(String base, Long workspaceId) {
        return base + ".workspace." + normalizeWorkspaceId(workspaceId);
    }

    private String nextScheduledRun() {
        try {
            LocalDateTime next = CronExpression.parse(properties.getCron()).next(LocalDateTime.now());
            return next != null ? next.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseTs(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
