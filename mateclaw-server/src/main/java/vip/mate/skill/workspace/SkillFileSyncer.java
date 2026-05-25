package vip.mate.skill.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mirrors canonical {@code mate_skill_file} rows down to each node's local
 * workspace cache so {@code scripts/} and {@code references/} files exist
 * on disk wherever the skill might run.
 *
 * <p>Also runs a one-time backfill: for any skill that has on-disk files
 * but no DB rows (typically pre-V112 installs), the local files are read
 * up into the DB so the canonical store catches up to reality. Backfill
 * is content-hash idempotent and safe to invoke repeatedly.
 *
 * <p>Triggered:
 * <ul>
 *   <li>At startup, after the bundled-skill syncer (see
 *       {@link SkillWorkspaceBootstrapRunner}).</li>
 *   <li>On-demand via the admin endpoint {@code POST /api/v1/skills/{id}/sync-files}.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillFileSyncer {

    private final SkillService skillService;
    private final SkillFileService skillFileService;
    private final SkillWorkspaceManager workspaceManager;

    /** Aggregate counters for one full sync pass. */
    public record SyncReport(int skillsConsidered,
                             int skillsBackfilled,
                             int filesMaterialized,
                             int filesAlreadyCurrent,
                             int filesBackfilledFromDisk) {}

    /** Sync every active skill once. Idempotent. */
    public SyncReport syncAll() {
        List<SkillEntity> skills = skillService.listSkills();
        int considered = 0;
        int backfilled = 0;
        int materialized = 0;
        int current = 0;
        int diskBackfilled = 0;

        for (SkillEntity skill : skills) {
            if (skill.getId() == null || skill.getName() == null) continue;
            considered++;
            var per = syncOne(skill);
            materialized += per.filesMaterialized();
            current += per.filesAlreadyCurrent();
            diskBackfilled += per.filesBackfilledFromDisk();
            if (per.didBackfillFromDisk()) backfilled++;
        }

        if (considered > 0) {
            log.info("SkillFileSyncer pass: skills={}, materialized={}, current={}, " +
                            "backfilledFromDisk(skills={}, files={})",
                    considered, materialized, current, backfilled, diskBackfilled);
        }
        return new SyncReport(considered, backfilled, materialized, current, diskBackfilled);
    }

    /** Per-skill sync outcome. */
    public record PerSkillReport(int filesMaterialized,
                                 int filesAlreadyCurrent,
                                 int filesBackfilledFromDisk,
                                 boolean didBackfillFromDisk) {}

    /**
     * Sync a single skill: backfill DB from FS if DB is empty and FS has
     * files, then materialize DB rows down to FS so any missing/stale files
     * are restored.
     */
    public PerSkillReport syncOne(SkillEntity skill) {
        Path workspaceDir = workspaceManager.resolveConventionPath(skill.getName());
        List<SkillFileEntity> dbFiles = skillFileService.listBySkillId(skill.getId());

        boolean didBackfill = false;
        int backfilled = 0;
        if (dbFiles.isEmpty()) {
            backfilled = backfillFromDiskIfNeeded(skill, workspaceDir);
            if (backfilled > 0) {
                didBackfill = true;
                dbFiles = skillFileService.listBySkillId(skill.getId());
            }
        }

        int materialized = 0;
        int alreadyCurrent = 0;
        for (SkillFileEntity row : dbFiles) {
            switch (materializeOne(workspaceDir, row)) {
                case WROTE -> materialized++;
                case CURRENT -> alreadyCurrent++;
                case SKIPPED -> {
                    /* unsafe path / IO failure already logged */
                }
            }
        }

        return new PerSkillReport(materialized, alreadyCurrent, backfilled, didBackfill);
    }

    private enum MaterializeOutcome { WROTE, CURRENT, SKIPPED }

    private MaterializeOutcome materializeOne(Path workspaceDir, SkillFileEntity row) {
        String relative = row.getFilePath();
        if (relative == null || relative.isBlank()) return MaterializeOutcome.SKIPPED;
        if (!relative.startsWith("references/") && !relative.startsWith("scripts/")) {
            log.warn("Skipping skill_file row {} — path outside scripts/ or references/: {}",
                    row.getId(), relative);
            return MaterializeOutcome.SKIPPED;
        }
        if (relative.contains("..")) {
            log.warn("Skipping skill_file row {} — suspicious path: {}", row.getId(), relative);
            return MaterializeOutcome.SKIPPED;
        }

        Path target = workspaceDir.resolve(relative).normalize();
        if (!target.startsWith(workspaceDir.normalize())) {
            log.warn("Skipping skill_file row {} — escapes workspace: {}", row.getId(), relative);
            return MaterializeOutcome.SKIPPED;
        }

        try {
            String content = row.getContent() == null ? "" : row.getContent();
            if (Files.exists(target)) {
                String onDisk = Files.readString(target, StandardCharsets.UTF_8);
                if (SkillFileService.sha256Hex(onDisk).equals(row.getSha256())) {
                    return MaterializeOutcome.CURRENT;
                }
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return MaterializeOutcome.WROTE;
        } catch (IOException e) {
            log.warn("Failed to materialize skill_file {} → {}: {}", row.getId(), target, e.getMessage());
            return MaterializeOutcome.SKIPPED;
        }
    }

    /**
     * One-time ingestion of pre-V112 on-disk files into the canonical
     * {@code mate_skill_file} table. Only runs when the skill has zero
     * file rows; subsequent installs go through the installer's normal
     * write-to-both-stores path.
     */
    private int backfillFromDiskIfNeeded(SkillEntity skill, Path workspaceDir) {
        if (!Files.exists(workspaceDir) || !Files.isDirectory(workspaceDir)) return 0;

        List<Path> roots = new ArrayList<>(2);
        Path scripts = workspaceDir.resolve("scripts");
        Path references = workspaceDir.resolve("references");
        if (Files.isDirectory(scripts)) roots.add(scripts);
        if (Files.isDirectory(references)) roots.add(references);
        if (roots.isEmpty()) return 0;

        java.util.Map<String, String> ingested = new java.util.LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Path root : roots) {
            String prefix = workspaceDir.relativize(root).toString().replace('\\', '/') + "/";
            try (var stream = Files.walk(root)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                for (Path f : files) {
                    String relative = workspaceDir.relativize(f).toString().replace('\\', '/');
                    if (!relative.startsWith(prefix)) continue;
                    if (!seen.add(relative)) continue;
                    try {
                        String content = Files.readString(f, StandardCharsets.UTF_8);
                        ingested.put(relative, content);
                    } catch (IOException e) {
                        log.warn("Backfill skipped {} (read failed: {})", f, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("Backfill walk failed for {}: {}", root, e.getMessage());
            }
        }

        if (ingested.isEmpty()) return 0;
        skillFileService.applyBundleFiles(skill.getId(), ingested, false);
        log.info("Backfilled {} bundle file(s) into mate_skill_file for skill '{}' (id={})",
                ingested.size(), skill.getName(), skill.getId());

        // Touch the workspace event so other observers (e.g. runtime cache) refresh.
        // Use a synthetic event type — INSTALLED is the closest existing match.
        skill.setUpdateTime(LocalDateTime.now());
        return ingested.size();
    }
}
