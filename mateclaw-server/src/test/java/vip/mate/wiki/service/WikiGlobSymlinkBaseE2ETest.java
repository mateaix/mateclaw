package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies glob matching works when the pattern's base directory is reached
 * through a symbolic link. {@code WikiSourcePathValidator} canonicalizes the
 * base with {@code toRealPath()}, so the walked files carry the symlink-resolved
 * prefix; the matcher must be built against that resolved scan root rather than
 * the literal pattern prefix, otherwise nothing matches and files are silently
 * dropped.
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
class WikiGlobSymlinkBaseE2ETest {

    @Autowired
    private WikiDirectoryScanService scanService;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    @Test
    void globWithSymlinkBase_singleLevel_matches(@TempDir Path base) throws IOException {
        Path realDir = Files.createDirectories(base.resolve("real"));
        Files.writeString(realDir.resolve("a.txt"), "alpha");
        Files.write(realDir.resolve("b.pdf"), "PDF-bytes".getBytes());

        Path linkDir = base.resolve("link");
        try {
            Files.createSymbolicLink(linkDir, realDir);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — skip
        }

        WikiDirectoryScanService.ScanResult result =
                scanService.scanDirectory(SEQ.incrementAndGet(), linkDir + "/*.txt");

        // Only a.txt matches; the .pdf is excluded by the explicit *.txt pattern.
        // Before the fix the symlink-resolved file prefix never matched the literal
        // pattern prefix, so added would be 0.
        assertEquals(1, result.added(), "glob through a symlinked base must match the .txt file");
    }

    @Test
    void globWithSymlinkBase_recursive_matchesSubdir(@TempDir Path base) throws IOException {
        Path realDir = Files.createDirectories(base.resolve("real"));
        Files.createDirectories(realDir.resolve("sub"));
        Files.writeString(realDir.resolve("sub/c.txt"), "charlie");

        Path linkDir = base.resolve("link");
        try {
            Files.createSymbolicLink(linkDir, realDir);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — skip
        }

        WikiDirectoryScanService.ScanResult result =
                scanService.scanDirectory(SEQ.incrementAndGet(), linkDir + "/**/*.txt");

        assertEquals(1, result.added(), "recursive glob through a symlinked base must match the nested .txt file");
    }
}
