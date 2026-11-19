package vip.mate.agent.runtime.dsh.management;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class DshPinnedInstallerTest {
    @Test
    void catalogRequiresExactKnownVersionAndCorrectHostPlatform() {
        assertEquals("0.2.2-rc.1", DshPinnedInstaller.release("0.2.2-rc.1").version());
        assertEquals("0.2.0-rc.1", DshPinnedInstaller.catalog().getFirst().version());
        assertEquals("0.2.0-rc.1", DshPinnedInstaller.release("0.2.0-rc.1").version());
        assertTrue(DshPinnedInstaller.catalog().stream().anyMatch(release -> release.version().equals("0.2.1-rc.2")));
        assertEquals("sha512-tvY+yhfKRifthbqPe/OK0AYxM5ZkvqfYq/jMYgJcEUxiRRL1Vwra3Ti5TB6AXgSWQdiPHYh/Vp6SJa9yHwMNuw==",
                DshPinnedInstaller.release("0.2.1-rc.2").integrity());
        assertTrue(DshPinnedInstaller.release("0.1.7-rc.2").integrity().startsWith("sha512-"));
        assertThrows(IllegalArgumentException.class, () -> DshPinnedInstaller.release("latest"));
        assertThrows(IllegalArgumentException.class, () -> DshPinnedInstaller.release("1.0.0"));
        assertEquals("linux-x64", DshPinnedInstaller.platform("Linux", "amd64"));
        assertEquals("darwin-arm64", DshPinnedInstaller.platform("Mac OS X", "aarch64"));
        assertThrows(IllegalArgumentException.class, () -> DshPinnedInstaller.platform("Windows", "amd64"));
    }
    @Test
    void trustedLocksPinAllRegistryPackagesBeforeInstallScripts() throws Exception {
        var mapper = new ObjectMapper();
        for (var release : DshPinnedInstaller.catalog()) {
            try (var input = getClass().getResourceAsStream("/dsh/locks/" + release.version() + ".json")) {
                assertNotNull(input);
                var packages = mapper.readTree(input).path("packages");
                assertEquals(release.integrity(), packages.path("node_modules/@deepseek-ai/dsh").path("integrity").asText());
                assertTrue(packages.size() > 100);
                var scripts = DshPinnedInstaller.installScripts(packages);
                assertFalse(scripts.isEmpty());
                for (String name : scripts.keySet()) {
                    assertTrue(packages.path("node_modules/" + name).path("hasInstallScript").asBoolean(), name);
                    assertTrue(scripts.get(name));
                }
                var entries = packages.fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    if (entry.getKey().isEmpty()) continue; // local root manifest
                    assertTrue(entry.getValue().path("resolved").asText().startsWith("https://registry.npmjs.org/"), entry.getKey());
                    assertTrue(entry.getValue().path("integrity").asText().startsWith("sha512-"), entry.getKey());
                }
            }
        }
    }

    @Test void nodeRequirementsAreReleaseSpecificAndRejectUnreviewedMajors() {
        for (String version : java.util.List.of("0.2.0-rc.1", "0.2.1-rc.2", "0.1.7-rc.2")) {
            assertDoesNotThrow(() -> DshPinnedInstaller.requireNodeVersion(version, "v22.20.0"));
            assertDoesNotThrow(() -> DshPinnedInstaller.requireNodeVersion(version, "v24.22.2"));
        }
        assertDoesNotThrow(() -> DshPinnedInstaller.requireNodeVersion("0.2.2-rc.1", "v24.9.0"));
        assertDoesNotThrow(() -> DshPinnedInstaller.requireNodeVersion("0.2.2-rc.1", "v24.22.2"));
        for (String node : java.util.List.of("v22.20.0", "v24.8.9", "v25.8.1", "v26.0.0", "v24.9.0-pre", "garbage")) {
            assertThrows(IllegalStateException.class, () -> DshPinnedInstaller.requireNodeVersion("0.2.2-rc.1", node), node);
        }
    }

    @Test void managedLauncherRestoresObservationPolicyAndPreservesArgumentBoundaries(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var prefix = java.nio.file.Files.createDirectories(temp.resolve("prefix with spaces"));
        var upstream = prefix.resolve("node_modules/.bin/dsh");
        java.nio.file.Files.createDirectories(upstream.getParent());
        java.nio.file.Files.writeString(upstream, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        assertTrue(upstream.toFile().setExecutable(true));
        var executable = DshPinnedInstaller.managedExecutable(prefix, "0.2.2-rc.1");
        var process = new ProcessBuilder(executable.toString(), "--profile", "sdk", "--patch", "admin patch.yml").start();
        var lines = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        assertEquals(0, process.waitFor());
        assertEquals(java.util.List.of("--patch", prefix.resolve("mateclaw-sdk.patch.yml").toString(), "--profile", "sdk", "--patch", "admin patch.yml"), lines);
        var patch = java.nio.file.Files.readString(prefix.resolve("mateclaw-sdk.patch.yml"));
        assertTrue(patch.contains("id: fs-observation-policy\n  disabled: false"));
        assertTrue(patch.contains("MATECLAW_DSH_CONTEXT_WINDOW"));
        assertEquals(upstream, DshPinnedInstaller.managedExecutable(prefix, "0.2.1-rc.2"));
    }
}
