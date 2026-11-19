package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.runtime.dsh.DshRuntimeService;
import vip.mate.system.service.SettingCrypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshUpgradeServiceTest {
    @TempDir Path root;

    @Test
    void failedCandidateRetainsActiveGenerationAndOriginalHome() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DshGenerationStore store = new DshGenerationStore(root.toString(), mapper, new SettingCrypto("test"));
        Path home = Files.createDirectories(root.resolve("old-home"));
        Files.writeString(home.resolve("user-state"), "preserve");
        store.updateActiveValues(Map.of("dsh.home_root", home.toString(), "dsh.runtime_version", "0.1.7-rc.2"));
        String previous = store.activeGeneration();
        DshRuntimeConfigService config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration("/bin/dsh", "", root.toString(), "", "model", ""));
        DshRuntimeService runtime = mock(DshRuntimeService.class);
        DshPinnedInstaller installer = mock(DshPinnedInstaller.class);
        when(installer.install(eq("0.2.0-rc.1"), any())).thenThrow(new IllegalStateException("failure"));
        DshUpgradeService service = new DshUpgradeService(store, config, runtime, installer, mapper, Runnable::run);
        Map<String, String> op = service.upgrade("0.2.0-rc.1", store.revision(), "request-1");
        assertEquals("FAILED", service.operation(op.get("id")).get("state"));
        assertEquals(previous, store.activeGeneration());
        assertEquals("preserve", Files.readString(home.resolve("user-state")));
        try (var lease = store.acquireLease("w", "a")) { assertNotNull(lease); }
        service.upgrade("0.2.0-rc.1", op.get("expectedRevision"), "request-1");
        verify(installer, times(1)).install(eq("0.2.0-rc.1"), any());
    }

    @Test
    void firstUpgradeFailedHealthDoesNotCreateAnActivePointer() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DshGenerationStore store = new DshGenerationStore(root.toString(), mapper, new SettingCrypto("test"));
        Path home = Files.createDirectories(root.resolve("legacy-home"));
        Files.writeString(home.resolve("history"), "preserve");
        DshRuntimeConfigService config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration("/bin/dsh", "", root.toString(), "", "model", "", "sdk", List.of(), home.toString()));
        DshRuntimeService runtime = mock(DshRuntimeService.class);
        when(runtime.testConnectionAtHome(any(), any())).thenReturn(Map.of("success", false));
        DshPinnedInstaller installer = mock(DshPinnedInstaller.class);
        when(installer.install(eq("0.2.0-rc.1"), any())).thenReturn(new DshPinnedInstaller.Installation("0.2.0-rc.1", "darwin-arm64", "/bin/dsh", "hash"));
        DshUpgradeService service = new DshUpgradeService(store, config, runtime, installer, mapper, Runnable::run);
        Map<String, String> op = service.upgrade("0.2.0-rc.1", "0", "first-failed");
        assertEquals("FAILED", op.get("state"));
        assertEquals("", store.activeGeneration());
        assertEquals("0", store.revision());
        assertEquals("preserve", Files.readString(home.resolve("history")));
        try (var lease = store.acquireLease("w", "a")) { assertNotNull(lease); }
    }

    @Test
    void snapshotRejectsSymlinksWithoutFollowingOutsideRoot() throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Path outside = Files.writeString(root.resolve("private"), "private-content");
        Files.createSymbolicLink(source.resolve("link"), outside);
        assertThrows(Exception.class, () -> DshUpgradeService.copyHome(source, root.resolve("candidate")));
        assertEquals("private-content", Files.readString(outside));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0.2.0-rc.1", "0.2.2-rc.1"})
    void candidateChecksEveryCopiedProfileBeforeActivation(String targetVersion) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DshGenerationStore store = new DshGenerationStore(root.toString(), mapper, new SettingCrypto("test"));
        Path home = Files.createDirectories(root.resolve("old-home"));
        Files.createDirectories(home.resolve("w/a/profiles"));
        Files.createDirectories(home.resolve("w/b/profiles"));
        Files.writeString(home.resolve("w/a/state"), "original");
        store.updateActiveValues(Map.of("dsh.home_root", home.toString(), "dsh.runtime_version", "0.1.7-rc.2"));
        String previous = store.activeGeneration();
        DshRuntimeConfigService config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration("/bin/dsh", "", root.toString(), "", "model", ""));
        DshRuntimeService runtime = mock(DshRuntimeService.class);
        when(runtime.testConnectionAtHome(any(), any())).thenReturn(Map.of("success", true));
        when(runtime.testTaskAtHome(any(), any())).thenAnswer(invocation -> {
            Files.writeString(((Path) invocation.getArgument(1)).resolve("probe"), "candidate");
            return Map.of("success", true);
        });
        DshPinnedInstaller installer = mock(DshPinnedInstaller.class);
        when(installer.install(eq(targetVersion), any())).thenReturn(new DshPinnedInstaller.Installation(targetVersion, "darwin-arm64", "/bin/dsh", "hash"));
        DshUpgradeService service = new DshUpgradeService(store, config, runtime, installer, mapper, Runnable::run);
        Map<String, String> op = service.upgrade(targetVersion, store.revision(), "request-2");
        assertEquals("COMPLETED", op.get("state"));
        assertEquals("2", op.get("homesChecked"));
        assertEquals("PASSED", op.get("taskStatus"));
        assertNotEquals(previous, store.activeGeneration());
        assertFalse(Files.exists(home.resolve("w/a/probe")));
        verify(runtime, times(2)).testTaskAtHome(any(), any());
        String rollbackRevision = store.revision();
        Map<String, String> rollback = service.rollback(op.get("id"), rollbackRevision, "rollback-1");
        assertEquals(rollback.get("id"), service.rollback(op.get("id"), rollbackRevision, "rollback-1").get("id"));
        assertEquals("COMPLETED", rollback.get("state"));
        assertEquals("0.1.7-rc.2", store.activeValues().get("dsh.runtime_version"));
        assertEquals("original", Files.readString(home.resolve("w/a/state")));
    }

}
