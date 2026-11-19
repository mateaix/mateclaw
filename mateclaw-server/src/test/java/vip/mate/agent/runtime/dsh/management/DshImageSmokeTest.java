package vip.mate.agent.runtime.dsh.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Run against the Dockerfile's runtime-base target, without a model key or database.
 * MATECLAW_DSH_IMAGE_SMOKE=your-amd64-image mvn -pl mateclaw-server -am test
 *   -Dtest=DshImageSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
 * Default sandbox checks require native amd64 Linux. For an isolated diagnostic
 * container on ARM/emulation, MATECLAW_DSH_IMAGE_SMOKE_PRIVILEGED=true explicitly
 * enables bwrap namespaces and requires the bwrap backend. This is test-only.
 */
@EnabledIfEnvironmentVariable(named = "MATECLAW_DSH_IMAGE_SMOKE", matches = ".+")
@Timeout(value = 12, unit = TimeUnit.MINUTES)
class DshImageSmokeTest {
    @TempDir Path temp;

    @Test
    void imageSupportsManagedInstall() throws Exception {
        checkImage(false);
    }

    @Test
    void imageSupportsManagedInstallAndConfinedBash() throws Exception {
        checkImage(true);
    }

    private void checkImage(boolean checkBash) throws Exception {
        // Stage only the installer and its actual dependencies; do not start Spring or use a DB.
        Path classes = Files.createDirectories(temp.resolve("classes"));
        for (Class<?> type : List.of(DshPinnedInstaller.class, DshPinnedInstaller.Release.class,
                DshPinnedInstaller.Installation.class, InstallerMain.class)) {
            String resource = type.getName().replace('.', '/') + ".class";
            Path target = classes.resolve(resource);
            Files.createDirectories(target.getParent());
            try (var input = type.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                Files.copy(input, target);
            }
        }
        Path libs = Files.createDirectories(temp.resolve("libs"));
        for (String entry : System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).split(java.io.File.pathSeparator)) {
            Path jar = Path.of(entry);
            String name = jar.getFileName().toString();
            if (name.endsWith(".jar") && (name.startsWith("jackson-") || name.startsWith("spring-core-")
                    || name.startsWith("spring-jcl-") || name.startsWith("commons-logging-"))) {
                Files.copy(jar, libs.resolve(name));
            }
        }
        for (var release : DshPinnedInstaller.catalog()) {
            Path lock = classes.resolve("dsh/locks/" + release.version() + ".json");
            Files.createDirectories(lock.getParent());
            try (var input = getClass().getResourceAsStream("/dsh/locks/" + release.version() + ".json")) {
                assertNotNull(input);
                Files.copy(input, lock);
            }
        }
        for (String resource : List.of("mateclaw-sdk.patch.yml", "mateclaw-sdk-launcher.sh")) {
            try (var input = getClass().getResourceAsStream("/dsh/" + resource)) {
                assertNotNull(input); Files.copy(input, classes.resolve("dsh/" + resource));
            }
        }
        try (var input = getClass().getResourceAsStream("/dsh/image-bash-smoke.mjs")) {
            assertNotNull(input);
            Files.copy(input, temp.resolve("bash-smoke.mjs"));
        }
        var command = new ArrayList<>(List.of("docker", "run", "--rm", "--platform", "linux/amd64",
                "--name", "mateclaw-dsh-image-smoke-" + java.util.UUID.randomUUID(),
                "--env", "CHECK_DSH_BASH=" + checkBash,
                "--volume", temp.toAbsolutePath() + ":/checks:ro", "--entrypoint", "bash",
                System.getenv("MATECLAW_DSH_IMAGE_SMOKE"), "-ec", """
                node -e 'if (process.versions.node.split(".")[0] !== "24") throw Error("Node 24 required")'
                npm --version
                command -v bwrap
                # Docker may deny user namespaces. Require a working dsh sandbox below.
                if bwrap --ro-bind / / --dev /dev --proc /proc --unshare-user \
                    --unshare-pid --die-with-parent bash -c 'printf BWRAP_OK'; then
                  echo ' DSH_BWRAP_OK'
                else
                  echo 'DSH_BWRAP_RESTRICTED: host namespace permissions required for bwrap'
                fi
                for version in 0.2.0-rc.1 0.2.1-rc.2 0.2.2-rc.1; do
                java -cp '/checks/classes:/checks/libs/*' \
                  'vip.mate.agent.runtime.dsh.management.DshImageSmokeTest$InstallerMain' "$version"
                if [ "$CHECK_DSH_BASH" = true ]; then
                  DSH_PREFIX=/tmp/dsh-install-$version node /checks/bash-smoke.mjs
                fi
                done
                """));
        boolean privileged = Boolean.parseBoolean(System.getenv("MATECLAW_DSH_IMAGE_SMOKE_PRIVILEGED"));
        if (privileged) command.addAll(2, List.of("--privileged", "--env", "DSH_EXPECT_BWRAP=true"));
        System.out.println("DSH_IMAGE_SMOKE privileged=" + privileged + " checkBash=" + checkBash);
        Path log = temp.resolve("smoke.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(Duration.ofMinutes(10).toMillis(), TimeUnit.MILLISECONDS), "image smoke timeout");
            String output = Files.readString(log);
            System.out.println(output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("DSH_INSTALL_OK"), output);
            if (checkBash) assertTrue(output.contains("DSH_BASH_SANDBOX_OK"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Process cleanup = new ProcessBuilder("docker", "rm", "--force", command.get(command.indexOf("--name") + 1))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(15, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        }
    }

    public static class InstallerMain {
        public static void main(String[] args) throws Exception {
            var installed = new DshPinnedInstaller(new ObjectMapper())
                    .install(args[0], Path.of("/tmp/dsh-install-" + args[0]));
            if (!Files.isExecutable(Path.of(installed.executablePath()))) throw new AssertionError("dsh executable");
            Process cli = new ProcessBuilder(installed.executablePath(), "--version")
                    .redirectErrorStream(true).start();
            try {
                if (!cli.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("dsh CLI timeout");
                String version = new String(cli.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (cli.exitValue() != 0 || !version.contains(installed.version())) throw new AssertionError(version);
            } finally {
                if (cli.isAlive()) cli.destroyForcibly();
            }
            System.out.println("DSH_INSTALL_OK " + installed.version() + " " + installed.platform());
        }
    }
}
