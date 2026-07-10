package vip.mate.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the pip mirror env-var injection in
 * {@link SkillScriptExecutionService}.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li><b>Desktop fallback</b> — {@code PIP_INDEX_URL} absent from the
 *       process env → Spring config ({@code mateclaw.pip.index-url}) is
 *       injected so Python subprocesses can still find the mirror.</li>
 *   <li><b>Docker / env-var precedence</b> — {@code PIP_INDEX_URL} already
 *       present in the subprocess env (set via docker-compose or system env)
 *       → Spring config does NOT override it.</li>
 * </ul>
 *
 * <p>Bash-gated so the suite stays green on hosts without bash (e.g. Windows CI).
 */
class SkillScriptExecutionServicePipMirrorTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String BASH_SCRIPT =
            "echo PIP_INDEX_URL=$PIP_INDEX_URL\n" +
            "echo PIP_TRUSTED_HOST=$PIP_TRUSTED_HOST\n";

    private SkillScriptExecutionService newService(String indexUrl, String trustedHost) {
        SkillScriptExecutionService svc = new SkillScriptExecutionService();
        ReflectionTestUtils.setField(svc, "pipIndexUrl", indexUrl);
        ReflectionTestUtils.setField(svc, "pipTrustedHost", trustedHost);
        return svc;
    }

    @Test
    @DisplayName("desktop fallback: Spring config injected when PIP_INDEX_URL absent from env")
    void springConfigInjectedWhenEnvAbsent(@TempDir Path dir) {
        assumeTrue(!IS_WINDOWS && hasInterpreter("bash"));
        // Skip if the host already has PIP_INDEX_URL set — the inherited env
        // var would make containsKey() true, hiding the Spring fallback path.
        assumeTrue(System.getenv("PIP_INDEX_URL") == null,
                "PIP_INDEX_URL already set in host environment");

        var svc = newService("http://192.168.1.100:8080/simple", "192.168.1.100");

        var result = svc.executeCode("bash", BASH_SCRIPT, dir, null, Map.of(), null);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("PIP_INDEX_URL=http://192.168.1.100:8080/simple");
        assertThat(result.getStdout()).contains("PIP_TRUSTED_HOST=192.168.1.100");
    }

    @Test
    @DisplayName("Docker case: env var takes precedence over Spring config")
    void envVarTakesPrecedenceOverSpringConfig(@TempDir Path dir) {
        assumeTrue(!IS_WINDOWS && hasInterpreter("bash"));

        var svc = newService("http://spring-fallback:8080/simple", "spring-fallback");

        // Simulate Docker: PIP_INDEX_URL already in the subprocess env
        var result = svc.executeCode("bash", BASH_SCRIPT, dir, null,
                Map.of("PIP_INDEX_URL", "http://docker-env:9090/simple",
                       "PIP_TRUSTED_HOST", "docker-env"),
                null);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("PIP_INDEX_URL=http://docker-env:9090/simple");
        assertThat(result.getStdout()).contains("PIP_TRUSTED_HOST=docker-env");
        assertThat(result.getStdout()).doesNotContain("spring-fallback");
    }

    @Test
    @DisplayName("no config: nothing injected, pip uses defaults")
    void noPipConfigNoInjection(@TempDir Path dir) {
        assumeTrue(!IS_WINDOWS && hasInterpreter("bash"));
        assumeTrue(System.getenv("PIP_INDEX_URL") == null,
                "PIP_INDEX_URL already set in host environment");

        var svc = newService("", "");

        var result = svc.executeCode("bash", BASH_SCRIPT, dir, null, Map.of(), null);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).doesNotContain("http://");
    }

    @Test
    @DisplayName("HTTPS index-url, no trusted-host → not auto-derived")
    void onlyIndexUrlSetHttps(@TempDir Path dir) {
        assumeTrue(!IS_WINDOWS && hasInterpreter("bash"));
        assumeTrue(System.getenv("PIP_INDEX_URL") == null,
                "PIP_INDEX_URL already set in host environment");

        var svc = newService("https://pypi.tuna.tsinghua.edu.cn/simple", "");

        var result = svc.executeCode("bash", BASH_SCRIPT, dir, null, Map.of(), null);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout())
                .contains("PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple");
        // HTTPS + valid cert → no trusted-host needed, should NOT be auto-derived
        assertThat(result.getStdout()).contains("PIP_TRUSTED_HOST=\n");
    }

    @Test
    @DisplayName("HTTP index-url, no trusted-host → auto-derived from URL")
    void httpAutoDeriveTrustedHost(@TempDir Path dir) {
        assumeTrue(!IS_WINDOWS && hasInterpreter("bash"));
        assumeTrue(System.getenv("PIP_INDEX_URL") == null,
                "PIP_INDEX_URL already set in host environment");

        var svc = newService("http://192.168.1.100:8080/simple", "");

        var result = svc.executeCode("bash", BASH_SCRIPT, dir, null, Map.of(), null);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout())
                .contains("PIP_INDEX_URL=http://192.168.1.100:8080/simple");
        // HTTP source → trusted-host auto-derived from URL
        assertThat(result.getStdout()).contains("PIP_TRUSTED_HOST=192.168.1.100");
    }

    @Test
    @DisplayName("HTTP index-url with explicit trusted-host → not overwritten")
    void httpExplicitTrustedHostNotOverwritten(@TempDir Path dir) {
        assumeTrue(!IS_WINDOWS && hasInterpreter("bash"));

        var svc = newService("http://192.168.1.100:8080/simple", "my-mirror.local");

        // Simulate Docker: both env vars already set
        var result = svc.executeCode("bash", BASH_SCRIPT, dir, null,
                Map.of("PIP_INDEX_URL", "http://10.0.0.5:9090/simple",
                       "PIP_TRUSTED_HOST", "10.0.0.5"),
                null);

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("PIP_INDEX_URL=http://10.0.0.5:9090/simple");
        assertThat(result.getStdout()).contains("PIP_TRUSTED_HOST=10.0.0.5");
        // Neither Spring config nor auto-derive should override
        assertThat(result.getStdout()).doesNotContain("192.168.1.100");
        assertThat(result.getStdout()).doesNotContain("my-mirror.local");
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
