package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the source watcher's scan cycle against H2: new files in a
 * KB's source directory are auto-ingested, and a re-scan is idempotent (dedup
 * by source path). Auto-processing is disabled so the test stays model-free.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999",
                "mate.wiki.auto-process-on-upload=false"
        }
)
class WikiSourceWatcherServiceE2ETest {

    @Autowired
    private WikiSourceWatcherService watcherService;
    @Autowired
    private WikiKnowledgeBaseService kbService;
    @Autowired
    private WikiDirectoryScanService scanService;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    @Test
    void scanCycleIngestsNewFiles_thenDedups(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("note-a.md"), "# Note A\n\ncontent a");
        Files.writeString(sourceDir.resolve("note-b.md"), "# Note B\n\ncontent b");

        WikiKnowledgeBaseEntity kb = kbService.create(
                "watcher-" + SEQ.incrementAndGet(), "test", null);
        kbService.updateSourceDirectory(kb.getId(), sourceDir.toString());

        // First cycle ingests both new files.
        int firstAdded = watcherService.runScanCycle();
        assertTrue(firstAdded >= 2, "expected >= 2 new files, got " + firstAdded);

        // A new file appears; the next cycle ingests only it (existing files dedup).
        Files.writeString(sourceDir.resolve("note-c.md"), "# Note C\n\ncontent c");
        int secondAdded = watcherService.runScanCycle();
        assertEquals(1, secondAdded, "only the newly added file should ingest");

        // Re-scanning with no changes ingests nothing.
        assertEquals(0, watcherService.runScanCycle(), "unchanged files must not re-ingest");

        // Modifying an existing file's content re-ingests it (content hash changed).
        Files.writeString(sourceDir.resolve("note-a.md"), "# Note A\n\nEDITED content a");
        int afterEdit = watcherService.runScanCycle();
        assertEquals(1, afterEdit, "a modified file must be re-ingested");
    }

    @Test
    void symlinkFileEscapingScanRoot_isNotIngested(@TempDir Path sourceDir, @TempDir Path outside)
            throws java.io.IOException {
        Files.writeString(sourceDir.resolve("real.md"), "# Real\n\nlocal content");
        Path secret = Files.writeString(outside.resolve("secret.md"), "TOP SECRET OUTSIDE");
        Path link = sourceDir.resolve("leak.md");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // filesystem without symlink support — skip
        }

        long kb = SEQ.incrementAndGet();
        WikiDirectoryScanService.ScanResult result = scanService.scanDirectory(kb, sourceDir.toString());

        // Only the real file is ingested; the symlink escaping the root is skipped.
        assertEquals(1, result.added(), "symlinked file pointing outside the root must not be ingested");
        assertTrue(result.skipped() >= 1 || !result.errors().isEmpty(),
                "the escaping symlink should be reported as skipped");
    }

    @Test
    void modifiedBinaryFile_isReingested(@TempDir Path sourceDir) throws java.io.IOException {
        Path pdf = sourceDir.resolve("doc.pdf");
        Files.write(pdf, "PDF-VERSION-ONE-bytes".getBytes());
        long kb = SEQ.incrementAndGet();

        assertEquals(1, scanService.scanDirectory(kb, sourceDir.toString()).added());
        // Unchanged binary re-scan ingests nothing.
        assertEquals(0, scanService.scanDirectory(kb, sourceDir.toString()).added());
        // Changed bytes -> different content hash -> re-ingested.
        Files.write(pdf, "PDF-VERSION-TWO-different-bytes".getBytes());
        assertEquals(1, scanService.scanDirectory(kb, sourceDir.toString()).added(),
                "a modified binary file must be re-ingested");
    }

    @Test
    void kbsWithoutSourceDirectory_areSkipped() {
        // A KB with no source directory must not cause errors in the cycle.
        kbService.create("nodir-" + SEQ.incrementAndGet(), "test", null);
        // Should complete without throwing (count is non-negative).
        assertTrue(watcherService.runScanCycle() >= 0);
    }
}
