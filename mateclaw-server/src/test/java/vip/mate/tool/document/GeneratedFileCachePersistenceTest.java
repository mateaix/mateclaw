package vip.mate.tool.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
