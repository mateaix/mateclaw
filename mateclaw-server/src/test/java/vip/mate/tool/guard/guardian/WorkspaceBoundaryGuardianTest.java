package vip.mate.tool.guard.guardian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link WorkspaceBoundaryGuardian} turns a workspace-boundary
 * escape (or a delete of the workspace root) into a hard, un-approvable BLOCK
 * at the policy layer — before the human-approval prompt (issue #313).
 */
@DisabledOnOs(OS.WINDOWS) // POSIX-style absolute paths in these cases
class WorkspaceBoundaryGuardianTest {

    private static final String WORKSPACE = "/tmp/ws-boundary-guardian-test";
    private static final String DEFAULT_ROOT = "/tmp/ws-boundary-default-root";

    private final WorkspaceBoundaryGuardian guardian = new WorkspaceBoundaryGuardian();

    @AfterEach
    void teardown() {
        WorkspacePathGuard.setDefaultRoot(null);
    }

    private ToolInvocationContext shell(String command, String basePath) {
        String args = "{\"command\":\"" + command.replace("\"", "\\\"") + "\"}";
        return ToolInvocationContext.of("execute_shell_command", args, "conv", "agent")
                .withWorkspaceBasePath(basePath);
    }

    private ToolInvocationContext write(String path, String basePath) {
        String args = "{\"filePath\":\"" + path + "\",\"content\":\"x\"}";
        return ToolInvocationContext.of("write_file", args, "conv", "agent")
                .withWorkspaceBasePath(basePath);
    }

    private void assertBlocked(List<GuardFinding> findings) {
        assertFalse(findings.isEmpty(), "expected a boundary finding");
        GuardFinding f = findings.get(0);
        assertEquals(GuardSeverity.CRITICAL, f.severity());
        assertEquals(GuardDecision.BLOCK, f.decision());
        assertEquals("WORKSPACE_BOUNDARY_ESCAPE", f.ruleId());
    }

    // ==================== Shell ====================

    @Test
    @DisplayName("Shell command escaping the workspace → CRITICAL BLOCK finding")
    void shellEscape_blocked() {
        assertBlocked(guardian.evaluate(shell("cat /etc/passwd", WORKSPACE)));
        assertBlocked(guardian.evaluate(shell("ls ..", WORKSPACE)));
    }

    @Test
    @DisplayName("Deleting the workspace root → CRITICAL BLOCK finding")
    void rootDeletion_blocked() {
        assertBlocked(guardian.evaluate(shell("rm -rf " + WORKSPACE, WORKSPACE)));
        assertBlocked(guardian.evaluate(shell("rm -rf .", WORKSPACE)));
    }

    @Test
    @DisplayName("In-bounds shell command → no finding")
    void shellInBounds_pass() {
        assertTrue(guardian.evaluate(shell("ls -la", WORKSPACE)).isEmpty());
        assertTrue(guardian.evaluate(shell("cat " + WORKSPACE + "/foo.txt", WORKSPACE)).isEmpty());
        assertTrue(guardian.evaluate(shell("rm -rf " + WORKSPACE + "/subdir", WORKSPACE)).isEmpty());
    }

    // ==================== File path tools ====================

    @Test
    @DisplayName("write_file outside the workspace → CRITICAL BLOCK finding")
    void writeOutside_blocked() {
        assertBlocked(guardian.evaluate(write("/etc/evil.conf", WORKSPACE)));
    }

    @Test
    @DisplayName("write_file inside the workspace → no finding")
    void writeInside_pass() {
        assertTrue(guardian.evaluate(write(WORKSPACE + "/notes.txt", WORKSPACE)).isEmpty());
    }

    // ==================== Default-root fallback & escape hatch ====================

    @Test
    @DisplayName("No per-workspace base path → falls back to the global default root")
    void defaultRootFallback_blocks() {
        WorkspacePathGuard.setDefaultRoot(DEFAULT_ROOT);
        // basePath null on the context, but the default root still confines.
        assertBlocked(guardian.evaluate(shell("cat /etc/passwd", null)));
        assertTrue(guardian.evaluate(shell("cat " + DEFAULT_ROOT + "/foo.txt", null)).isEmpty());
    }

    @Test
    @DisplayName("No base path and no default root → guardian is a no-op")
    void noBoundary_noop() {
        assertTrue(guardian.evaluate(shell("cat /etc/passwd", null)).isEmpty());
        assertTrue(guardian.evaluate(write("/etc/passwd", null)).isEmpty());
    }

    @Test
    @DisplayName("supports() only fires for shell and file-path tools")
    void supports_scope() {
        assertTrue(guardian.supports(shell("ls", WORKSPACE)));
        assertTrue(guardian.supports(write("a.txt", WORKSPACE)));
        assertFalse(guardian.supports(
                ToolInvocationContext.of("web_search", "{}", "conv", "agent")));
    }
}
