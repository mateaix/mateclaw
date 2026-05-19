package vip.mate.skill.lifecycle;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.skill.workspace.SkillWorkspaceProperties;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State machine primitives for the skill lifecycle curator. Holds every
 * mutation a skill can undergo as it ages out: {@code active -> stale ->
 * archived}, plus the reverse {@code restore} and the activity bump that
 * keeps an in-use skill anchored to the present.
 *
 * <p>All writes use {@link LambdaUpdateWrapper} whitelists rather than
 * {@code updateById(entity)} so {@code FieldStrategy.ALWAYS} columns are
 * never wiped by a partially-populated entity.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class SkillLifecycleService {

    private final SkillMapper skillMapper;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillWorkspaceProperties workspaceProperties;
    private final SkillRuntimeService runtimeService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;
    private final SkillLifecycleProperties properties;

    /**
     * {@code @Lazy} on {@code runtimeService} breaks the construction cycle
     * {@code SkillService -> SkillLifecycleService -> SkillRuntimeService ->
     * SkillService}.
     */
    @Autowired
    public SkillLifecycleService(SkillMapper skillMapper,
                                 SkillWorkspaceManager workspaceManager,
                                 SkillWorkspaceProperties workspaceProperties,
                                 @Lazy SkillRuntimeService runtimeService,
                                 AuditEventService auditEventService,
                                 ObjectMapper objectMapper,
                                 SkillLifecycleProperties properties) {
        this.skillMapper = skillMapper;
        this.workspaceManager = workspaceManager;
        this.workspaceProperties = workspaceProperties;
        this.runtimeService = runtimeService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // ==================== Pure decision functions ====================

    /** Activity anchor: last recorded activity, falling back to creation time. */
    public LocalDateTime anchor(SkillEntity skill) {
        if (skill.getLastActivityAt() != null) {
            return skill.getLastActivityAt();
        }
        return skill.getCreateTime();
    }

    /** Skills the curator must never touch (filtered out before the state machine). */
    public boolean isExempt(SkillEntity skill) {
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            return true;
        }
        if (Boolean.TRUE.equals(skill.getPinned())) {
            return true;
        }
        String type = skill.getSkillType();
        if (type == null || List.of("builtin", "mcp", "acp").contains(type)) {
            return true;
        }
        String name = skill.getName();
        if (name != null) {
            for (String prefix : properties.getProtectPrefixes()) {
                if (prefix != null && !prefix.isBlank() && name.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decide the transition for a skill at time {@code now}. Pure function:
     * no side effects, no I/O — driven entirely by the entity's anchor and
     * lifecycle state against the configured day thresholds.
     */
    public LifecycleTransition planTransition(SkillEntity skill, LocalDateTime now) {
        if (isExempt(skill)) {
            return LifecycleTransition.NONE;
        }
        LocalDateTime anchor = anchor(skill);
        if (anchor == null) {
            return LifecycleTransition.NONE;
        }
        long days = Duration.between(anchor, now).toDays();
        String state = Optional.ofNullable(skill.getLifecycleState()).orElse("active");
        if (days >= properties.getArchiveAfterDays()) {
            return "archived".equals(state) ? LifecycleTransition.NONE : LifecycleTransition.TO_ARCHIVED;
        }
        if (days >= properties.getStaleAfterDays()) {
            return ("stale".equals(state) || "archived".equals(state))
                    ? LifecycleTransition.NONE : LifecycleTransition.TO_STALE;
        }
        return "stale".equals(state) ? LifecycleTransition.REACTIVATE : LifecycleTransition.NONE;
    }

    // ==================== Mutations ====================

    /**
     * Apply a planned transition. Returns {@code true} when the transition
     * actually committed — an archive that fails at the workspace move or
     * the DB write returns {@code false} so the caller can report
     * {@code applied < planned}.
     */
    public boolean apply(SkillEntity skill, LifecycleTransition t, LocalDateTime now) {
        return applyManual(skill, t, now, defaultReason(t));
    }

    /**
     * Same as {@link #apply} but with an explicit audit reason — used by the
     * admin-triggered manual archive so the audit trail records intent.
     */
    public boolean applyManual(SkillEntity skill, LifecycleTransition t, LocalDateTime now, String reason) {
        return switch (t) {
            case TO_STALE -> mark(skill, "stale");
            case TO_ARCHIVED -> archive(skill, now, reason);
            case REACTIVATE -> mark(skill, "active");
            case NONE -> false;
        };
    }

    /**
     * Restore an archived skill: move its workspace back (when one was
     * archived), flip the row to {@code active}, and refresh the runtime
     * cache. DB-only skills with no archived workspace are a legitimate path
     * — they restore on the DB write alone as long as {@code skill_content}
     * still holds the body.
     */
    public SkillEntity restore(Long id) {
        SkillEntity skill = skillMapper.selectById(id);
        if (skill == null) {
            throw new MateClawException("err.skill.not_found", 404, "Skill not found: " + id);
        }
        if (!"archived".equals(skill.getLifecycleState())) {
            throw new MateClawException("err.skill.not_archived", 409,
                    "Skill is not archived: " + skill.getName());
        }

        SkillWorkspaceManager.RestoreResult fs = workspaceManager.restoreWorkspace(skill.getName());
        switch (fs) {
            case MOVED -> { /* normal path */ }
            case MISSING -> {
                if (skill.getSkillContent() == null || skill.getSkillContent().isBlank()) {
                    throw new MateClawException("err.skill.unrecoverable", 409,
                            "Skill has no workspace archive and no skill content — cannot restore");
                }
                log.warn("Restoring DB-only skill '{}' (no workspace archive)", skill.getName());
            }
            case FAILED -> throw new MateClawException("err.skill.restore_failed", 500,
                    "Workspace archive exists but move-back failed; check disk / permissions");
        }

        skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                .eq(SkillEntity::getId, id)
                .set(SkillEntity::getEnabled, true)
                .set(SkillEntity::getLifecycleState, "active")
                .set(SkillEntity::getArchivedAt, null)
                .set(SkillEntity::getLastActivityAt, LocalDateTime.now()));

        runtimeService.refreshActiveSkills();
        recordAudit("RESTORE", skill, Map.of("fs", fs.name(), "to", "active"));
        return skillMapper.selectById(id);
    }

    /**
     * Pin or unpin a skill. A pinned skill is permanently exempt from the
     * automatic state machine until unpinned.
     */
    public SkillEntity setPinned(Long id, boolean pinned) {
        SkillEntity skill = skillMapper.selectById(id);
        if (skill == null) {
            throw new MateClawException("err.skill.not_found", 404, "Skill not found: " + id);
        }
        skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                .eq(SkillEntity::getId, id)
                .set(SkillEntity::getPinned, pinned));
        recordAudit(pinned ? "PIN" : "UNPIN", skill, Map.of("pinned", pinned));
        return skillMapper.selectById(id);
    }

    /**
     * Push the activity anchor of a skill to now and pull it back to
     * {@code active} if it had drifted to {@code stale}. Best-effort: a
     * write failure is logged, never thrown — losing one bump only delays
     * the curator by a day. Archived skills are left untouched (recovering
     * an archived skill must go through {@link #restore}).
     */
    public void bumpActivity(Long skillId) {
        if (skillId == null) {
            return;
        }
        try {
            skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                    .eq(SkillEntity::getId, skillId)
                    .and(w -> w.isNull(SkillEntity::getLifecycleState)
                            .or().ne(SkillEntity::getLifecycleState, "archived"))
                    .set(SkillEntity::getLastActivityAt, LocalDateTime.now())
                    .set(SkillEntity::getLifecycleState, "active"));
        } catch (Exception e) {
            log.debug("Failed to bump activity for skill {}: {}", skillId, e.getMessage());
        }
    }

    // ==================== Internals ====================

    private boolean mark(SkillEntity skill, String toState) {
        String prevState = Optional.ofNullable(skill.getLifecycleState()).orElse("active");
        if (prevState.equals(toState)) {
            return false;
        }
        int rows;
        try {
            rows = skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                    .eq(SkillEntity::getId, skill.getId())
                    .set(SkillEntity::getLifecycleState, toState));
        } catch (Exception e) {
            log.warn("Skill '{}' lifecycle mark to {} failed: {}", skill.getName(), toState, e.getMessage());
            return false;
        }
        if (rows == 0) {
            return false;
        }
        recordAudit("LIFECYCLE", skill, Map.of("from", prevState, "to", toState));
        return true;
    }

    /**
     * Archive a skill: move its workspace to {@code .archived/}, then flip
     * the row. The filesystem move runs before the DB write so a DB failure
     * can be compensated by moving the workspace back. Returns {@code false}
     * (no commit) when the workspace move fails or the DB write touches no
     * rows — the next sweep retries.
     */
    private boolean archive(SkillEntity skill, LocalDateTime now, String reason) {
        // Step 1: workspace move. MISSING is commit-safe (DB-only skill);
        // FAILED defers the whole transition.
        SkillWorkspaceManager.ArchiveResult fsResult = SkillWorkspaceManager.ArchiveResult.MISSING;
        if ("archive".equals(workspaceProperties.getDeletePolicy())) {
            fsResult = workspaceManager.archiveWorkspace(skill.getName());
        }
        if (fsResult == SkillWorkspaceManager.ArchiveResult.FAILED) {
            log.warn("Skill '{}' workspace archive failed; deferring DB transition", skill.getName());
            return false;
        }

        // Step 2: DB flip — guarded by affected-row count + compensation.
        String prevState = Optional.ofNullable(skill.getLifecycleState()).orElse("active");
        int rows = 0;
        try {
            rows = skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                    .eq(SkillEntity::getId, skill.getId())
                    .set(SkillEntity::getEnabled, false)
                    .set(SkillEntity::getLifecycleState, "archived")
                    .set(SkillEntity::getArchivedAt, now));
        } catch (Exception e) {
            log.error("Skill '{}' DB archive write failed; attempting compensation", skill.getName(), e);
        }
        if (rows == 0) {
            log.warn("Skill '{}' DB archive update touched 0 rows; compensating workspace", skill.getName());
            if (fsResult == SkillWorkspaceManager.ArchiveResult.MOVED) {
                workspaceManager.restoreWorkspace(skill.getName());
            }
            return false;
        }

        // Mirror the uninstall path: deregister wrapper tools AND refresh the
        // active-skill cache so an in-flight prompt build stops seeing the row.
        runtimeService.deregisterSkillWrappers(skill.getId());
        runtimeService.refreshActiveSkills();

        recordAudit("ARCHIVE", skill, Map.of(
                "reason", reason,
                "anchor", String.valueOf(anchor(skill)),
                "from", prevState,
                "to", "archived",
                "fs", fsResult.name()));
        return true;
    }

    private String defaultReason(LifecycleTransition t) {
        return switch (t) {
            case TO_STALE -> "idle>=" + properties.getStaleAfterDays() + "d";
            case TO_ARCHIVED -> "idle>=" + properties.getArchiveAfterDays() + "d";
            case REACTIVATE -> "activity-observed";
            case NONE -> "";
        };
    }

    private void recordAudit(String action, SkillEntity skill, Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            json = String.valueOf(detail);
        }
        auditEventService.record(action, "SKILL",
                String.valueOf(skill.getId()), skill.getName(), json);
    }
}
