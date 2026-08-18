package vip.mate.tool.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the tool-side attachment resolver finds files under both the flat
 * conversation-dir layout and the per-day ({@code yyyy-MM-dd}) sub-directory
 * layout, including the sanitized-basename suffix fallback used when the LLM
 * passes the original (non-ASCII) filename instead of the stored name.
 */
class ChatUploadResolverDateFolderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearContext() {
        ToolExecutionContext.clear();
    }

    /** Point the resolver at {@code tempDir} as the workspace base path. */
    private Path conversationDir(String conversationId) throws Exception {
        ToolExecutionContext.set(conversationId, "tester", tempDir.toString());
        Path dir = tempDir.resolve("chat-uploads").resolve(conversationId);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    @DisplayName("flat layout: direct basename match still resolves")
    void resolvesFlatFile() throws Exception {
        Path convDir = conversationDir("conv-flat");
        Files.writeString(convDir.resolve("1777_report.pdf"), "x");

        assertThat(ChatUploadResolver.resolve("1777_report.pdf"))
                .isEqualTo(convDir.resolve("1777_report.pdf"));
    }

    @Test
    @DisplayName("date layout: file under yyyy-MM-dd resolves by stored name")
    void resolvesDatedFile() throws Exception {
        Path convDir = conversationDir("conv-dated");
        Path dateDir = convDir.resolve("2026-07-26");
        Files.createDirectories(dateDir);
        Files.writeString(dateDir.resolve("1777_report.pdf"), "x");

        assertThat(ChatUploadResolver.resolve("1777_report.pdf"))
                .isEqualTo(dateDir.resolve("1777_report.pdf"));
    }

    @Test
    @DisplayName("date layout: sanitized-suffix fallback matches original filename")
    void resolvesDatedFileBySuffixFallback() throws Exception {
        Path convDir = conversationDir("conv-suffix");
        Path dateDir = convDir.resolve("2026-07-26");
        Files.createDirectories(dateDir);
        // Stored as "{millis}_{sanitized}": non-ASCII chars become underscores.
        Files.writeString(dateDir.resolve("1777391026594_____.docx"), "x");

        assertThat(ChatUploadResolver.resolve("人人有虾.docx"))
                .isEqualTo(dateDir.resolve("1777391026594_____.docx"));
    }

    @Test
    @DisplayName("suffix fallback: the newest same-named copy wins over a stale flat one")
    void suffixFallbackPrefersNewestCopy() throws Exception {
        Path convDir = conversationDir("conv-newest");
        Path dateDir = convDir.resolve("2026-07-26");
        Files.createDirectories(dateDir);
        Path stale = convDir.resolve("1777000000000_report.docx");
        Path fresh = dateDir.resolve("1777391026594_report.docx");
        Files.writeString(stale, "old");
        Files.writeString(fresh, "new");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(1_777_000_000_000L));
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(1_777_391_026_594L));

        assertThat(ChatUploadResolver.resolve("report.docx")).isEqualTo(fresh);
    }

    @Test
    @DisplayName("a Windows-style path from the model still resolves on a POSIX host")
    void resolvesWindowsStylePathOnPosixHost() throws Exception {
        Path convDir = conversationDir("conv-winpath");
        Path dateDir = convDir.resolve("2026-07-26");
        Files.createDirectories(dateDir);
        Files.writeString(dateDir.resolve("1777391026594_report.pdf"), "x");

        // A backslash is a legal file-name character on Linux/macOS, so the
        // basename has to be split on both separators, not just the host's.
        assertThat(ChatUploadResolver.resolve("C:\\Users\\me\\report.pdf"))
                .isEqualTo(dateDir.resolve("1777391026594_report.pdf"));
    }

    @Test
    @DisplayName("missing file resolves to null in either layout")
    void missingFileIsNull() throws Exception {
        conversationDir("conv-missing");

        assertThat(ChatUploadResolver.resolve("nope.pdf")).isNull();
    }
}
