package vip.mate.tool.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin {@link GeneratedFileCache#findIdByFilename}: recover a file's issued id
 * from its logical filename, so a reference that points at the name instead of
 * the id (which {@code GENERATED_URL_PATTERN} cannot parse) can still be
 * resolved. Covers the in-memory hit, the disk fallback, the mime-prefix
 * filter, case-insensitivity, and the misses.
 */
class GeneratedFileCacheFindByFilenameTest {

    private Path dir;
    private GeneratedFileCache cache;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dir = tempDir;
        cache = new GeneratedFileCache(tempDir);
    }

    @Test
    @DisplayName("in-memory: filename + image mime → the issued id")
    void memoryHit() {
        String id = cache.put("PNG".getBytes(), "cover_cat_7pits.png", "image/png");
        assertEquals(Optional.of(id), cache.findIdByFilename("cover_cat_7pits.png", "image/"));
    }

    @Test
    @DisplayName("filename match is case-insensitive")
    void caseInsensitive() {
        String id = cache.put("PNG".getBytes(), "Cover_Cat.PNG", "image/png");
        assertEquals(Optional.of(id), cache.findIdByFilename("cover_cat.png", "image/"));
    }

    @Test
    @DisplayName("mime prefix excludes a non-image with the same name")
    void mimePrefixExcludesNonImage() {
        cache.put("PDF".getBytes(), "cover.pdf", "application/pdf");
        assertTrue(cache.findIdByFilename("cover.pdf", "image/").isEmpty());
        // Without the constraint it is found.
        assertTrue(cache.findIdByFilename("cover.pdf", null).isPresent());
    }

    @Test
    @DisplayName("disk fallback: a fresh cache with empty memory finds it via persisted meta")
    void diskFallback() {
        String id = cache.put("PNG".getBytes(), "gzh-cover.png", "image/png");
        // A brand-new instance over the same dir has nothing in memory yet.
        GeneratedFileCache reopened = new GeneratedFileCache(dir);
        assertEquals(Optional.of(id), reopened.findIdByFilename("gzh-cover.png", "image/"));
    }

    @Test
    @DisplayName("unknown filename and null/blank input → empty")
    void misses() {
        cache.put("PNG".getBytes(), "cover.png", "image/png");
        assertTrue(cache.findIdByFilename("nope.png", "image/").isEmpty());
        assertTrue(cache.findIdByFilename(null, "image/").isEmpty());
        assertTrue(cache.findIdByFilename("  ", "image/").isEmpty());
    }
}
