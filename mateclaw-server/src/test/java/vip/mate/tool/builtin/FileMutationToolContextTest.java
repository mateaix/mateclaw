package vip.mate.tool.builtin;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.i18n.I18nService;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for issue #617: file mutation tools must use the
 * explicit Spring AI ToolContext instead of relying on legacy thread-local
 * workspace state.
 */
class FileMutationToolContextTest {

    @AfterEach
    void tearDown() {
        WorkspacePathGuard.setDefaultRoot(null);
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("write_file resolves relative paths against ToolContext workspace root (#617)")
    void writeFileUsesToolContextWorkspaceRoot(@TempDir Path tempDir) throws Exception {
        Path defaultRoot = Files.createDirectory(tempDir.resolve("default-root"));
        Path contextRoot = Files.createDirectory(tempDir.resolve("context-root"));
        WorkspacePathGuard.setDefaultRoot(defaultRoot.toString());
        WriteFileTool tool = new WriteFileTool(i18n());

        String result = tool.write_file(
                "deck.md",
                "# Deck",
                ChatOrigin.web("conv-617", "alice", 1L, contextRoot.toString()).toToolContext());

        assertThat(JSONUtil.parseObj(result).getBool("error", false)).isFalse();
        assertThat(contextRoot.resolve("deck.md")).hasContent("# Deck");
        assertThat(defaultRoot.resolve("deck.md")).doesNotExist();
    }

    @Test
    @DisplayName("edit_file resolves relative paths against ToolContext workspace root (#617)")
    void editFileUsesToolContextWorkspaceRoot(@TempDir Path tempDir) throws Exception {
        Path defaultRoot = Files.createDirectory(tempDir.resolve("default-root"));
        Path contextRoot = Files.createDirectory(tempDir.resolve("context-root"));
        WorkspacePathGuard.setDefaultRoot(defaultRoot.toString());
        Files.writeString(contextRoot.resolve("deck.md"), "old title", StandardCharsets.UTF_8);
        EditFileTool tool = new EditFileTool(i18n());

        String result = tool.edit_file(
                "deck.md",
                "old",
                "new",
                false,
                ChatOrigin.web("conv-617", "alice", 1L, contextRoot.toString()).toToolContext());

        assertThat(JSONUtil.parseObj(result).getBool("error", false)).isFalse();
        assertThat(contextRoot.resolve("deck.md")).hasContent("new title");
        assertThat(defaultRoot.resolve("deck.md")).doesNotExist();
    }

    private static I18nService i18n() {
        return mock(I18nService.class, inv ->
                "msg".equals(inv.getMethod().getName()) ? inv.getArgument(0) : null);
    }
}
