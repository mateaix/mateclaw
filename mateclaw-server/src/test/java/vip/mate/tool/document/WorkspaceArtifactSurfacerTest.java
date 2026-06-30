package vip.mate.tool.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Files a tool run writes to the working dir should surface as one-click
 * downloads (issue #191): registered in the generated-file cache and returned as
 * {@code [name](url)} links the chat layer scans for. Pre-existing, scratch, and
 * hidden files are excluded.
 */
class WorkspaceArtifactSurfacerTest {

    private static final Pattern LINK = Pattern.compile(
            "\\[([^\\]]+)\\]\\(((?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/[A-Za-z0-9-]+)\\)");

    private Path tmp;
    private Path cacheDir;

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : new Path[]{tmp, cacheDir}) {
            if (root != null && Files.exists(root)) {
                try (var s = Files.walk(root)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignore) { }
                    });
                }
            }
        }
    }

    @Test
    @DisplayName("Files written during the run surface as links; old/noise files do not")
    void surfacesNewlyWrittenFiles() throws Exception {
        tmp = Files.createTempDirectory("artifacts-");
        cacheDir = Files.createTempDirectory("cache-");
        GeneratedFileCache cache = new GeneratedFileCache(cacheDir);

        Path old = Files.write(tmp.resolve("old.txt"), "old".getBytes());
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000L));

        long runStart = System.currentTimeMillis();
        Thread.sleep(5);

        Files.write(tmp.resolve("report.xlsx"), "PK fake-xlsx".getBytes());
        Files.write(tmp.resolve("data.csv"), "a,b\n1,2\n".getBytes());
        Files.write(tmp.resolve("scratch.tmp"), "x".getBytes());
        Files.write(tmp.resolve(".hidden"), "x".getBytes());

        List<String> links = WorkspaceArtifactSurfacer.collect(cache, tmp, runStart, null);

        assertEquals(2, links.size(), "expected the two real artifacts, got: " + links);
        assertTrue(links.stream().anyMatch(l -> l.contains("report.xlsx")));
        assertTrue(links.stream().anyMatch(l -> l.contains("data.csv")));
        assertFalse(links.stream().anyMatch(l -> l.contains("old.txt")), "pre-existing file must not surface");
        assertFalse(links.stream().anyMatch(l -> l.contains(".tmp")), ".tmp must not surface");
        assertFalse(links.stream().anyMatch(l -> l.contains(".hidden")), "hidden file must not surface");

        for (String link : links) {
            Matcher m = LINK.matcher(link);
            assertTrue(m.matches(), "link not in extractable form: " + link);
            String id = m.group(2).substring(m.group(2).lastIndexOf('/') + 1);
            Optional<GeneratedFileCache.Entry> entry = cache.get(id);
            assertTrue(entry.isPresent(), "cached artifact must be retrievable: " + link);
        }
    }

    @Test
    @DisplayName("Null / non-existent working dir and null cache are safe no-ops")
    void edgeCasesAreSafe() throws Exception {
        cacheDir = Files.createTempDirectory("cache-");
        GeneratedFileCache cache = new GeneratedFileCache(cacheDir);
        assertTrue(WorkspaceArtifactSurfacer.collect(cache, null, 0L, null).isEmpty());
        assertTrue(WorkspaceArtifactSurfacer.collect(cache, Path.of("/no/such/dir/xyz"), 0L, null).isEmpty());
        assertTrue(WorkspaceArtifactSurfacer.collect(null, Path.of("."), 0L, null).isEmpty());
    }
}
