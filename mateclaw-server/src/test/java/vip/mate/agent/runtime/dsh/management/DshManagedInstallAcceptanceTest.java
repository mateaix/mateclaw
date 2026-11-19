package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vip.mate.agent.runtime.dsh.DshSdkProcess;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit disposable prefix only; never selects an active generation or opens the user database. */
@EnabledIfEnvironmentVariable(named="MATECLAW_DSH_INSTALL_ACCEPTANCE_PREFIX", matches=".+")
class DshManagedInstallAcceptanceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0.2.2-rc.1", "0.2.0-rc.1", "0.2.1-rc.2", "0.1.7-rc.2"})
    void exactManagedCandidateInstallsAndInitializesWithItsPolicyPatch(String version) throws Exception {
        var json = new ObjectMapper();
        Path prefix = Path.of(System.getenv("MATECLAW_DSH_INSTALL_ACCEPTANCE_PREFIX")).toAbsolutePath().resolve(version);
        var installed = new DshPinnedInstaller(json).install(version, prefix);
        assertEquals(version, installed.version());
        assertEquals(prefix.resolve("0.2.2-rc.1".equals(version) ? "bin/dsh" : "node_modules/.bin/dsh").toString(), installed.executablePath());
        Path home = Files.createTempDirectory("dsh-022-install-home");
        var builder = new ProcessBuilder(installed.executablePath(), "--profile", "sdk").directory(home.toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", System.getenv("PATH"), "HOME", home.toString(), "DSH_HOME", home.toString(), "DSH_AGENTS_HOME", home.resolve("agents").toString()));
        try(var sdk = new DshSdkProcess(builder.start(), json, "")) {
            var reply = sdk.initialize(Map.of("cwd",home.toString(),"provider","deepseek-official","model","deepseek-v4-flash","maxTokens",64), Duration.ofSeconds(30));
            assertEquals("deepseek-harness-sdk-runtime", reply.path("serverInfo").path("name").asText());
        } finally {
            try(var paths=Files.walk(home)) { for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
}
