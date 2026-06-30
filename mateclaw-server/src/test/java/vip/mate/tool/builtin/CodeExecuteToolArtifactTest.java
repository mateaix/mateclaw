package vip.mate.tool.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.document.GeneratedFileCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a code run, files it wrote to the working dir should surface as
 * one-click downloads (issue #191): the tool registers them in the
 * generated-file cache and returns {@code [name](url)} links the chat layer
 * already scans for. Pre-existing files, scratch/noise files, and oversized
 * files must be left out.
 */
class CodeExecuteToolArtifactTest {

    // Mirror of ChatController.GENERATED_FILE_LINK_PATTERN so we assert the links
    // are in the exact shape the chat layer extracts.
    private static final Pattern LINK = Pattern.compile(
            "\\[([^\\]]+)\\]\\(((?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/[A-Za-z0-9-]+)\\)");

    private Path tmp;
    private Path cacheDir;

    private CodeExecuteTool newTool(GeneratedFileCache cache) {
        // Only generatedFileCache is exercised here; the other collaborators are
        // unused by collectArtifactLinks.
        return new CodeExecuteTool(null, null, null, null, cache);
    }

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
    @DisplayName("Files written during the run surface as download links; old/noise files do not")
    void surfacesNewlyWrittenFiles() throws Exception {
        tmp = Files.createTempDirectory("codeexec-artifacts-");
        cacheDir = Files.createTempDirectory("codeexec-cache-");
        GeneratedFileCache cache = new GeneratedFileCache(cacheDir);

        // Pre-existing file, modified well before the run window — must be ignored.
        Path old = Files.write(tmp.resolve("old.txt"), "old".getBytes());
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1_000L));

        long runStart = System.currentTimeMillis();
        Thread.sleep(5);

        // Files produced "by the run".
        byte[] xlsx = "PK fake-xlsx-bytes".getBytes();
        Files.write(tmp.resolve("report.xlsx"), xlsx);
        Files.write(tmp.resolve("data.csv"), "a,b\n1,2\n".getBytes());
        // Noise that must be filtered out.
        Files.write(tmp.resolve("scratch.tmp"), "x".getBytes());
        Files.write(tmp.resolve(".hidden"), "x".getBytes());

        List<String> links = newTool(cache).collectArtifactLinks(tmp, runStart, null);

        // report.xlsx + data.csv only.
        assertEquals(2, links.size(), "expected the two real artifacts, got: " + links);
        assertTrue(links.stream().anyMatch(l -> l.contains("report.xlsx")));
        assertTrue(links.stream().anyMatch(l -> l.contains("data.csv")));
        assertFalse(links.stream().anyMatch(l -> l.contains("old.txt")), "pre-existing file must not surface");
        assertFalse(links.stream().anyMatch(l -> l.contains("scratch.tmp")), ".tmp must not surface");
        assertFalse(links.stream().anyMatch(l -> l.contains(".hidden")), "hidden file must not surface");

        // Each link is in the exact shape the chat layer extracts, and the bytes
        // round-trip through the cache (so the download endpoint can serve them).
        for (String link : links) {
            Matcher m = LINK.matcher(link);
            assertTrue(m.matches(), "link not in extractable form: " + link);
            String id = m.group(2).substring(m.group(2).lastIndexOf('/') + 1);
            Optional<GeneratedFileCache.Entry> entry = cache.get(id);
            assertTrue(entry.isPresent(), "cached artifact must be retrievable: " + link);
        }
    }

    @Test
    @DisplayName("Null / non-existent working dir yields no links and does not throw")
    void nullWorkingDirIsSafe() throws Exception {
        cacheDir = Files.createTempDirectory("codeexec-cache-");
        CodeExecuteTool tool = newTool(new GeneratedFileCache(cacheDir));
        assertTrue(tool.collectArtifactLinks(null, 0L, null).isEmpty());
        assertTrue(tool.collectArtifactLinks(Path.of("/no/such/dir/xyz"), 0L, null).isEmpty());
    }
}
