package vip.mate.channel.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ChatController#toRelativeUploadPath} to return a <em>root-relative</em>
 * path, never the absolute server location.
 * <p>
 * Regression guard for the workspace-aware chat-uploads change: once the upload
 * root became absolute (the resolver normalizes via {@code toAbsolutePath()}),
 * the {@code path} field — which is rendered into the LLM prompt and returned to
 * the client — started leaking the server's absolute filesystem layout. These
 * tests lock the value to {@code chat-uploads/{convId}/[{date}/]{storedName}}.
 */
class ChatControllerUploadPathTest {

    @Test
    @DisplayName("default root: returns chat-uploads/{convId}/{storedName}, not absolute")
    void defaultRootIsRelative() {
        // Mirrors the resolver's default root: absolute + normalized.
        Path uploadRoot = Paths.get("data", "chat-uploads").toAbsolutePath().normalize();
        Path target = uploadRoot.resolve("conv-1").resolve("1777_a.txt");

        String path = ChatController.toRelativeUploadPath(uploadRoot, target);

        assertThat(path).isEqualTo("chat-uploads/conv-1/1777_a.txt");
        assertThat(Paths.get(path).isAbsolute()).isFalse();
        assertThat(path).doesNotContain(uploadRoot.toString());
    }

    @Test
    @DisplayName("date-folder target: date segment is preserved in the relative path")
    void dateFolderTargetKeepsDateSegment() {
        Path uploadRoot = Paths.get("data", "chat-uploads").toAbsolutePath().normalize();
        Path target = uploadRoot.resolve("conv-1").resolve("2026-07-26").resolve("1777_a.txt");

        String path = ChatController.toRelativeUploadPath(uploadRoot, target);

        assertThat(path).isEqualTo("chat-uploads/conv-1/2026-07-26/1777_a.txt");
        assertThat(Paths.get(path).isAbsolute()).isFalse();
    }

    @Test
    @DisplayName("workspace-scoped absolute root: still root-relative, no leak")
    void scopedRootIsRelative() {
        // An absolute workspace basePath somewhere outside the CWD.
        Path uploadRoot = Paths.get("/srv/ws/alpha/chat-uploads").toAbsolutePath().normalize();
        Path target = uploadRoot.resolve("conv-2").resolve("9_b.pdf");

        String path = ChatController.toRelativeUploadPath(uploadRoot, target);

        assertThat(path).isEqualTo("chat-uploads/conv-2/9_b.pdf");
        assertThat(path).doesNotContain("/srv/ws/alpha");
    }

    @Test
    @DisplayName("custom base-dir name is preserved (not hardcoded to chat-uploads)")
    void customBaseDirNamePreserved() {
        Path uploadRoot = Paths.get("/var/uploads").toAbsolutePath().normalize();
        Path target = uploadRoot.resolve("conv-3").resolve("f.bin");

        String path = ChatController.toRelativeUploadPath(uploadRoot, target);

        assertThat(path).isEqualTo("uploads/conv-3/f.bin");
    }
}
