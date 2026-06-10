package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the scan re-checks the resolved target's size, so an oversized file
 * reached through a symlink (whose own attribute size is just the link length)
 * cannot slip past the max-scan-file-size gate.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999",
                "mate.wiki.auto-process-on-upload=false",
                "mate.wiki.max-scan-file-size=200"
        }
)
class WikiScanSizeGuardE2ETest {

    @Autowired
    private WikiDirectoryScanService scanService;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    @Test
    void oversizedTargetReachedViaSymlink_isSkipped(@TempDir Path dir) throws IOException {
        // 5000-byte file far exceeds the 200-byte cap; a RELATIVE symlink to it
        // has a tiny attribute size (the short link path) that passes the
        // visitFile gate, so only the resolved-target re-check can stop it.
        Path big = dir.resolve("big.pdf");
        Files.write(big, new byte[5000]);
        Path link = dir.resolve("link.pdf");
        try {
            Files.createSymbolicLink(link, big.getFileName()); // relative -> "big.pdf"
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symlink support — skip
        }

        WikiDirectoryScanService.ScanResult result =
                scanService.scanDirectory(SEQ.incrementAndGet(), dir.toString());

        // Neither the oversized file nor the symlink to it is ingested.
        assertEquals(0, result.added(), "oversized target must not be ingested via a symlink");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("oversized")),
                "the resolved-target size check should report the oversized skip");
    }
}
