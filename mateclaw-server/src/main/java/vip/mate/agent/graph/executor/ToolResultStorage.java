package vip.mate.agent.graph.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.context.StructuredTruncator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tool-result spill store implementing layers 2 and 3 of the RFC-008 Phase 3
 * three-layer budget. Layer 1 (per-tool cap) lives inside individual tools.
 *
 * <p><b>Layer 2 — per-result spill</b> ({@link #persistIfOversized}): a single
 * tool result that exceeds the configured threshold is written to disk and
 * the in-memory copy is replaced with a short preview plus a pointer line so
 * the LLM can use {@code read_file} to retrieve the full text on demand.</p>
 *
 * <p><b>Layer 3 — per-turn aggregate budget</b>
 * ({@link #enforceTurnBudget}): after every tool in one turn has executed,
 * if the combined response size still exceeds the turn budget, the largest
 * non-spilled responses are spilled in turn until the aggregate fits.</p>
 *
 * <p>Spill files live under one of, in order:</p>
 * <ol>
 *   <li>{@code ToolResultProperties.storageBaseDir} when explicitly set</li>
 *   <li>{@code <workspaceBasePath>/.mateclaw/tool-results/<conversationId>/} when a workspace is bound</li>
 *   <li>{@code ${java.io.tmpdir}/mateclaw/tool-results/<conversationId>/} as the universal fallback</li>
 * </ol>
 *
 * <p>Failures (disk full, IO error) degrade silently: the original result is
 * returned unchanged so the agent keeps working. Errors are logged at WARN.</p>
 *
 * <p>This class does <b>not</b> manage GC. Spill files accumulate until manually
 * cleaned. A scheduled cleanup job is tracked as a Phase 3 follow-up.</p>
 */
@Slf4j
@Component
@Configuration
@EnableConfigurationProperties(ToolResultProperties.class)
public class ToolResultStorage {

    /** Marker placed in the in-context preview so callers and tools can recognize spill output. */
    public static final String SPILL_MARKER_PREFIX = "[mate-tool-result-spill]";

    private final ToolResultProperties props;
    /** Cached at construction; refreshed lazily if the underlying list mutates (rare). */
    private volatile java.util.Set<String> excludedToolsSnapshot;

    /** D-6: monotonically increasing spill counter for observability. */
    private final java.util.concurrent.atomic.AtomicLong spillCount = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Workspace roots observed during this JVM's lifetime. Populated every
     * time a successful spill resolves a base directory; consulted by the
     * scheduled retention sweep and by {@link #purgeConversation} so we
     * don't have to query the database for every workspace path. Cross-JVM
     * orphans are not covered — that is documented in the cleanup javadoc.
     */
    private final java.util.Set<Path> observedRoots = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ToolResultStorage(ToolResultProperties props) {
        this.props = props;
        this.excludedToolsSnapshot = props.excludedToolsSet();
    }

    /** D-6: current cumulative spill count (monotonically increasing). */
    public long getSpillCount() {
        return spillCount.get();
    }

    /**
     * Returns true when {@code toolName} is in the configured exclusion list.
     * Excluded tools (typically retrieval tools like {@code read_file}) are
     * never spilled — spilling their output would create a recursion where
     * the agent reads a spill path and produces yet another spill.
     */
    private boolean isExcluded(String toolName) {
        if (toolName == null) return false;
        java.util.Set<String> snap = excludedToolsSnapshot;
        java.util.Set<String> live = props.excludedToolsSet();
        if (live != snap && !live.equals(snap)) {
            this.excludedToolsSnapshot = live;
            snap = live;
        }
        return snap.contains(toolName);
    }

    /**
     * Layer 2. If {@code result} exceeds the per-result threshold, write the full
     * text to a spill file and return a preview-plus-pointer string. Otherwise
     * return the original result unchanged.
     *
     * @param result          the raw tool output (may be null)
     * @param toolName        used in the preview header so the LLM knows which tool produced it
     * @param toolUseId       unique within a conversation; becomes the spill file's basename
     * @param conversationId  scopes spill files by conversation
     * @param workspaceBasePath agent's workspace base path; may be null/blank
     */
    public String persistIfOversized(String result, String toolName, String toolUseId,
                                     String conversationId, String workspaceBasePath) {
        if (!props.isEnabled() || result == null) {
            return result;
        }
        if (isExcluded(toolName)) {
            // Retrieval-style tool — never spill, would cause read-back recursion.
            return result;
        }
        if (result.length() <= props.getPerResultThresholdChars()) {
            return result;
        }
        Path file = spillFor(conversationId, toolUseId, workspaceBasePath);
        if (file == null) {
            return result;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, result, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            log.warn("[ToolResultStorage] spill write failed for tool={} convId={} ({}); keeping original",
                    toolName, conversationId, ioe.getMessage());
            return result;
        }
        long count = spillCount.incrementAndGet();
        log.info("[ToolResultStorage] spill #{}: tool={} chars={} convId={}", count, toolName, result.length(), conversationId);
        return buildPreview(result, toolName, file);
    }

    /**
     * Layer 3. Walk the responses; if their aggregate length exceeds the turn
     * budget, spill the largest remaining non-spilled result and recompute.
     * Mutates the returned list in place by replacing oversized responses.
     */
    public List<ToolResponseMessage.ToolResponse> enforceTurnBudget(
            List<ToolResponseMessage.ToolResponse> responses,
            String conversationId,
            String workspaceBasePath) {
        if (!props.isEnabled() || responses == null || responses.isEmpty()) {
            return responses;
        }
        int budget = props.getPerTurnBudgetChars();
        int aggregate = aggregateSize(responses);
        if (aggregate <= budget) {
            return responses;
        }

        log.info("[ToolResultStorage] turn budget exceeded: {} chars > {} (responses={})",
                aggregate, budget, responses.size());

        List<ToolResponseMessage.ToolResponse> mutable = new ArrayList<>(responses);

        while (aggregate > budget) {
            // Find the largest response that has not yet been spilled and is
            // not produced by an excluded (retrieval-style) tool.
            int targetIdx = -1;
            int targetLen = -1;
            for (int i = 0; i < mutable.size(); i++) {
                ToolResponseMessage.ToolResponse r = mutable.get(i);
                String body = r.responseData();
                if (body == null || body.startsWith(SPILL_MARKER_PREFIX)) continue;
                if (isExcluded(r.name())) continue;     // retrieval tools must not be spilled
                if (body.length() > targetLen) {
                    targetLen = body.length();
                    targetIdx = i;
                }
            }
            if (targetIdx < 0) {
                int compactedIdx = compactLargestExcludedResult(mutable);
                if (compactedIdx >= 0) {
                    aggregate = aggregateSize(mutable);
                    continue;
                }
                log.warn("[ToolResultStorage] aggregate still {} chars after spilling/compacting everything eligible",
                        aggregate);
                break;
            }
            ToolResponseMessage.ToolResponse target = mutable.get(targetIdx);
            Path file = spillFor(conversationId, target.id(), workspaceBasePath);
            if (file == null) {
                break;
            }
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, target.responseData(), StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                log.warn("[ToolResultStorage] spill write failed during turn budget enforcement: {}",
                        ioe.getMessage());
                break;
            }
            String preview = buildPreview(target.responseData(), target.name(), file);
            mutable.set(targetIdx, new ToolResponseMessage.ToolResponse(target.id(), target.name(), preview));
            aggregate = aggregateSize(mutable);
        }
        return mutable;
    }

    private int compactLargestExcludedResult(List<ToolResponseMessage.ToolResponse> mutable) {
        int targetIdx = -1;
        int targetLen = props.getExcludedToolInlineChars();
        for (int i = 0; i < mutable.size(); i++) {
            ToolResponseMessage.ToolResponse r = mutable.get(i);
            String body = r.responseData();
            if (body == null || body.startsWith(SPILL_MARKER_PREFIX)) continue;
            if (!isExcluded(r.name())) continue;
            if (body.length() > targetLen) {
                targetLen = body.length();
                targetIdx = i;
            }
        }
        if (targetIdx < 0) {
            return -1;
        }
        ToolResponseMessage.ToolResponse target = mutable.get(targetIdx);
        String compacted = compactInline(target.responseData(), target.name(), props.getExcludedToolInlineChars());
        mutable.set(targetIdx, new ToolResponseMessage.ToolResponse(target.id(), target.name(), compacted));
        log.info("[ToolResultStorage] compacted excluded tool result: tool={} chars={} -> {}",
                target.name(), targetLen, compacted.length());
        return targetIdx;
    }

    static String compactInline(String body, String toolName, int maxChars) {
        if (body == null || body.length() <= maxChars) {
            return body;
        }
        String marker = "\n\n... [tool result compacted for model context: tool="
                + toolName + ", original_chars=" + body.length() + ". "
                + StructuredTruncator.FIDELITY_NOTE + "] ...\n\n";
        int available = Math.max(200, maxChars - marker.length());
        int headLen = Math.max(100, (int) (available * 0.45));
        int tailLen = Math.max(100, available - headLen);
        if (headLen + tailLen >= body.length()) {
            return body;
        }
        return StructuredTruncator.truncate(body, headLen, tailLen, marker);
    }

    private static int aggregateSize(List<ToolResponseMessage.ToolResponse> responses) {
        int sum = 0;
        for (ToolResponseMessage.ToolResponse r : responses) {
            if (r.responseData() != null) sum += r.responseData().length();
        }
        return sum;
    }

    private String buildPreview(String fullResult, String toolName, Path spillFile) {
        // Snap the preview to a complete JSON element so the model never sees a value
        // severed mid-token (which invites it to fabricate the omitted fields).
        String head = StructuredTruncator.headSlice(fullResult, props.getPreviewHeadChars());
        return SPILL_MARKER_PREFIX
                + " tool=" + toolName
                + " full_chars=" + fullResult.length()
                + " path=" + spillFile.toAbsolutePath()
                + "\n[Preview — first " + head.length() + " of " + fullResult.length()
                + " chars. The preview is INCOMPLETE: use read_file with the path above to "
                + "retrieve the full result. Do NOT infer or fabricate the omitted content.]\n"
                + head
                + "\n…[truncated]";
    }

    /**
     * Resolve the spill file path for a given (conversationId, toolUseId).
     * Returns {@code null} if no usable directory can be determined.
     */
    private Path spillFor(String conversationId, String toolUseId, String workspaceBasePath) {
        String safeConv = sanitize(conversationId);
        String safeId = sanitize(toolUseId);
        if (safeId.isEmpty()) {
            safeId = "noid-" + System.nanoTime();
        }
        Path base = resolveBaseDir(workspaceBasePath);
        if (base == null) return null;
        return base.resolve(safeConv).resolve(safeId + ".txt");
    }

    private Path resolveBaseDir(String workspaceBasePath) {
        Path base;
        if (!props.getStorageBaseDir().isEmpty()) {
            base = Paths.get(props.getStorageBaseDir());
        } else if (workspaceBasePath != null && !workspaceBasePath.isBlank()) {
            base = Paths.get(workspaceBasePath, ".mateclaw", "tool-results");
        } else {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp == null || tmp.isEmpty()) return null;
            base = Paths.get(tmp, "mateclaw", "tool-results");
        }
        // Register so the retention sweep and conversation-delete hook can
        // reach this root even when the workspace path is no longer in scope.
        observedRoots.add(base);
        return base;
    }

    /**
     * Roots currently known to this instance. Exposed package-private so the
     * scheduled retention sweep and unit tests can enumerate them without
     * touching the underlying set directly.
     */
    java.util.Set<Path> getObservedRoots() {
        return java.util.Collections.unmodifiableSet(observedRoots);
    }

    /**
     * Best-effort: delete every spill file and per-conversation directory
     * older than {@link ToolResultProperties#getRetentionDays()} across all
     * roots this storage has seen, plus the configured base dir and the
     * tmpdir fallback. Returns the number of files deleted.
     *
     * <p>Workspaces that never received a spill in this JVM's lifetime are
     * not covered. Persisting an observed-roots registry across restarts
     * could fix that, but is intentionally out of scope — the operator-side
     * remedy is to run a one-off cleanup with {@code storage-base-dir}
     * pointed at the historical workspace.
     */
    public int cleanupExpired() {
        if (props.getRetentionDays() <= 0) {
            return 0;
        }
        long cutoffEpochMillis = System.currentTimeMillis()
                - (long) props.getRetentionDays() * 24L * 60L * 60L * 1000L;

        java.util.Set<Path> roots = new java.util.LinkedHashSet<>(observedRoots);
        if (!props.getStorageBaseDir().isEmpty()) {
            roots.add(Paths.get(props.getStorageBaseDir()));
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            roots.add(Paths.get(tmp, "mateclaw", "tool-results"));
        }

        int deleted = 0;
        for (Path root : roots) {
            deleted += deleteExpiredUnder(root, cutoffEpochMillis);
        }
        if (deleted > 0) {
            log.info("[ToolResultStorage] cleanup: {} spill files removed across {} root(s)",
                    deleted, roots.size());
        }
        return deleted;
    }

    private int deleteExpiredUnder(Path root, long cutoffEpochMillis) {
        if (root == null || !java.nio.file.Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(root, 2)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(root)) continue;
                if (!java.nio.file.Files.isRegularFile(p)) continue;
                try {
                    long mtime = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                    if (mtime < cutoffEpochMillis) {
                        java.nio.file.Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (java.io.IOException ioe) {
                    log.warn("[ToolResultStorage] failed to inspect spill file {}: {}", p, ioe.getMessage());
                }
            }
        } catch (java.io.IOException ioe) {
            log.warn("[ToolResultStorage] cleanup walk failed under {}: {}", root, ioe.getMessage());
            return deleted;
        }
        // Best-effort: remove emptied per-conversation directories.
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(root)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (!java.nio.file.Files.isDirectory(child)) continue;
                try (java.util.stream.Stream<Path> kids = java.nio.file.Files.list(child)) {
                    if (kids.findAny().isEmpty()) {
                        java.nio.file.Files.deleteIfExists(child);
                    }
                } catch (java.io.IOException ignored) {
                    // empty-check failure is not fatal — leave the directory alone
                }
            }
        } catch (java.io.IOException ioe) {
            log.warn("[ToolResultStorage] empty-dir sweep failed under {}: {}", root, ioe.getMessage());
        }
        return deleted;
    }

    /**
     * Delete every spill file produced for {@code conversationId} across
     * all observed roots, plus the configured base and tmpdir fallback.
     * Called by {@code ConversationService.deleteConversation} so spill
     * directories don't outlive the conversation that owns them.
     *
     * <p>Silently no-ops when nothing matches — a conversation that never
     * spilled, or one whose workspace root was never observed in this JVM,
     * is simply left alone. Returns the number of files deleted.
     */
    public int purgeConversation(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return 0;
        }
        String safeConv = sanitize(conversationId);
        java.util.Set<Path> roots = new java.util.LinkedHashSet<>(observedRoots);
        if (!props.getStorageBaseDir().isEmpty()) {
            roots.add(Paths.get(props.getStorageBaseDir()));
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            roots.add(Paths.get(tmp, "mateclaw", "tool-results"));
        }
        int deleted = 0;
        for (Path root : roots) {
            Path convDir = root.resolve(safeConv);
            if (!java.nio.file.Files.isDirectory(convDir)) continue;
            try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(convDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    try {
                        if (java.nio.file.Files.isRegularFile(p)) {
                            java.nio.file.Files.deleteIfExists(p);
                            deleted++;
                        }
                    } catch (java.io.IOException ioe) {
                        log.warn("[ToolResultStorage] failed to delete spill file {}: {}", p, ioe.getMessage());
                    }
                }
            } catch (java.io.IOException ioe) {
                log.warn("[ToolResultStorage] purge walk failed under {}: {}", convDir, ioe.getMessage());
            }
            try {
                java.nio.file.Files.deleteIfExists(convDir);
            } catch (java.io.IOException ignored) {
                // non-empty after deletes (another writer raced us) — fine, leave it
            }
        }
        if (deleted > 0) {
            log.info("[ToolResultStorage] purged {} spill file(s) for conversation {}", deleted, conversationId);
        }
        return deleted;
    }

    /** Strip path separators and reserved characters so user-supplied IDs cannot escape the directory. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Test/admin helper: lexicographic ordering by length, descending. Not used at runtime. */
    static Comparator<ToolResponseMessage.ToolResponse> byBodyLengthDesc() {
        return (a, b) -> Integer.compare(
                b.responseData() == null ? 0 : b.responseData().length(),
                a.responseData() == null ? 0 : a.responseData().length());
    }
}
