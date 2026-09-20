package vip.mate.tool.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the persistence contract that keeps download links durable: bytes are
 * written to disk so a link still resolves after the in-memory entry is gone
 * or the JVM has restarted. A regression here reintroduces the
 * "File not found or expired" page that a user hits minutes after generating
 * a document.
 */
class GeneratedFileCachePersistenceTest {

    @Test
    void forbiddenWorkspaceLookupDoesNotPopulateColdCache(@TempDir Path dir) {
        String id = new GeneratedFileCache(dir).put("body".getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain",
                new GeneratedFileCache.Owner(20L, 30L, "conv"));
        GeneratedFileCache cold = new GeneratedFileCache(dir);
        assertTrue(cold.getForWorkspace(id, 40L).isEmpty());
        assertTrue(((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(cold, "entries")).isEmpty());
        assertTrue(cold.getForWorkspace(id, 20L).isPresent());
    }

    @Test
    void coldDownloadRejectsSymbolicLinkToExternalContent(@TempDir Path dir) throws IOException {
        Path storage = Files.createDirectory(dir.resolve("cache"));
        var cache = new GeneratedFileCache(storage);
        String id = cache.put("original".getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain");
        Path external = Files.writeString(dir.resolve("private.txt"), "outside content");
        Files.delete(storage.resolve(id));
        Files.createSymbolicLink(storage.resolve(id), external);

        assertTrue(new GeneratedFileCache(storage).get(id).isEmpty());
        assertEquals("outside content", Files.readString(external));
    }

    @Test
    void coldDownloadRejectsSymbolicLinkToExternalMetadata(@TempDir Path dir) throws IOException {
        Path storage = Files.createDirectory(dir.resolve("cache"));
        var cache = new GeneratedFileCache(storage);
        String id = cache.put("original".getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain");
        Path metadata = storage.resolve(id + ".meta");
        Path external = Files.move(metadata, dir.resolve("outside.meta"));
        Files.createSymbolicLink(metadata, external);

        assertTrue(new GeneratedFileCache(storage).get(id).isEmpty());
        assertTrue(Files.isRegularFile(external));
    }

    @Test
    void callerCannotChangeRegisteredVersionThroughInputBytes(@TempDir Path dir) {
        var cache = new GeneratedFileCache(dir);
        byte[] input = "report-v1".getBytes(StandardCharsets.UTF_8);
        String id = cache.put(input, "report.txt", "text/plain");
        input[0] = 'X';
        byte[] persisted = new GeneratedFileCache(dir).get(id).orElseThrow().bytes();
        assertArrayEquals("report-v1".getBytes(StandardCharsets.UTF_8), persisted);
        assertArrayEquals(persisted, cache.get(id).orElseThrow().bytes());
    }

    @Test
    void callerCannotChangeRegisteredVersionThroughReturnedBytes(@TempDir Path dir) {
        var cache = new GeneratedFileCache(dir);
        String id = cache.put("report-v1".getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain");
        var returned = cache.get(id).orElseThrow();
        returned.bytes()[0] = 'X';
        byte[] persisted = new GeneratedFileCache(dir).get(id).orElseThrow().bytes();
        assertArrayEquals(persisted, cache.get(id).orElseThrow().bytes());
        assertArrayEquals(persisted, returned.bytes());
    }

    @Test
    @DisplayName("a link survives a 'restart' — a fresh cache over the same dir still serves it")
    void survivesRestart(@TempDir Path dir) {
        GeneratedFileCache first = new GeneratedFileCache(dir);
        byte[] bytes = "report-body".getBytes(StandardCharsets.UTF_8);
        String id = first.put(bytes, "季度报表.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        // Simulate a JVM restart: a brand-new instance with an empty memory map,
        // pointing at the same storage directory.
        GeneratedFileCache afterRestart = new GeneratedFileCache(dir);
        GeneratedFileCache.Entry entry = afterRestart.get(id).orElse(null);

        assertNotNull(entry, "persisted entry must be reloaded from disk after restart");
        assertArrayEquals(bytes, entry.bytes(), "reloaded bytes must match the original");
        assertEquals("季度报表.docx", entry.filename(), "unicode filename must round-trip");
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                entry.mimeType());
    }

    @Test
    @DisplayName("workspace ownership metadata persists and gates lookup")
    void ownershipPersistsAndGatesLookup(@TempDir Path dir) {
        GeneratedFileCache first = new GeneratedFileCache(dir);
        byte[] bytes = "workspace-b".getBytes(StandardCharsets.UTF_8);
        String id = first.put(bytes, "b.csv", "text/csv",
                new GeneratedFileCache.Owner(20L, 30L, "conv-b"));

        GeneratedFileCache afterRestart = new GeneratedFileCache(dir);
        GeneratedFileCache.Entry entry = afterRestart.get(id).orElse(null);

        assertNotNull(entry, "persisted entry must be reloaded from disk after restart");
        assertEquals(20L, entry.workspaceId());
        assertEquals(30L, entry.ownerUserId());
        assertEquals("conv-b", entry.conversationId());
        assertTrue(afterRestart.getForWorkspace(id, 20L).isPresent());
        assertTrue(afterRestart.getForWorkspace(id, 10L).isEmpty(),
                "a file generated in workspace B must not resolve under workspace A");
    }

    @Test
    @DisplayName("unknown id returns empty")
    void unknownIdEmpty(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        assertTrue(cache.get("00000000-0000-0000-0000-000000000000").isEmpty());
    }

    @Test
    @DisplayName("malformed / path-traversal ids are rejected without touching disk")
    void traversalRejected(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        assertTrue(cache.get("../secret").isEmpty());
        assertTrue(cache.get("a/b").isEmpty());
        assertTrue(cache.get("").isEmpty());
        assertTrue(cache.get(null).isEmpty());
    }

    @Test
    @DisplayName("memory LRU eviction never loses downloadability — old ids reload from disk")
    void lruEvictionFallsBackToDisk(@TempDir Path dir) {
        GeneratedFileCache cache = new GeneratedFileCache(dir);
        // Far exceed the in-memory cap so the first id is evicted from memory.
        String firstId = cache.put("first".getBytes(StandardCharsets.UTF_8), "first.txt", "text/plain");
        for (int i = 0; i < 400; i++) {
            cache.put(("f" + i).getBytes(StandardCharsets.UTF_8), "f" + i + ".txt", "text/plain");
        }
        GeneratedFileCache.Entry entry = cache.get(firstId).orElse(null);
        assertNotNull(entry, "an id evicted from the memory cache must still resolve from disk");
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), entry.bytes());
    }

    @Test
    @DisplayName("scrub treats a persisted-but-evicted id as live (reloads from disk)")
    void scrubReloadsPersisted(@TempDir Path dir) {
        GeneratedFileCache first = new GeneratedFileCache(dir);
        String id = first.put("x".getBytes(StandardCharsets.UTF_8), "a.pdf", "application/pdf");

        GeneratedFileCache afterRestart = new GeneratedFileCache(dir);
        String text = "下载: /api/v1/files/generated/" + id;
        assertEquals(text, afterRestart.scrubMissingReferences(text),
                "a still-persisted link must not be scrubbed as missing after restart");
    }
}
