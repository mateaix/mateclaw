package vip.mate.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link SkillScriptExecutionService#executeCode}, the inline
 * code-execution entry point that makes documentation-only skills runnable.
 *
 * <p>Subprocess-backed cases are gated on the interpreter being present so the
 * suite stays green on hosts without python / bash (e.g. Windows CI).
 */
class SkillScriptExecutionServiceCodeTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final SkillScriptExecutionService service = new SkillScriptExecutionService();

    @Test
    @DisplayName("rejects an unsupported language")
    void rejectsUnknownLanguage(@TempDir Path dir) {
        var result = service.executeCode("ruby", "puts 1", dir, null, Map.of(), null);
        assertThat(result.getExitCode()).isEqualTo(-1);
        assertThat(result.getStderr()).contains("Unsupported language");
    }

    @Test
    @DisplayName("rejects blank code")
    void rejectsBlankCode(@TempDir Path dir) {
        var result = service.executeCode("python", "   ", dir, null, Map.of(), null);
        assertThat(result.getExitCode()).isEqualTo(-1);
        assertThat(result.getStderr()).contains("No code supplied");
    }

    @Test
    @DisplayName("rejects a non-existent caller-supplied working directory")
    void rejectsMissingWorkingDir() {
        var result = service.executeCode("python", "print(1)",
                Path.of("/no/such/dir/" + System.nanoTime()), null, Map.of(), null);
        assertThat(result.getExitCode()).isEqualTo(-1);
        assertThat(result.getStderr()).contains("Working directory does not exist");
    }

    @Test
    @DisplayName("runs in a private scratch dir when working directory is null")
    void runsWithNullWorkingDir() {
        assumeTrue(hasInterpreter(IS_WINDOWS ? "python" : "python3"));
        var result = service.executeCode("python", "print('scratch-ok')", null, null, Map.of(), null);
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("scratch-ok");
    }

    @Test
    @DisplayName("runs python code and captures stdout")
    void runsPython(@TempDir Path dir) {
        assumeTrue(hasInterpreter(IS_WINDOWS ? "python" : "python3"));
        var result = service.executeCode("python", "print('hello from py')", dir, null, Map.of(), null);
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("hello from py");
    }

    @Test
    @DisplayName("injects supplied env vars into the subprocess")
    void injectsEnvVars(@TempDir Path dir) {
        assumeTrue(hasInterpreter(IS_WINDOWS ? "python" : "python3"));
        var result = service.executeCode("python",
                "import os; print(os.environ.get('MY_SKILL_TOKEN'))",
                dir, null, Map.of("MY_SKILL_TOKEN", "s3cr3t"), null);
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("s3cr3t");
    }

    @Test
    @DisplayName("scrubs sensitive host env vars from the code subprocess")
    void scrubsSensitiveHostEnv(@TempDir Path dir) {
        assumeTrue(hasInterpreter(IS_WINDOWS ? "python" : "python3"));
        // A *_KEY name in the parent process must not leak into LLM-authored code.
        // We can't set the parent env here, but PATH-like vars survive while any
        // KEY/SECRET/TOKEN parent var is stripped — assert a known scrubbed name
        // is absent rather than relying on a specific host secret being set.
        var result = service.executeCode("python",
                "import os; print('LEAK' if any(k.endswith('_SECRET') for k in os.environ) else 'CLEAN')",
                dir, null, Map.of(), null);
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("CLEAN");
    }

    @Test
    @DisplayName("runs bash code on unix")
    void runsBash(@TempDir Path dir) {
        assumeTrue(!IS_WINDOWS && hasInterpreter("bash"));
        var result = service.executeCode("bash", "echo from-bash", dir, null, Map.of(), null);
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("from-bash");
    }

    @Test
    @DisplayName("forwards positional args to the program")
    void forwardsArgs(@TempDir Path dir) {
        assumeTrue(hasInterpreter(IS_WINDOWS ? "python" : "python3"));
        var result = service.executeCode("python",
                "import sys; print(sys.argv[1])", dir, List.of("the-arg"), Map.of(), null);
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("the-arg");
    }

    @Test
    @DisplayName("leaves no temp code file behind in the working directory")
    void cleansUpTempFile(@TempDir Path dir) {
        assumeTrue(hasInterpreter(IS_WINDOWS ? "python" : "python3"));
        service.executeCode("python", "print('x')", dir, null, Map.of(), null);
        try (var stream = Files.list(dir)) {
            assertThat(stream.toList()).isEmpty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasInterpreter(String name) {
        try {
            Process p = new ProcessBuilder(name, "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
