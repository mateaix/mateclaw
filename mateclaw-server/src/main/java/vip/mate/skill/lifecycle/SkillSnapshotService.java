package vip.mate.skill.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.skill.lifecycle.model.SkillSnapshotEntity;
import vip.mate.skill.lifecycle.repository.SkillSnapshotMapper;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Restore points for the skill library, captured before a mutating curator
 * sweep.
 *
 * <p>Autonomous curation makes broad, unattended changes: consolidation
 * rewrites skill bodies and folds several skills into an umbrella, and the
 * state machine archives skills out of the active set. Both run overnight with
 * nobody watching, so the first time anyone notices a bad pass is well after
 * it finished. A snapshot turns "the curator mangled my library" from an
 * unrecoverable event into one command.
 *
 * <p>Only the fields autonomous curation can actually change are captured.
 * The curator never deletes a skill — it archives, which is a state change —
 * so restoring is always an update over rows that still exist, never a
 * resurrection.
 *
 * <p>Restore is itself snapshotted first, so a rollback applied to the wrong
 * run can be rolled forward again.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSnapshotService {

    private final SkillMapper skillMapper;
    private final SkillSnapshotMapper snapshotMapper;
    private final SkillLifecycleProperties properties;
    private final ObjectMapper objectMapper;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillRuntimeService runtimeService;

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Capture the current state of every curatable skill.
     *
     * @param reason why the snapshot was taken, shown in listings
     * @return the persisted snapshot, or {@code null} when snapshots are
     *         disabled or there was nothing to capture
     */
    public SkillSnapshotEntity capture(String reason) {
        return capture(reason, 1L);
    }

    public SkillSnapshotEntity capture(String reason, Long workspaceId) {
        return captureInternal(reason, workspaceId, false);
    }

    /**
     * Capture a mandatory restore point. Unlike the admin-facing best-effort
     * API, persistence/serialization failures propagate so an autonomous
     * mutation cannot continue without the rollback point it promised.
     * Explicitly disabling backups remains an intentional opt-out.
     */
    public SkillSnapshotEntity captureRequired(String reason, Long workspaceId) {
        return captureInternal(reason, workspaceId, true);
    }

    private SkillSnapshotEntity captureInternal(String reason, Long workspaceId, boolean required) {
        long scopedWorkspaceId = normalizeWorkspaceId(workspaceId);
        if (!properties.isBackupEnabled()) {
            return null;
        }
        List<SkillEntity> skills = skillMapper.selectList(
                new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getBuiltin, false)
                        .eq(SkillEntity::getWorkspaceId, scopedWorkspaceId));
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        ArrayNode payload = objectMapper.createArrayNode();
        for (SkillEntity skill : skills) {
            payload.add(toNode(skill));
        }
        SkillSnapshotEntity snapshot = new SkillSnapshotEntity();
        snapshot.setWorkspaceId(scopedWorkspaceId);
        snapshot.setReason(reason == null || reason.isBlank() ? "manual" : reason.strip());
        snapshot.setSkillCount(skills.size());
        try {
            snapshot.setPayload(objectMapper.writeValueAsString(payload));
            int inserted = snapshotMapper.insert(snapshot);
            if (inserted != 1) {
                throw new IllegalStateException("snapshot insert affected " + inserted + " rows");
            }
        } catch (Exception e) {
            log.warn("[SkillSnapshot] Capture failed ({}): {}", reason, e.getMessage());
            if (required) {
                throw new IllegalStateException("Required skill snapshot could not be captured", e);
            }
            return null;
        }
        pruneToRetention(scopedWorkspaceId);
        log.info("[SkillSnapshot] Captured {} skill(s) — reason='{}', id={}",
                skills.size(), snapshot.getReason(), snapshot.getId());
        return snapshot;
    }

    /**
     * Roll the skill library back to a snapshot.
     *
     * <p>Takes a {@code pre-restore} snapshot first, so an unwanted rollback
     * can be undone by restoring that one.
     *
     * @param snapshotId snapshot to restore
     * @return per-skill outcome counts
     * @throws IllegalArgumentException when the snapshot does not exist or its
     *                                  payload cannot be read
     */
    public Map<String, Object> restore(Long snapshotId) {
        return restore(snapshotId, 1L);
    }

    // Deliberately not one outer DB transaction: restore reports per-skill
    // success/failure and compensates that skill's filesystem on a failed DB
    // write. An outer transaction would let one late SQL error roll back all
    // earlier rows while their already-completed filesystem changes remained.
    public Map<String, Object> restore(Long snapshotId, Long workspaceId) {
        long scopedWorkspaceId = normalizeWorkspaceId(workspaceId);
        SkillSnapshotEntity snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<SkillSnapshotEntity>()
                        .eq(SkillSnapshotEntity::getId, snapshotId)
                        .eq(SkillSnapshotEntity::getWorkspaceId, scopedWorkspaceId));
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot " + snapshotId + " not found");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(snapshot.getPayload() == null ? "[]" : snapshot.getPayload());
        } catch (Exception e) {
            throw new IllegalArgumentException("Snapshot " + snapshotId + " payload is unreadable", e);
        }
        if (!payload.isArray()) {
            throw new IllegalArgumentException("Snapshot " + snapshotId + " payload is not an array");
        }

        // Snapshot the current state before overwriting it, so restoring the
        // wrong run is not itself a one-way door.
        captureRequired("pre-restore to snapshot " + snapshotId, scopedWorkspaceId);

        int restored = 0;
        int missing = 0;
        int failed = 0;
        Set<Long> snapshotSkillIds = new HashSet<>();
        for (JsonNode node : payload) {
            Long id = node.path("id").isNull() ? null : node.path("id").asLong(0);
            if (id == null || id == 0) {
                continue;
            }
            snapshotSkillIds.add(id);
            SkillEntity current = skillMapper.selectById(id);
            if (current == null || !Long.valueOf(scopedWorkspaceId).equals(current.getWorkspaceId())) {
                // The curator never deletes, so a row that is gone was removed
                // by something else; re-creating it here would resurrect a
                // deletion the user meant.
                missing++;
                continue;
            }
            ObjectNode previousState = toNode(current);
            try {
                restoreWorkspaceState(current, node, scopedWorkspaceId);
                int rows = skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                        .eq(SkillEntity::getId, id)
                        .eq(SkillEntity::getWorkspaceId, scopedWorkspaceId)
                        .set(SkillEntity::getSkillContent, textOrNull(node, "skillContent"))
                        .set(SkillEntity::getDescription, textOrNull(node, "description"))
                        .set(SkillEntity::getVersion, textOrNull(node, "version"))
                        .set(SkillEntity::getTags, textOrNull(node, "tags"))
                        .set(SkillEntity::getOrigin, textOrNull(node, "origin"))
                        .set(SkillEntity::getLifecycleState, textOrNull(node, "lifecycleState"))
                        .set(SkillEntity::getEnabled, boolOrNull(node, "enabled"))
                        .set(SkillEntity::getPinned, boolOrNull(node, "pinned"))
                        .set(node.has("lastActivityAt"), SkillEntity::getLastActivityAt,
                                dateTimeOrNull(node, "lastActivityAt"))
                        .set(node.has("curatorSeenAt"), SkillEntity::getCuratorSeenAt,
                                dateTimeOrNull(node, "curatorSeenAt"))
                        .set(node.has("archivedAt"), SkillEntity::getArchivedAt,
                                dateTimeOrNull(node, "archivedAt")));
                if (rows != 1) {
                    throw new IllegalStateException("restore update affected " + rows + " rows");
                }
                restored++;
            } catch (Exception e) {
                failed++;
                log.warn("[SkillSnapshot] Restore failed for skill id={}: {}", id, e.getMessage());
                try {
                    restoreWorkspaceState(current, previousState, scopedWorkspaceId);
                } catch (Exception compensationError) {
                    log.error("[SkillSnapshot] Filesystem compensation failed for skill id={}: {}",
                            id, compensationError.getMessage());
                }
            }
        }
        ArchiveAdditionsResult additions = archivePostSnapshotAdditions(snapshotSkillIds, scopedWorkspaceId);
        failed += additions.failed();
        try {
            runtimeService.refreshActiveSkills();
        } catch (Exception e) {
            // The DB/filesystem restore is authoritative. A transient cache
            // refresh failure must not roll its transaction back after files
            // have already been reconciled; the next scheduled refresh heals it.
            log.warn("[SkillSnapshot] Runtime refresh after restore failed: {}", e.getMessage());
        }
        log.info("[SkillSnapshot] Restored {} skill(s) from snapshot {} ({} no longer present)",
                restored, snapshotId, missing);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", String.valueOf(snapshotId));
        out.put("restored", restored);
        out.put("missing", missing);
        out.put("failed", failed);
        out.put("archivedAdditions", additions.archived());
        return out;
    }

    /** Recent snapshots, newest first, without their payloads. */
    public List<Map<String, Object>> list(int limit) {
        return list(1L, limit);
    }

    public List<Map<String, Object>> list(Long workspaceId, int limit) {
        long scopedWorkspaceId = normalizeWorkspaceId(workspaceId);
        List<SkillSnapshotEntity> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<SkillSnapshotEntity>()
                        .eq(SkillSnapshotEntity::getWorkspaceId, scopedWorkspaceId)
                        .select(SkillSnapshotEntity::getId, SkillSnapshotEntity::getReason,
                                SkillSnapshotEntity::getSkillCount, SkillSnapshotEntity::getCreateTime)
                        .orderByDesc(SkillSnapshotEntity::getCreateTime)
                        .last("LIMIT " + Math.max(1, limit)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillSnapshotEntity row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            // Snowflake id as a string: 19 digits exceed JS Number precision.
            m.put("id", String.valueOf(row.getId()));
            m.put("reason", row.getReason());
            m.put("skillCount", row.getSkillCount());
            m.put("createdAt", row.getCreateTime() == null ? null : row.getCreateTime().format(LABEL_FMT));
            out.add(m);
        }
        return out;
    }

    /** Drop the oldest snapshots beyond the configured retention count. */
    private void pruneToRetention(Long workspaceId) {
        int keep = Math.max(1, properties.getBackupKeep());
        List<SkillSnapshotEntity> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<SkillSnapshotEntity>()
                        .eq(SkillSnapshotEntity::getWorkspaceId, workspaceId)
                        .select(SkillSnapshotEntity::getId)
                        .orderByDesc(SkillSnapshotEntity::getCreateTime));
        if (rows.size() <= keep) {
            return;
        }
        for (SkillSnapshotEntity stale : rows.subList(keep, rows.size())) {
            try {
                snapshotMapper.deleteById(stale.getId());
            } catch (Exception e) {
                log.debug("[SkillSnapshot] Prune failed for {}: {}", stale.getId(), e.getMessage());
            }
        }
    }

    private ObjectNode toNode(SkillEntity skill) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", skill.getId());
        n.put("name", skill.getName());
        n.put("description", skill.getDescription());
        n.put("version", skill.getVersion());
        n.put("tags", skill.getTags());
        n.put("origin", skill.getOrigin());
        n.put("lifecycleState", skill.getLifecycleState());
        n.put("enabled", skill.getEnabled());
        n.put("pinned", skill.getPinned());
        n.put("skillContent", skill.getSkillContent());
        putDateTime(n, "lastActivityAt", skill.getLastActivityAt());
        putDateTime(n, "curatorSeenAt", skill.getCuratorSeenAt());
        putDateTime(n, "archivedAt", skill.getArchivedAt());
        n.put("workspacePresent", workspaceManager.conventionWorkspaceExists(
                skill.getName(), skill.getWorkspaceId()));
        return n;
    }

    /**
     * A consolidation can create a new umbrella skill after the snapshot was
     * captured. Leaving that row active would make a restore only partial, so
     * additions absent from the snapshot are archived (not deleted) and remain
     * recoverable through the automatically captured pre-restore point.
     */
    private ArchiveAdditionsResult archivePostSnapshotAdditions(Set<Long> snapshotSkillIds, Long workspaceId) {
        List<SkillEntity> currentSkills = skillMapper.selectList(
                new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getBuiltin, false)
                        .eq(SkillEntity::getWorkspaceId, workspaceId));
        int archived = 0;
        int failed = 0;
        for (SkillEntity skill : currentSkills == null ? List.<SkillEntity>of() : currentSkills) {
            if (skill.getId() == null || snapshotSkillIds.contains(skill.getId())) {
                continue;
            }
            try {
                SkillWorkspaceManager.ArchiveResult fs = workspaceManager.archiveWorkspace(
                        skill.getName(), workspaceId);
                if (fs == SkillWorkspaceManager.ArchiveResult.FAILED) {
                    throw new IllegalStateException("Failed to archive workspace for '" + skill.getName() + "'");
                }
                int rows = skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                        .eq(SkillEntity::getId, skill.getId())
                        .eq(SkillEntity::getWorkspaceId, workspaceId)
                        .set(SkillEntity::getEnabled, false)
                        .set(SkillEntity::getLifecycleState, "archived")
                        .set(SkillEntity::getArchivedAt, LocalDateTime.now()));
                if (rows != 1) {
                    if (fs == SkillWorkspaceManager.ArchiveResult.MOVED) {
                        workspaceManager.restoreWorkspace(skill.getName(), workspaceId);
                    }
                    throw new IllegalStateException("archive update affected " + rows + " rows");
                }
                archived++;
            } catch (Exception e) {
                failed++;
                log.warn("[SkillSnapshot] Failed to archive post-snapshot skill id={}: {}",
                        skill.getId(), e.getMessage());
            }
        }
        return new ArchiveAdditionsResult(archived, failed);
    }

    private record ArchiveAdditionsResult(int archived, int failed) {}

    /**
     * Restore the filesystem half before publishing the corresponding DB row.
     * New snapshots remember whether a convention workspace existed; legacy
     * snapshots fall back to the current/archive state so they remain usable.
     */
    private void restoreWorkspaceState(SkillEntity current, JsonNode node, Long workspaceId) {
        String name = textOrNull(node, "name");
        if (name == null || name.isBlank()) {
            name = current.getName();
        }
        String content = textOrNull(node, "skillContent");
        String desiredState = textOrNull(node, "lifecycleState");
        boolean desiredArchived = "archived".equals(desiredState);
        boolean workspacePresent = node.has("workspacePresent")
                ? node.path("workspacePresent").asBoolean(false)
                : workspaceManager.conventionWorkspaceExists(name, workspaceId)
                    || "archived".equals(current.getLifecycleState());

        if (desiredArchived) {
            if (workspaceManager.conventionWorkspaceExists(name, workspaceId)) {
                if (content != null && workspaceManager.exportToWorkspace(name, content, workspaceId) == null) {
                    throw new IllegalStateException("Failed to restore workspace content for '" + name + "'");
                }
                if (workspaceManager.archiveWorkspace(name, workspaceId)
                        == SkillWorkspaceManager.ArchiveResult.FAILED) {
                    throw new IllegalStateException("Failed to restore archived workspace for '" + name + "'");
                }
            }
            return;
        }

        if (!workspacePresent) {
            if (workspaceManager.conventionWorkspaceExists(name, workspaceId)
                    && workspaceManager.archiveWorkspace(name, workspaceId)
                        == SkillWorkspaceManager.ArchiveResult.FAILED) {
                // Preserve the post-snapshot directory in .archived rather than
                // deleting it; the pre-restore snapshot can then roll forward.
                throw new IllegalStateException("Failed to remove post-snapshot workspace for '" + name + "'");
            }
            return;
        }

        SkillWorkspaceManager.RestoreResult moved = workspaceManager.restoreWorkspace(name, workspaceId);
        if (moved == SkillWorkspaceManager.RestoreResult.FAILED) {
            throw new IllegalStateException("Failed to restore workspace for '" + name + "'");
        }
        if (content != null && workspaceManager.exportToWorkspace(name, content, workspaceId) == null) {
            throw new IllegalStateException("Failed to restore workspace content for '" + name + "'");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asBoolean();
    }

    private static LocalDateTime dateTimeOrNull(JsonNode node, String field) {
        String value = textOrNull(node, field);
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private static void putDateTime(ObjectNode node, String field, LocalDateTime value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    private static long normalizeWorkspaceId(Long workspaceId) {
        return workspaceId != null && workspaceId > 0 ? workspaceId : 1L;
    }
}
