package vip.mate.tool.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.tool.document.GeneratedFileCache.ArtifactVersion.*;

class GeneratedFileArtifactVersionTest {
    @TempDir Path root;

    private String put(GeneratedFileCache cache, String text) {
        return cache.put(text.getBytes(StandardCharsets.UTF_8), "report.txt", "text/plain",
                new GeneratedFileCache.Owner(1L, 1L, "conv"));
    }
    private String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void matchingBytesRemainUnverifiedAndChangedBytesAreDetected() throws Exception {
        var cache = new GeneratedFileCache(root);
        String id = put(cache, "report");
        assertEquals(UNVERIFIED, cache.probeDurableArtifactVersion(id, 1L, "conv", digest("report"), 1024));
        Files.writeString(root.resolve(id), "different report");
        assertEquals(CHANGED, cache.probeDurableArtifactVersion(id, 1L, "conv", digest("report"), 1024));
        assertEquals("report", new String(cache.get(id).orElseThrow().bytes(), StandardCharsets.UTF_8),
                "probe reads durable bytes, not the old hot cache");
    }

    @Test void overBudgetDisabledAndInvalidDigestNeverPassOrClaimChange() throws Exception {
        var cache = new GeneratedFileCache(root);
        String id = put(cache, "report");
        assertEquals(UNVERIFIED, cache.probeDurableArtifactVersion(id, 1L, "conv", digest("different"), 2));
        assertEquals(UNVERIFIED, cache.probeDurableArtifactVersion(id, 1L, "conv", digest("different"), 0));
        assertEquals(UNVERIFIED, cache.probeDurableArtifactVersion(id, 1L, "conv", "bad digest", 1024));
    }

    @Test void missingForeignExpiredAndOversizedMetadataAreUnavailable() throws Exception {
        var cache = new GeneratedFileCache(root);
        String id = put(cache, "report");
        assertEquals(UNAVAILABLE, cache.probeDurableArtifactVersion(id, 2L, "conv", digest("report"), 1024));
        assertEquals(UNAVAILABLE, cache.probeDurableArtifactVersion(id, 1L, "other", digest("report"), 1024));
        Path meta = root.resolve(id + ".meta");
        String original = Files.readString(meta);
        Files.writeString(meta, "0" + original.substring(original.indexOf('\t')));
        assertEquals(UNAVAILABLE, cache.probeDurableArtifactVersion(id, 1L, "conv", digest("report"), 1024));
        Files.writeString(meta, "x".repeat(16_385));
        assertFalse(cache.isDurablyAvailable(id, 1L, "conv"));
        Files.writeString(meta, original);
        Files.delete(root.resolve(id));
        assertEquals(UNAVAILABLE, cache.probeDurableArtifactVersion(id, 1L, "conv", digest("report"), 1024));
    }

    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @Test void symbolicLinksAreNotFollowedByTheProbe() throws Exception {
        var cache = new GeneratedFileCache(root);
        String id = put(cache, "report");
        Path outside = Files.writeString(root.resolve("outside"), "report");
        Files.delete(root.resolve(id));
        Files.createSymbolicLink(root.resolve(id), outside);
        assertFalse(cache.isDurablyAvailable(id, 1L, "conv"));
        assertEquals(UNAVAILABLE, cache.probeDurableArtifactVersion(id, 1L, "conv", digest("report"), 1024));
        assertEquals("UNAVAILABLE", cache.readDurableArtifactSnapshot(id, 1L, "conv", digest("report"), 1024).status());
    }
    @Test void boundedSnapshotReadsOnlyMatchingOwnedDurableBytes() throws Exception {
        var cache = new GeneratedFileCache(root);
        String id = put(cache, "report");
        var read = cache.readDurableArtifactSnapshot(id, 1L, "conv", digest("report"), 6);
        assertEquals("READ", read.status());
        assertEquals("report", new String(read.bytes(), StandardCharsets.UTF_8));
        read.bytes()[0] = 'X';
        assertEquals("report", new String(read.bytes(), StandardCharsets.UTF_8));
        assertEquals("UNKNOWN", cache.readDurableArtifactSnapshot(id, 1L, "conv", digest("report"), 5).status());
        assertEquals("UNKNOWN", cache.readDurableArtifactSnapshot(id, 1L, "conv", digest("report"), 0).status());
        assertEquals("UNKNOWN", cache.readDurableArtifactSnapshot(id, 1L, "conv", "fake", 10).status());
        assertEquals("UNAVAILABLE", cache.readDurableArtifactSnapshot(id, 2L, "conv", digest("report"), 10).status());
        assertEquals("UNAVAILABLE", cache.readDurableArtifactSnapshot(id, 1L, "other", digest("report"), 10).status());
        Files.writeString(root.resolve(id), "changed");
        var stale = cache.readDurableArtifactSnapshot(id, 1L, "conv", digest("report"), 10);
        assertEquals("STALE", stale.status());
        assertNull(stale.bytes());
        Files.delete(root.resolve(id));
        assertEquals("UNAVAILABLE", cache.readDurableArtifactSnapshot(id, 1L, "conv", digest("report"), 10).status());
    }

    @Test void snapshotReadHasAHardOneMebibyteLimit() throws Exception {
        var cache = new GeneratedFileCache(root);
        String content = "x".repeat(1_048_577);
        String id = put(cache, content);
        var result = cache.readDurableArtifactSnapshot(id, 1L, "conv", digest(content), Integer.MAX_VALUE);
        assertEquals("UNKNOWN", result.status());
        assertNull(result.bytes());
    }

}
