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

    @Scheduled(cron = "${mateclaw.skill.curator.cron:0 0 2 * * *}")
    @SchedulerLock(name = "skill-curator", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        // Gate 1: config-level enable.
        if (!properties.isEnabled() || "OFF".equals(properties.getScope())) {
            return;
        }
        // Gate 2: operational pause.
        if (systemSettingService.getBool(PAUSED_KEY, false)) {
            log.debug("Curator paused via {} — skipping this tick", PAUSED_KEY);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        boolean activated = systemSettingService.getBool(FIRST_RUN_KEY, false);

        // Gate 3: first-run throttle. Before activation the sweep is
        // informational; bound it to once per ~day so the report directory
        // doesn't fill with identical previews.
        if (!activated) {
            LocalDateTime lastObserved = parseTs(systemSettingService.getString(LAST_OBSERVED_KEY, null));
            LocalDateTime lastDry = parseTs(systemSettingService.getString(LAST_DRY_RUN_KEY, null));
            if (lastObserved == null) {
                systemSettingService.saveString(LAST_OBSERVED_KEY, now.toString(),
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
        SkillCuratorReport report = sweep(now, dryRun);

        if (dryRun) {
            systemSettingService.saveString(LAST_DRY_RUN_KEY, now.toString(),
                    "Skill curator last dry-run timestamp");
        }
        systemSettingService.saveString(LAST_RUN_KEY, now.toString(),
                "Skill curator last run timestamp");
        notifier.onRunComplete(report);
    }

    /**
     * Run a dry-run sweep immediately, bypassing the first-run throttle and
     * the scheduler lock — for the admin "preview now" action.
     */
    public SkillCuratorReport dryRunNow() {
        SkillCuratorReport report = sweep(LocalDateTime.now(), true);
        notifier.onRunComplete(report);
        return report;
    }

    /** Flip the activation flag (preview-only ⇄ applying). */
    public void activate(boolean activate) {
        systemSettingService.saveBool(FIRST_RUN_KEY, activate, "Skill curator activated");
    }

    /** Set the runtime pause flag. */
    public void setPaused(boolean paused) {
        systemSettingService.saveBool(PAUSED_KEY, paused, "Skill curator paused");
    }

    /** Aggregated control-panel state for the admin UI. */
    public Map<String, Object> status() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", properties.isEnabled());
        config.put("scope", properties.getScope());
        config.put("staleAfterDays", properties.getStaleAfterDays());
        config.put("archiveAfterDays", properties.getArchiveAfterDays());
        config.put("cron", properties.getCron());

        Map<String, Object> control = new LinkedHashMap<>();
        control.put("activated", systemSettingService.getBool(FIRST_RUN_KEY, false));
        control.put("paused", systemSettingService.getBool(PAUSED_KEY, false));
        control.put("lastObservedAt", systemSettingService.getString(LAST_OBSERVED_KEY, null));
        control.put("lastDryRunAt", systemSettingService.getString(LAST_DRY_RUN_KEY, null));
        control.put("lastRunAt", systemSettingService.getString(LAST_RUN_KEY, null));
        control.put("nextScheduledRun", nextScheduledRun());

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("active", countState("active"));
        counts.put("stale", countState("stale"));
        counts.put("archived", countState("archived"));
        counts.put("pinned", skillMapper.selectCount(
                new LambdaQueryWrapper<SkillEntity>().eq(SkillEntity::getPinned, true)));
        // Count only archival-relevant skills held back by a binding — same
        // set the run report's blockedByBindings array shows, so the status
        // count and the report stay consistent (builtin / mcp / acp / pinned
        // skills are exempt regardless of bindings and are not counted here).
        counts.put("blockedByBindings",
                agentBindingService.blockedByBindingCandidates(LocalDateTime.now()).size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", config);
        out.put("control", control);
        out.put("counts", counts);
        String latest = reportStore.latestRunId();
        out.put("lastReport", latest == null ? null : Map.of(
                "id", latest,
                "url", "/api/v1/skills/curator/reports/" + latest));
        return out;
    }

    // ==================== Internals ====================

    private SkillCuratorReport sweep(LocalDateTime now, boolean dryRun) {
        SkillCuratorReport.Builder report = SkillCuratorReport.builder()
                .runAt(now)
                .dryRun(dryRun)
                .config(properties.getStaleAfterDays(), properties.getArchiveAfterDays(),
                        properties.getScope());

        reconcileOrphans(now, report, dryRun);

        List<SkillEntity> candidates = loadCandidates();
        int plannedStale = 0, plannedArchived = 0, plannedReactivate = 0;
        int appliedStale = 0, appliedArchived = 0, appliedReactivate = 0;
        for (SkillEntity skill : candidates) {
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
                .plannedCounts(plannedStale, plannedArchived, plannedReactivate)
                .appliedCounts(appliedStale, appliedArchived, appliedReactivate)
                .blockedByBindings(agentBindingService.blockedByBindingCandidates(now));

        return reportStore.write(report.build());
    }

    /**
     * Candidate skills for the state machine: not builtin, not pinned, not a
     * builtin/mcp/acp type, not bound to any enabled agent, and — under the
     * default {@code AGENT_CREATED} scope — created by an agent.
     */
    private List<SkillEntity> loadCandidates() {
        Set<Long> bindingProtected = agentBindingService.skillIdsBoundToEnabledAgents();

        LambdaQueryWrapper<SkillEntity> w = new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getBuiltin, false)
                .eq(SkillEntity::getPinned, false)
                .notIn(SkillEntity::getSkillType, List.of("builtin", "mcp", "acp"));
        if (!bindingProtected.isEmpty()) {
            w.notIn(SkillEntity::getId, bindingProtected);
        }
        if ("AGENT_CREATED".equals(properties.getScope())) {
            w.isNotNull(SkillEntity::getSourceConversationId);
        }
        return skillMapper.selectList(w);
    }

    /**
     * Heal the unambiguous divergence class: a row marked {@code archived}
     * whose convention workspace is back in place (an admin moved a directory
     * or a re-install ran). The reverse class — workspace moved but the DB
     * write failed — is handled inline by the archive compensation path.
     */
    private void reconcileOrphans(LocalDateTime now, SkillCuratorReport.Builder report, boolean dryRun) {
        List<SkillEntity> archived = skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getLifecycleState, "archived"));
        for (SkillEntity skill : archived) {
            if (skill.getName() == null || !workspaceManager.conventionWorkspaceExists(skill.getName())) {
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

    private long countState(String state) {
        return skillMapper.selectCount(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getLifecycleState, state));
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
