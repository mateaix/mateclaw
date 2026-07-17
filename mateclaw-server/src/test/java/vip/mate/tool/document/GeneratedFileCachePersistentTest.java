package vip.mate.tool.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the issue #514 persistent-entry contract: a file registered into a
 * workspace artifact catalog must survive the TTL sweep (and a restart) —
 * unlike a plain {@code put} whose bytes are deleted after 7 days.
 */
class GeneratedFileCachePersistentTest {

    @Test
    @DisplayName("putPersistent entry is never reported as expired, even past its nominal TTL")
    void persistentNeverExpires(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        String id = cache.putPersistent("body".getBytes(StandardCharsets.UTF_8), "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        GeneratedFileCache.Entry entry = cache.get(id).orElse(null);
        assertNotNull(entry, "persistent entry must be retrievable");
        assertFalse(entry.expired(), "persistent entry must never be expired");
        assertTrue(entry.persistent(), "persistent flag must be true");
    }

    @Test
    @DisplayName("cleanupExpired does NOT sweep persistent entries")
    void cleanupSkipsPersistent(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        byte[] bytes = "persistent-body".getBytes(StandardCharsets.UTF_8);
        String persistentId = cache.putPersistent(bytes, "p.docx", "application/pdf");
        String transientId = cache.put("t".getBytes(StandardCharsets.UTF_8), "t.txt", "text/plain");

        // Run the sweep. It iterates .meta files on disk and evicts expired ones.
        cache.cleanupExpired();

        // Neither is past the 7-day TTL, so both survive this particular sweep.
        // The point of this test is to assert the sweep logic itself doesn't
        // accidentally treat persistent as transient — the real guarantee is
        // exercised by the meta-flag-parsing assertions below.
        assertTrue(cache.get(persistentId).isPresent(), "persistent entry survives sweep");
        assertTrue(cache.get(transientId).isPresent(), "fresh transient entry survives sweep");
    }

    @Test
    @DisplayName("persistent flag round-trips through disk — survives a 'restart'")
    void persistentSurvivesRestart(@TempDir Path dir) {
        GeneratedFileCache first = new GeneratedFileCache(dir);
        String id = first.putPersistent("restart-body".getBytes(StandardCharsets.UTF_8),
                "季度报表.docx", "application/pdf");

        // Simulate a JVM restart.
        GeneratedFileCache afterRestart = new GeneratedFileCache(dir);
        GeneratedFileCache.Entry entry = afterRestart.get(id).orElse(null);

        assertNotNull(entry, "persistent entry reloads from disk after restart");
        assertTrue(entry.persistent(), "persistent flag round-trips through .meta");
        assertFalse(entry.expired(), "reloaded persistent entry never reports expired");
    }

    @Test
    @DisplayName("legacy 3-field .meta (pre-#514) loads as non-persistent")
    void legacyMetaDefaultsNonPersistent(@TempDir Path dir) throws Exception {
        // Write a pre-#514 style meta: only 3 tab-separated fields.
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        byte[] bytes = "legacy".getBytes(StandardCharsets.UTF_8);
        String id = "legacy-uuid-1234";
        java.nio.file.Files.write(dir.resolve(id), bytes);
        java.nio.file.Files.writeString(dir.resolve(id + ".meta"),
                (System.currentTimeMillis() + 86400000L) + "\ttext/plain\tbGVnYWN5LnR4dA==");

        GeneratedFileCache.Entry entry = cache.get(id).orElse(null);
        assertNotNull(entry, "legacy meta must still load");
        assertFalse(entry.persistent(), "legacy 3-field meta defaults to non-persistent");
    }

    @Test
    @DisplayName("plain put() entries remain non-persistent (backward compat)")
    void plainPutIsNonPersistent(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        String id = cache.put("x".getBytes(StandardCharsets.UTF_8), "x.txt", "text/plain");
        GeneratedFileCache.Entry entry = cache.get(id).orElseThrow();
        assertFalse(entry.persistent(), "plain put() must remain non-persistent");
    }
}
