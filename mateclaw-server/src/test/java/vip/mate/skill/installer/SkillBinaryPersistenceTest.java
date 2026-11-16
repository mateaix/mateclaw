package vip.mate.skill.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.skill.installer.model.SkillBundle;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.workspace.*;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkillBinaryPersistenceTest {
    @TempDir Path root;
    @Test void installerSendsSameBytesToCanonicalStoreAndWorkspace() throws Exception {
        var props = new SkillWorkspaceProperties(); props.setRoot(root.toString());
        var manager = new SkillWorkspaceManager(props, event -> {});
        var store = mock(SkillFileService.class);
        var applied = new SkillFileService.ApplyResult(2, 0, false, false);
        when(store.applyBundleFiles(anyLong(), anyMap(), anyBoolean())).thenReturn(applied);
        when(store.applyBundleBytes(anyLong(), anyMap(), anyBoolean())).thenReturn(applied);
        var installer = new SkillInstaller(null, null, manager, null, store, new com.fasterxml.jackson.databind.ObjectMapper(), event -> {});
        byte[] binary = {(byte)0xff, 0, 1, 13, 10};
        var bundle = new SkillBundle("test", "# test", Map.of(), Map.of("run.py", "print('中文')\r\n"), "github", "test", "1", "", "", "",
                Map.of("references/font.woff", binary, "templates/sample.docx", binary));
        var skill = new SkillEntity(); skill.setId(42L); skill.setName("test");
        var method = SkillInstaller.class.getDeclaredMethod("persistBundleFiles", SkillEntity.class, SkillBundle.class, boolean.class, String.class, Long.class);
        method.setAccessible(true); method.invoke(installer, skill, bundle, false, "test", 1L);
        assertArrayEquals(binary, Files.readAllBytes(manager.resolveConventionPath("test", 1L).resolve("references/font.woff")));
        assertArrayEquals(binary, Files.readAllBytes(manager.resolveConventionPath("test", 1L).resolve("templates/sample.docx")));
        assertArrayEquals("print('中文')\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(manager.resolveConventionPath("test", 1L).resolve("scripts/run.py")));
        verify(store).applyBundleBytes(eq(42L), argThat(files -> java.util.Arrays.equals(binary, files.get("references/font.woff"))
                && java.util.Arrays.equals(binary, files.get("templates/sample.docx"))), eq(false));
    }
    @Test void byteBundlesRetainEmptyBucketGuardAndPruneStaleAttachments() throws Exception {
        var props = new SkillWorkspaceProperties(); props.setRoot(root.toString());
        var manager = new SkillWorkspaceManager(props, event -> {});
        byte[] bytes = {0, (byte)0xff, 1};
        manager.applyBundleBytes("test", Map.of("references/old.bin", bytes, "templates/old.bin", bytes), false, 1L);
        var workspace = manager.resolveConventionPath("test", 1L);
        manager.applyBundleBytes("test", Map.of(), false, 1L);
        assertArrayEquals(bytes, Files.readAllBytes(workspace.resolve("references/old.bin")));
        assertArrayEquals(bytes, Files.readAllBytes(workspace.resolve("templates/old.bin")));
        manager.applyBundleBytes("test", Map.of("references/new.bin", bytes), false, 1L);
        assertFalse(Files.exists(workspace.resolve("references/old.bin")));
        assertTrue(Files.exists(workspace.resolve("templates/old.bin")));
        manager.applyBundleBytes("test", Map.of(), true, 1L);
        assertFalse(Files.exists(workspace.resolve("references/new.bin")));
        assertFalse(Files.exists(workspace.resolve("templates/old.bin")));
    }
}
