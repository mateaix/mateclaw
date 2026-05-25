package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link ShellExecuteTool#selectPosixShell(String, Predicate)},
 * the helper that lets the shell tool honor the caller's {@code $SHELL}
 * instead of the hardcoded {@code /bin/sh} fallback.
 *
 * <p>Tests use the executable-check seam so they're platform-independent —
 * Windows CI doesn't have {@code /bin/sh}, POSIX dev hosts have varying
 * shells installed. The pure logic is tested here; the real invocation
 * goes through {@code Files::isExecutable} via the production overload.
 */
class ShellExecuteToolShellSelectionTest {

    private static final Predicate<Path> ALWAYS_EXECUTABLE = p -> true;
    private static final Predicate<Path> NEVER_EXECUTABLE = p -> false;

    @Test
    @DisplayName("null env → fallback to /bin/sh (no executable probe)")
    void nullEnvFallsBack() {
        assertEquals("/bin/sh",
                ShellExecuteTool.selectPosixShell(null, ALWAYS_EXECUTABLE));
    }

    @Test
    @DisplayName("empty / blank env → fallback to /bin/sh")
    void blankEnvFallsBack() {
        assertEquals("/bin/sh",
                ShellExecuteTool.selectPosixShell("", ALWAYS_EXECUTABLE));
        assertEquals("/bin/sh",
                ShellExecuteTool.selectPosixShell("   ", ALWAYS_EXECUTABLE));
    }

    @Test
    @DisplayName("$SHELL points at executable shell → honored verbatim")
    void executableShellHonored() {
        // The whole point of this lane: prefer the user's interactive shell
        // (zsh on macOS, bash on RHEL, fish on personal setups) over the
        // dash that /bin/sh symlinks to on Debian/Ubuntu.
        assertEquals("/usr/bin/zsh",
                ShellExecuteTool.selectPosixShell("/usr/bin/zsh", ALWAYS_EXECUTABLE));
        assertEquals("/usr/local/bin/fish",
                ShellExecuteTool.selectPosixShell("/usr/local/bin/fish", ALWAYS_EXECUTABLE));
    }

    @Test
    @DisplayName("$SHELL set but not executable → fallback to /bin/sh")
    void notExecutableFallsBack() {
        assertEquals("/bin/sh",
                ShellExecuteTool.selectPosixShell("/usr/bin/zsh", NEVER_EXECUTABLE));
    }

    @Test
    @DisplayName("invalid path string → fallback to /bin/sh, no exception")
    void invalidPathFallsBack() {
        // NUL byte makes Path.of throw InvalidPathException on POSIX.
        Predicate<Path> shouldNotBeReached = p -> {
            throw new AssertionError("executable check must not run on invalid path");
        };
        assertEquals("/bin/sh",
                ShellExecuteTool.selectPosixShell("/tmp/has\0null", shouldNotBeReached));
    }
}
