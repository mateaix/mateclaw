package vip.mate.skill.runtime;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Reconciles a skill's SKILL.md between its two stores: the canonical
 * {@code mate_skill.skill_content} column and the convention-workspace
 * file cache ({@code {workspace-root}/{name}/SKILL.md}).
 *
 * <p>The database column is the single source of truth; the workspace file
 * is a materialized cache kept for script execution and direct-file
 * tooling. Because agents (via shell tools) and operators may still edit
 * the file in place, reconciliation is a three-way sync anchored on a
 * sidecar marker ({@value #SYNC_MARKER}) that records the SHA-256 of the
 * content at the last successful sync:
 *
 * <ul>
 *   <li>DB == file → in sync; heal a missing/stale marker.</li>
 *   <li>File missing/blank, DB has content → materialize DB → file.
 *       A blank file is never ingested over non-blank DB content.</li>
 *   <li>DB blank, file has content → backfill file → DB (covers installs
 *       that predate the canonical column).</li>
 *   <li>File changed since last sync, DB unchanged → ingest file → DB.
 *       This is what makes shell/agent edits to the file visible to
 *       DB-reading consumers (admin console, API).</li>
 *   <li>DB changed since last sync, file unchanged → materialize DB → file.
 *       This heals nodes whose workspace export was missed or failed.</li>
 *   <li>No marker and both sides non-blank but different (legacy state) →
 *       the file wins once: prior releases resolved runtime content from
 *       the directory, so the file reflects what was actually in effect.</li>
 *   <li>Both sides changed since last sync → DB wins; the losing file is
 *       kept as {@code SKILL.md.bak} before being overwritten.</li>
 * </ul>
 *
 * <p>All writes are idempotent and failure-tolerant: an IO or DB error
 * logs a warning and leaves the marker untouched, so the next resolve
 * pass retries the same reconciliation.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillContentReconciler {

    /** Sidecar file holding the SHA-256 of SKILL.md at the last sync. */
    public static final String SYNC_MARKER = ".skillmd.sha256";

    /** Backup name for a file-side edit that loses a two-sided conflict. */
    static final String CONFLICT_BACKUP = "SKILL.md.bak";

    private final SkillMapper skillMapper;

    /** What the reconciliation pass did. */
    public enum Action {
        /** Both stores already held the same content. */
        IN_SYNC,
        /** DB was blank; the workspace file was ingested into the DB. */
        BACKFILLED_TO_DB,
        /** The workspace file changed; its content was written to the DB. */
        INGESTED_TO_DB,
        /** The DB changed; its content was written to the workspace file. */
        MATERIALIZED_TO_FS,
        /** Both changed; DB won and the file edit was backed up. */
        CONFLICT_DB_WON,
        /** A store write failed; stores may still diverge. Retried next pass. */
        FAILED
    }

    /** Reconciled content (the value both stores now agree on) + what happened. */
    public record Outcome(String content, Action action) {}

    /**
     * Reconcile {@code entity}'s skill_content with the SKILL.md inside
     * {@code workspaceDir}. On an ingest/backfill the passed entity's
     * in-memory {@code skillContent} is updated too, so downstream resolve
     * stages and diff-based write-backs see the merged value.
     */
    public Outcome reconcile(SkillEntity entity, Path workspaceDir) {
        String dbContent = entity.getSkillContent() == null ? "" : entity.getSkillContent();
        Path skillMd = workspaceDir.resolve("SKILL.md");
        String fsContent = readFileQuietly(skillMd);

        String dbHash = sha256(dbContent);
        String fsHash = sha256(fsContent);
        Path marker = workspaceDir.resolve(SYNC_MARKER);
        String syncedHash = readMarker(marker);

        if (dbHash.equals(fsHash)) {
            if (!dbHash.equals(syncedHash)) {
                writeMarkerQuietly(marker, dbHash);
            }
            return new Outcome(dbContent, Action.IN_SYNC);
        }

        if (fsContent.isBlank()) {
            // File missing or empty while the DB has content: always
            // materialize. Blank file content is never treated as an edit —
            // that guard blocks the same wipe-on-empty scenario the bundle
            // apply path protects against.
            return materialize(entity, skillMd, marker, dbContent, dbHash, Action.MATERIALIZED_TO_FS);
        }

        if (dbContent.isBlank()) {
            return ingest(entity, marker, fsContent, fsHash, Action.BACKFILLED_TO_DB);
        }

        // Both sides non-blank and different — use the marker to decide
        // which side moved since the last sync.
        if (syncedHash == null || syncedHash.equals(dbHash)) {
            // DB unchanged since last sync (or legacy pre-marker state,
            // where the directory was the effective runtime source):
            // the file edit is the newer fact — ingest it.
            return ingest(entity, marker, fsContent, fsHash, Action.INGESTED_TO_DB);
        }
        if (syncedHash.equals(fsHash)) {
            // File unchanged since last sync; the DB moved — materialize.
            return materialize(entity, skillMd, marker, dbContent, dbHash, Action.MATERIALIZED_TO_FS);
        }

        // Both sides changed since the last sync. The DB is canonical, so
        // it wins; keep the losing file edit next to the file for manual
        // recovery instead of silently discarding it.
        backupQuietly(skillMd, workspaceDir.resolve(CONFLICT_BACKUP));
        log.warn("SKILL.md conflict for skill '{}': both DB and workspace file changed since last sync; "
                + "DB content wins, file edit saved as {}", entity.getName(), CONFLICT_BACKUP);
        return materialize(entity, skillMd, marker, dbContent, dbHash, Action.CONFLICT_DB_WON);
    }

    /**
     * Mirror a file-authoritative skill's content into the DB column so
     * DB-reading consumers (admin console, API) see what the runtime
     * actually executes. Used for skills with an explicitly configured
     * {@code skillDir}, where the user-managed directory — not the DB —
     * is the source of truth and no marker/backfill dance applies.
     */
    public void mirrorToDb(SkillEntity entity, String fsContent) {
        if (fsContent == null || fsContent.isBlank()) return;
        String dbContent = entity.getSkillContent() == null ? "" : entity.getSkillContent();
        if (fsContent.equals(dbContent)) return;
        if (writeDb(entity, fsContent)) {
            log.info("Mirrored directory SKILL.md into skill_content for skill '{}' (explicit skillDir)",
                    entity.getName());
        }
    }

    private Outcome ingest(SkillEntity entity, Path marker, String fsContent, String fsHash, Action action) {
        if (!writeDb(entity, fsContent)) {
            return new Outcome(fsContent, Action.FAILED);
        }
        writeMarkerQuietly(marker, fsHash);
        log.info("Ingested workspace SKILL.md into skill_content for skill '{}' ({})",
                entity.getName(), action);
        return new Outcome(fsContent, action);
    }

    private Outcome materialize(SkillEntity entity, Path skillMd, Path marker,
                                String dbContent, String dbHash, Action action) {
        try {
            Files.createDirectories(skillMd.getParent());
            Files.writeString(skillMd, dbContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to materialize SKILL.md for skill '{}' → {}: {}",
                    entity.getName(), skillMd, e.getMessage());
            return new Outcome(dbContent, Action.FAILED);
        }
        writeMarkerQuietly(marker, dbHash);
        log.info("Materialized skill_content to workspace SKILL.md for skill '{}' ({})",
                entity.getName(), action);
        return new Outcome(dbContent, action);
    }

    /**
     * Column-whitelisted DB write. {@code SkillEntity} declares several
     * {@code FieldStrategy.ALWAYS} columns, so a partial
     * {@code updateById} would null them out — the update wrapper touches
     * only {@code skill_content} and {@code update_time}.
     */
    private boolean writeDb(SkillEntity entity, String content) {
        if (entity.getId() == null) return false;
        try {
            skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                    .eq(SkillEntity::getId, entity.getId())
                    .set(SkillEntity::getSkillContent, content)
                    .set(SkillEntity::getUpdateTime, LocalDateTime.now()));
            entity.setSkillContent(content);
            return true;
        } catch (Exception e) {
            log.warn("Failed to write skill_content for skill '{}': {}", entity.getName(), e.getMessage());
            return false;
        }
    }

    private String readFileQuietly(Path file) {
        if (!Files.exists(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
            return "";
        }
    }

    private String readMarker(Path marker) {
        if (!Files.exists(marker)) return null;
        try {
            String value = Files.readString(marker, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            log.warn("Failed to read sync marker {}: {}", marker, e.getMessage());
            return null;
        }
    }

    private void writeMarkerQuietly(Path marker, String hash) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, hash, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write sync marker {}: {}", marker, e.getMessage());
        }
    }

    private void backupQuietly(Path source, Path backup) {
        try {
            if (Files.exists(source)) {
                Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to back up {} → {}: {}", source, backup, e.getMessage());
        }
    }

    static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
