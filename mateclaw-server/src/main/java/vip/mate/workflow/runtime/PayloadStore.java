package vip.mate.workflow.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.workflow.model.WorkflowPayloadEntity;
import vip.mate.workflow.repository.WorkflowPayloadMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Write-through facade over {@code mate_workflow_payload}. Three-tier storage:
 *
 * <ul>
 *   <li><b>inline</b> (≤ {@code inlineMaxBytes}, default 256KB) — bytes go into
 *       {@code content_bytes}. Cheapest and lets one DB query reconstruct the
 *       payload.</li>
 *   <li><b>fs</b> (≤ {@code hardCapBytes}) — bytes go to a workspace-scoped
 *       file under {@code mateclaw.workflow.payload.fs.root}; the row stores
 *       only the relative path in {@code storage_ref}. Default for any
 *       deployment that hasn't enabled a configured object-storage provider.</li>
 *   <li>Anything above the hard cap is rejected at write time so a runaway
 *       fan-out can't fill the disk silently.</li>
 * </ul>
 *
 * <p>{@code s3} / {@code oss} columns exist in the schema but the v0 ship
 * only writes {@code inline} or {@code fs}; provider configuration ships in
 * v1. The fs tier is what unblocks local dev / docker / private deploys
 * that don't have an object store configured.
 */
@Slf4j
@Service
public class PayloadStore {

    private static final String SCHEME = "mwf://";
    private static final String STORAGE_KIND_INLINE = "inline";
    private static final String STORAGE_KIND_FS = "fs";

    private final WorkflowPayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;
    private final long inlineMaxBytes;
    private final long hardCapBytes;
    private final Path fsRoot;
    private final long retentionDays;

    public PayloadStore(WorkflowPayloadMapper payloadMapper,
                        ObjectMapper objectMapper,
                        @Value("${mateclaw.workflow.payload.inline-max-bytes:262144}") long inlineMaxBytes,
                        @Value("${mateclaw.workflow.payload.hard-cap-bytes:52428800}") long hardCapBytes,
                        @Value("${mateclaw.workflow.payload.fs.root:./data/workflow-payload}") String fsRoot,
                        @Value("${mateclaw.workflow.payload.retention-days:30}") long retentionDays) {
        this.payloadMapper = payloadMapper;
        this.objectMapper = objectMapper;
        this.inlineMaxBytes = inlineMaxBytes;
        this.hardCapBytes = hardCapBytes;
        this.fsRoot = Path.of(fsRoot).toAbsolutePath();
        this.retentionDays = retentionDays;
    }

    /** Store a UTF-8 string payload and return its stable URI. */
    public String storeString(long workspaceId, String body, String contentType) {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        return storeBytes(workspaceId, bytes, contentType == null ? "text/plain" : contentType);
    }

    /** JSON-encode {@code value} and store it. {@code contentType} is fixed to {@code application/json}. */
    public String storeJson(long workspaceId, Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            return storeBytes(workspaceId, bytes, "application/json");
        } catch (JsonProcessingException e) {
            throw new PayloadStoreException("failed to serialize payload as JSON: " + e.getMessage(), e);
        }
    }

    /** Store raw bytes and return the URI. Routes by size: inline → fs → reject. */
    public String storeBytes(long workspaceId, byte[] bytes, String contentType) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > hardCapBytes) {
            throw new PayloadStoreException("payload exceeds hard cap of "
                    + hardCapBytes + " bytes (got " + bytes.length + ")");
        }
        String uri = SCHEME + workspaceId + "/" + UUID.randomUUID();

        WorkflowPayloadEntity row = new WorkflowPayloadEntity();
        row.setPayloadUri(uri);
        row.setWorkspaceId(workspaceId);
        row.setContentType(contentType);
        row.setSha256(sha256Hex(bytes));
        row.setSizeBytes((long) bytes.length);
        row.setCreatedAt(LocalDateTime.now());

        if (bytes.length <= inlineMaxBytes) {
            row.setContentBytes(bytes);
            row.setStorageKind(STORAGE_KIND_INLINE);
        } else {
            // Spill to filesystem so we don't bloat the DB row. Path layout
            // is {fsRoot}/{workspaceId}/{first2chars}/{uuid} so a single
            // workspace can't pile millions of files into one directory.
            String relative = workspaceId + "/" + uri.substring(uri.length() - 2)
                    + "/" + uri.substring(uri.length() - Math.min(36, uri.length()));
            Path target = fsRoot.resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            } catch (IOException e) {
                throw new PayloadStoreException("failed to write fs payload " + uri
                        + ": " + e.getMessage(), e);
            }
            row.setStorageKind(STORAGE_KIND_FS);
            row.setStorageRef(relative);
        }

        payloadMapper.insert(row);
        return uri;
    }

    /** Resolve a payload URI to its raw bytes; throws when the URI is unknown. */
    public byte[] readBytes(String payloadUri) {
        WorkflowPayloadEntity row = lookup(payloadUri);
        if (STORAGE_KIND_FS.equals(row.getStorageKind())) {
            try {
                return Files.readAllBytes(fsRoot.resolve(row.getStorageRef()));
            } catch (IOException e) {
                throw new PayloadStoreException("failed to read fs payload " + payloadUri
                        + ": " + e.getMessage(), e);
            }
        }
        return row.getContentBytes() == null ? new byte[0] : row.getContentBytes();
    }

    /** Resolve a payload URI to its UTF-8 decoded string body. */
    public String readString(String payloadUri) {
        return new String(readBytes(payloadUri), StandardCharsets.UTF_8);
    }

    /** Resolve a payload URI to its JSON body parsed back into the requested shape. */
    public <T> T readJson(String payloadUri, Class<T> type) {
        try {
            return objectMapper.readValue(readBytes(payloadUri), type);
        } catch (Exception e) {
            throw new PayloadStoreException(
                    "failed to deserialize payload " + payloadUri + " as " + type.getSimpleName()
                            + ": " + e.getMessage(),
                    e);
        }
    }

    private WorkflowPayloadEntity lookup(String payloadUri) {
        WorkflowPayloadEntity row = payloadMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowPayloadEntity>()
                        .eq(WorkflowPayloadEntity::getPayloadUri, payloadUri));
        if (row == null) {
            throw new PayloadStoreException("payload not found: " + payloadUri);
        }
        return row;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is part of the JCA standard set — should never happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Drop payload rows older than {@code retention-days}. Tombstones the
     * filesystem files for fs-tier payloads in the same pass so the disk
     * doesn't keep growing once the DB row is gone. Returns the number of
     * rows actually deleted; primarily for tests + log lines.
     *
     * <p>v0 deletes by absolute age rather than walking the
     * {@code mate_workflow_run} graph — runs that finish stay queryable
     * for {@code retention-days} from the payload-write timestamp, which
     * is "good enough" for an alpha. v1 can switch to run-state-driven
     * GC ({@code state IN ('succeeded','failed') AND completed_at &lt;
     * threshold}) once the operator UI exposes a "preserve forever" flag
     * for runs the customer wants kept.
     */
    public int sweepExpired() {
        if (retentionDays <= 0) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<WorkflowPayloadEntity> stale = payloadMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowPayloadEntity>()
                        .lt(WorkflowPayloadEntity::getCreatedAt, cutoff));
        if (stale.isEmpty()) return 0;
        int deleted = 0;
        for (WorkflowPayloadEntity row : stale) {
            // Best-effort fs cleanup before the row goes — the row IS the
            // foreign key the file is reachable through; if the row goes
            // first the file becomes orphaned.
            if (STORAGE_KIND_FS.equals(row.getStorageKind()) && row.getStorageRef() != null) {
                try {
                    Files.deleteIfExists(fsRoot.resolve(row.getStorageRef()));
                } catch (IOException e) {
                    log.warn("[PayloadStore] fs delete failed for {}: {}",
                            row.getStorageRef(), e.getMessage());
                }
            }
            try {
                payloadMapper.deleteById(row.getId());
                deleted++;
            } catch (Exception e) {
                log.warn("[PayloadStore] db delete failed for payload {}: {}",
                        row.getPayloadUri(), e.getMessage());
            }
        }
        return deleted;
    }

    /**
     * Periodic sweep — runs once an hour by default. Tunable via
     * {@code mateclaw.workflow.payload.sweep-interval-ms}. Skips a tick
     * silently when retentionDays = 0 (operator opted out of GC).
     */
    @Scheduled(
            fixedDelayString = "${mateclaw.workflow.payload.sweep-interval-ms:3600000}",
            initialDelayString = "${mateclaw.workflow.payload.sweep-initial-delay-ms:600000}")
    public void scheduledSweepExpired() {
        try {
            int dropped = sweepExpired();
            if (dropped > 0) {
                log.info("[PayloadStore] swept {} expired payload rows (retention={} days)",
                        dropped, retentionDays);
            }
        } catch (Exception e) {
            log.warn("[PayloadStore] periodic sweep failed: {}", e.getMessage());
        }
    }

    /** Wrapper exception for payload-store failures. */
    public static class PayloadStoreException extends RuntimeException {
        public PayloadStoreException(String message) { super(message); }
        public PayloadStoreException(String message, Throwable cause) { super(message, cause); }
    }
}
