package vip.mate.tool.guard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import vip.mate.tool.builtin.ToolExecutionContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link WorkspacePathGuard#validateShellCommand(String)} — the
 * static command-string scan that backstops {@code ShellExecuteTool} so an
 * absolute-path reference in the shell command cannot reach outside the
 * configured workspace boundary, even though the shell process itself has
 * full filesystem permissions.
 *
 * <p>The boundary is read via {@link ToolExecutionContext#workspaceBasePath()}.
 */
@DisabledOnOs(OS.WINDOWS) // POSIX-style absolute paths in these cases
class WorkspacePathGuardShellTest {

    private static final String WORKSPACE = "/tmp/ws-guard-shell-test";
    private static final String SKILL_ROOT = "/tmp/ws-guard-skill-root";

    @BeforeEach
    void setup() {
        ToolExecutionContext.set("conv-test", "test-user", WORKSPACE);
    }

    @AfterEach
    void teardown() {
        ToolExecutionContext.clear();
        WorkspacePathGuard.setSkillRoot(null);
    }

    // ==================== No-op when sandbox absent ====================

    @Test
    @DisplayName("No workspace configured → all commands pass")
    void noWorkspace_noop() {
        ToolExecutionContext.clear();
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("rm -rf /"));
    }

    @Test
    @DisplayName("Null or empty command → no-op")
    void nullOrEmpty_noop() {
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(null));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(""));
    }

    // ==================== In-boundary commands pass ====================

    @Test
    @DisplayName("Relative paths and in-workspace absolute paths pass")
    void inBoundary_pass() {
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("ls -la"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("cat foo.txt"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("cat subdir/bar.txt"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "cat " + WORKSPACE + "/foo.txt"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "cd " + WORKSPACE + "/subdir && ls"));
    }

    @Test
    @DisplayName("URLs are not mistaken for filesystem paths")
    void urls_pass() {
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "curl -s https://example.com/api/data"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "wget -O out.txt http://host:8080/path/to/file"));
    }

    // ==================== Out-of-boundary absolute paths blocked ====================

    @Test
    @DisplayName("Absolute path outside workspace → rejected")
    void absoluteOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("head -3 /Users/someone/code/secret.md"));
    }

    @Test
    @DisplayName("Output redirection to outside path → rejected")
    void redirection_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("echo evil > /tmp/leak.txt"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ls >> /var/log/sneak.log"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("grep foo < /etc/hosts"));
    }

    @Test
    @DisplayName("cd / pushd to outside path → rejected")
    void cdOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cd /etc && ls"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("pushd /var/spool"));
    }

    @Test
    @DisplayName("ln -s to an outside target → rejected")
    void symlinkCreate_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ln -sf /etc/passwd alias"));
    }

    @Test
    @DisplayName("Pipe with outside path on either side → rejected")
    void pipeOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat /etc/passwd | grep root"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("env | tee /tmp/dump.txt"));
    }

    @Test
    @DisplayName("Quoted absolute path → rejected")
    void quotedAbsolute_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat \"/etc/passwd\""));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat '/etc/passwd'"));
    }

    @Test
    @DisplayName("Command substitution $(...) with outside path → rejected")
    void commandSubstOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("echo $(cat /etc/passwd)"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("X=`head /etc/hostname`; echo $X"));
    }

    // ==================== Tilde + env-var rejection ====================

    @Test
    @DisplayName("Tilde expansion → rejected")
    void tilde_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat ~/.zshrc"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cd ~ && ls"));
    }

    @Test
    @DisplayName("$HOME / ${HOME} / $TMPDIR / $PATH → rejected")
    void envVarOutside_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat $HOME/.zshrc"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ls ${HOME}/Documents"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("touch $TMPDIR/leak"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("echo bad > $PATH/evil"));
    }

    @Test
    @DisplayName("Other env vars not on the deny list are allowed")
    void unrelatedEnvVar_pass() {
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("echo $LANG"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("printf '%s\\n' \"$MY_FLAG\""));
    }

    // ==================== Device-node allowlist ====================

    @Test
    @DisplayName("/dev/null and other standard device nodes are allowed")
    void deviceNodes_pass() {
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("find . -name '*.md' 2>/dev/null"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("ls -la > /dev/null"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("cat /dev/urandom | head -c 16"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("dd if=/dev/zero of=zeros.bin bs=1024 count=1"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("read line < /dev/stdin"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("echo hi > /dev/stderr"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("tty < /dev/tty"));
    }

    @Test
    @DisplayName("/dev/fd/N (process substitution) is allowed")
    void devFd_pass() {
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("diff <(sort file_a.txt) <(sort file_b.txt)"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("cat /dev/fd/0"));
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("read line < /dev/fd/3"));
    }

    // ==================== Relative parent-directory traversal ====================

    @Test
    @DisplayName("Bare `cd ..` → rejected")
    void cdDotDot_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cd .. && ls"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cd .."));
    }

    @Test
    @DisplayName("Relative parent traversal `../foo` → rejected")
    void relativeParentTraversal_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat ../mateclaw/CLAUDE.md"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("head -3 ../README.md"));
    }

    @Test
    @DisplayName("Symlink creation with relative outside-pointing target → rejected")
    void relativeSymlinkEscape_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ln -sf ../mateclaw breakout"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ln -s ../../etc shortcut"));
    }

    @Test
    @DisplayName("Deeper relative traversal `foo/../../bar` → rejected")
    void deepRelativeTraversal_blocked() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat subdir/../../other/file.txt"));
    }

    @Test
    @DisplayName("In-workspace `..` traversal that normalizes back inside → allowed")
    void inWorkspaceTraversal_pass() {
        // subdir/../sibling → workspace/sibling, still inside.
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("cat subdir/../sibling.txt"));
        // ./.. is at workspace root after normalize — still inside? No: ./.. is parent of cwd.
        // We do want to reject that, which the regex will catch as escape.
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("ls foo/.."));  // resolves to workspace root
    }

    @Test
    @DisplayName("Identifier with double-dot but no slash (e.g. `abc..xyz`) is not a path → allowed")
    void doubleDotInIdentifier_pass() {
        // "..foo" / "abc..xyz" should not be confused with parent traversal.
        // These appear in version strings, env var values, etc.
        assertDoesNotThrow(() ->
                WorkspacePathGuard.validateShellCommand("echo version=1.2..3"));
    }

    // ==================== Shared skill root allowance ====================

    @Test
    @DisplayName("Skill root is trusted in addition to the workspace")
    void skillRoot_pass() {
        WorkspacePathGuard.setSkillRoot(SKILL_ROOT);
        // Reading and running a shared skill's files from a workspace elsewhere.
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "cat " + SKILL_ROOT + "/zclt-toolkit/SKILL.md"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "bash " + SKILL_ROOT + "/zclt-toolkit/scripts/run.sh"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "cd " + SKILL_ROOT + "/zclt-toolkit && ls"));
        // The workspace itself still passes.
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                "cat " + WORKSPACE + "/foo.txt"));
    }

    @Test
    @DisplayName("Without a skill root, the same skill path is still blocked")
    void skillRoot_unset_blocked() {
        // No skill root registered → skill path is just another outside path.
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat " + SKILL_ROOT + "/zclt-toolkit/SKILL.md"));
    }

    @Test
    @DisplayName("A skill root does not widen the boundary to unrelated outside paths")
    void skillRoot_doesNotWidenOtherPaths() {
        WorkspacePathGuard.setSkillRoot(SKILL_ROOT);
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("echo evil > /tmp/leak.txt"));
    }

    // ==================== Device-node negative cases ====================

    @Test
    @DisplayName("Non-allowlisted /dev/* paths still rejected")
    void devOther_blocked() {
        // Block-device-like paths must not be allowed by the allowlist.
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("dd if=/dev/disk0 of=image.bin"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat /dev/loop0"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("ls /dev/null/sneak"));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspacePathGuard.validateShellCommand("cat /dev/fd/notanumber"));
    }
}
