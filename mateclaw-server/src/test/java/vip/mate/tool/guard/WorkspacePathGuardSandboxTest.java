package vip.mate.tool.guard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import vip.mate.tool.builtin.ToolExecutionContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for two workspace-sandbox escape paths (issue #313):
 *
 * <ol>
 *   <li><b>Fail-closed default root</b> — when a conversation has no
 *       per-workspace base path, operations must fall back to the registered
 *       global sandbox root instead of running unconstrained. Without the
 *       fallback, a fresh install (workspace {@code base_path} unset) lets the
 *       agent read/write/delete anywhere the server process can reach.</li>
 *   <li><b>Workspace-root deletion guard</b> — the boundary check is reflexive
 *       ({@code root startsWith root}), so a delete aimed at the workspace root
 *       itself would otherwise pass and wipe the whole sandbox. Destructive
 *       commands targeting the root are rejected as escapes.</li>
 * </ol>
 */
@DisabledOnOs(OS.WINDOWS) // POSIX-style absolute paths in these cases
class WorkspacePathGuardSandboxTest {

    private static final String DEFAULT_ROOT = "/tmp/ws-guard-default-root";
    private static final String WORKSPACE = "/tmp/ws-guard-root-del-test";

    @AfterEach
    void teardown() {
        ToolExecutionContext.clear();
        WorkspacePathGuard.setDefaultRoot(null);
        WorkspacePathGuard.setSkillRoot(null);
        WorkspacePathGuard.clearTrustedRoots();
    }

    // ==================== Defect 1: fail-closed default root ====================

    @Nested
    @DisplayName("Fail-closed fallback to the global sandbox root")
    class FailClosed {

        @BeforeEach
        void setup() {
            // No per-conversation workspace configured — the out-of-the-box state.
            ToolExecutionContext.clear();
            WorkspacePathGuard.setDefaultRoot(DEFAULT_ROOT);
        }

        @Test
        @DisplayName("Shell: outside-the-root reads are rejected, not waved through")
        void shellOutside_blocked() {
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("ls .."));
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("rm -rf /tmp/somewhere-else"));
        }

        @Test
        @DisplayName("Shell: paths inside the default root still pass")
        void shellInside_pass() {
            assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("ls -la"));
            assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                    "cat " + DEFAULT_ROOT + "/foo.txt"));
        }

        @Test
        @DisplayName("validatePath: outside the default root is rejected")
        void validatePathOutside_blocked() {
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validatePath("/etc/passwd"));
        }

        @Test
        @DisplayName("validatePath: inside the default root is allowed")
        void validatePathInside_pass() {
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validatePath(DEFAULT_ROOT + "/notes/new-file.txt"));
        }

        @Test
        @DisplayName("Per-conversation workspace still takes precedence over the default root")
        void conversationWorkspace_wins() {
            ToolExecutionContext.set("conv", "user", WORKSPACE);
            // Inside the conversation workspace passes even though it's outside DEFAULT_ROOT.
            assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand(
                    "cat " + WORKSPACE + "/foo.txt"));
            // The default root is NOT additionally trusted when a workspace is set.
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("cat " + DEFAULT_ROOT + "/foo.txt"));
        }
    }

    @Nested
    @DisplayName("Escape hatch: no default root registered → legacy no-op")
    class Disabled {

        @BeforeEach
        void setup() {
            ToolExecutionContext.clear();
            WorkspacePathGuard.setDefaultRoot(null);
        }

        @Test
        @DisplayName("Without a default root, unconfigured conversations are unconstrained")
        void noDefaultRoot_noop() {
            assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
            assertDoesNotThrow(() -> WorkspacePathGuard.validatePath("/etc/passwd"));
        }
    }

    // ============ Tool-result spill dir is trusted outside the boundary (issue #403) ============

    @Nested
    @DisplayName("A registered tool-result spill root is readable outside the workspace boundary")
    class TrustedSpillRoot {

        private static final String SPILL_ROOT = "/tmp/mate-tool-result-spill/tool-results";

        @BeforeEach
        void setup() {
            // A conversation bound to a workspace, with a central spill dir that
            // lives outside that workspace — the production scenario from #403.
            ToolExecutionContext.set("conv", "user", WORKSPACE);
            WorkspacePathGuard.addTrustedRoot(SPILL_ROOT);
        }

        @Test
        @DisplayName("validatePath: reading a spilled tool result outside the workspace is allowed")
        void validatePathSpill_pass() {
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validatePath(SPILL_ROOT + "/conv/call_2.txt"));
        }

        @Test
        @DisplayName("findPathBoundaryViolation: spill path reports no violation")
        void findPathBoundaryViolationSpill_null() {
            org.junit.jupiter.api.Assertions.assertNull(
                    WorkspacePathGuard.findPathBoundaryViolation(
                            SPILL_ROOT + "/conv/call_2.txt", WORKSPACE));
        }

        @Test
        @DisplayName("Shell: cat-ing a spilled tool result outside the workspace is allowed")
        void shellSpill_pass() {
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("cat " + SPILL_ROOT + "/conv/call_2.txt"));
        }

        @Test
        @DisplayName("A non-spill path outside the workspace is still blocked")
        void unrelatedOutside_stillBlocked() {
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validatePath("/etc/passwd"));
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
        }

        @Test
        @DisplayName("Deleting inside the trusted spill root is not a root-deletion escape")
        void deleteInsideSpillRoot_pass() {
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("rm -rf " + SPILL_ROOT + "/conv"));
        }
    }

    // ==================== Defect 2: workspace-root deletion guard ====================

    @Nested
    @DisplayName("Deleting the workspace root itself is refused")
    class RootDeletion {

        @BeforeEach
        void setup() {
            ToolExecutionContext.set("conv", "user", WORKSPACE);
        }

        @Test
        @DisplayName("rm -rf <root> (absolute) → rejected")
        void rmRootAbsolute_blocked() {
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("rm -rf " + WORKSPACE));
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("rm -rf " + WORKSPACE + "/"));
        }

        @Test
        @DisplayName("rmdir <root> → rejected")
        void rmdirRoot_blocked() {
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("rmdir " + WORKSPACE));
        }

        @Test
        @DisplayName("rm -rf . and rm -rf ./ (cwd is root) → rejected")
        void rmDotInRoot_blocked() {
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("rm -rf ."));
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("rm -rf ./"));
        }

        @Test
        @DisplayName("rm -rf foo/.. (resolves to root) → rejected")
        void rmTraversalToRoot_blocked() {
            assertThrows(IllegalArgumentException.class, () ->
                    WorkspacePathGuard.validateShellCommand("rm -rf foo/.."));
        }

        @Test
        @DisplayName("Deleting a path INSIDE the root still passes the boundary check")
        void rmInsideRoot_pass() {
            // Destructive but in-bounds — the approval layer, not the path guard,
            // gates this. The guard must not over-block legitimate cleanup.
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("rm -rf subdir"));
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("rm -rf " + WORKSPACE + "/subdir"));
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("rm -rf ./build/output"));
        }

        @Test
        @DisplayName("Non-destructive references to the root are still allowed")
        void nonDestructiveRoot_pass() {
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("ls " + WORKSPACE));
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("cd " + WORKSPACE + " && ls"));
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("ls foo/.."));
            assertDoesNotThrow(() ->
                    WorkspacePathGuard.validateShellCommand("find . -name '*.md'"));
        }
    }
}
