package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention sweep + per-conversation purge for {@link ToolResultStorage}.
 *
 * <p>The store keeps a per-JVM "observed roots" registry — every time a
 * spill resolves a directory, that directory is remembered so the
 * retention sweep can reach it even after the workspace path has gone
 * out of scope. These tests verify the registry behaviour, the
 * mtime-based deletion contract, and the targeted per-conversation purge
 * called from {@code ConversationService.deleteConversation}.
 */
class ToolResultStorageRetentionTest {

    @Test
    void successfulSpillRegistersTheRoot(@TempDir Path tempDir) {
        ToolResultStorage storage = newStorage(tempDir, /*threshold*/ 100, /*retention*/ 7);

        // Trigger a spill so resolveBaseDir() is invoked.
        String out = storage.persistIfOversized(
                "x".repeat(500), "web_search", "call-1", "conv-A", tempDir.toString());
        assertTrue(out.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX));

        assertTrue(storage.getObservedRoots().contains(tempDir),
                "successful spill must register its resolved root for later cleanup");
    }

    @Test
    void cleanupDeletesFilesOlderThanRetention(@TempDir Path tempDir) throws Exception {
        // retention = 1 day
        ToolResultStorage storage = newStorage(tempDir, /*threshold*/ 100, /*retention*/ 1);

        // Drop a "fresh" spill via the public API.
        String fresh = storage.persistIfOversized(
                "fresh".repeat(200), "web_search", "call-fresh", "conv-A", tempDir.toString());
        assertTrue(fresh.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX));
        Path freshFile = pathFromPreview(fresh);

        // Drop a "stale" spill and back-date its mtime by 8 days.
        String stale = storage.persistIfOversized(
                "stale".repeat(200), "web_search", "call-stale", "conv-B", tempDir.toString());
        Path staleFile = pathFromPreview(stale);
        Files.setLastModifiedTime(staleFile,
                FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));

        int deleted = storage.cleanupExpired();

        assertEquals(1, deleted, "only the stale file should be removed");
        assertTrue(Files.exists(freshFile), "fresh file must survive");
        assertFalse(Files.exists(staleFile), "stale file must be deleted");
    }

    @Test
    void cleanupIsNoOpWhenRetentionDisabled(@TempDir Path tempDir) throws Exception {
        ToolResultStorage storage = newStorage(tempDir, /*threshold*/ 100, /*retention*/ 0);

        String stale = storage.persistIfOversized(
                "stale".repeat(200), "web_search", "call-1", "conv-A", tempDir.toString());
        Path staleFile = pathFromPreview(stale);
        Files.setLastModifiedTime(staleFile,
                FileTime.from(Instant.now().minus(100, ChronoUnit.DAYS)));

        int deleted = storage.cleanupExpired();
        assertEquals(0, deleted, "retentionDays<=0 must disable the sweep entirely");
        assertTrue(Files.exists(staleFile), "stale file must remain when sweep is disabled");
    }

    @Test
    void cleanupRemovesEmptiedConversationDirectories(@TempDir Path tempDir) throws Exception {
        ToolResultStorage storage = newStorage(tempDir, /*threshold*/ 100, /*retention*/ 1);

        String stale = storage.persistIfOversized(
                "stale".repeat(200), "web_search", "call-stale", "conv-old", tempDir.toString());
        Path staleFile = pathFromPreview(stale);
        Path staleDir = staleFile.getParent();
        Files.setLastModifiedTime(staleFile,
                FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));

        storage.cleanupExpired();

        assertFalse(Files.exists(staleFile));
        assertFalse(Files.exists(staleDir),
                "the empty conv-old/ directory should be cleaned up too");
    }

    @Test
    void purgeConversationDeletesAllFilesForOneConversation(@TempDir Path tempDir) throws Exception {
        ToolResultStorage storage = newStorage(tempDir, /*threshold*/ 100, /*retention*/ 30);

        // Two spills for conv-A, one spill for conv-B.
        String a1 = storage.persistIfOversized(
                "a1".repeat(200), "web_search", "call-a1", "conv-A", tempDir.toString());
        String a2 = storage.persistIfOversized(
                "a2".repeat(200), "web_search", "call-a2", "conv-A", tempDir.toString());
        String b1 = storage.persistIfOversized(
                "b1".repeat(200), "web_search", "call-b1", "conv-B", tempDir.toString());
        Path af1 = pathFromPreview(a1);
        Path af2 = pathFromPreview(a2);
        Path bf1 = pathFromPreview(b1);

        int deleted = storage.purgeConversation("conv-A");

        assertEquals(2, deleted, "both A files should be deleted");
        assertFalse(Files.exists(af1));
        assertFalse(Files.exists(af2));
        assertTrue(Files.exists(bf1), "conv-B files must not be touched by a conv-A purge");
    }

    @Test
    void purgeConversationIsSilentForUnknownConversation(@TempDir Path tempDir) {
        ToolResultStorage storage = newStorage(tempDir, /*threshold*/ 100, /*retention*/ 7);

        // No spill at all → nothing to purge → 0, no exception.
        int deleted = storage.purgeConversation("never-existed");
        assertEquals(0, deleted);
    }

    @Test
    void purgeConversationHandlesBlankIdSafely(@TempDir Path tempDir) {
        ToolResultStorage storage = newStorage(tempDir, /*threshold*/ 100, /*retention*/ 7);

        assertEquals(0, storage.purgeConversation(null));
        assertEquals(0, storage.purgeConversation(""));
    }

    // ------------------------------------------------------------------ helpers

    private static ToolResultStorage newStorage(Path tempDir, int threshold, int retentionDays) {
        ToolResultProperties props = new ToolResultProperties();
        props.setStorageBaseDir(tempDir.toString());
        props.setPerResultThresholdChars(threshold);
        props.setPreviewHeadChars(80);
        props.setRetentionDays(retentionDays);
        props.setExcludedTools(List.of());
        return new ToolResultStorage(props);
    }

    private static Path pathFromPreview(String preview) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("path=(\\S+)").matcher(preview);
        assertTrue(m.find(), "preview must include path=...");
        Path p = Path.of(m.group(1));
        assertNotNull(p);
        return p;
    }
}
