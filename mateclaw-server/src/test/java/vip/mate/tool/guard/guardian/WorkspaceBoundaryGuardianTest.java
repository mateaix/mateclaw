package vip.mate.tool.guard.guardian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link WorkspaceBoundaryGuardian} turns a workspace-boundary
 * escape (or a delete of the workspace root) into a hard, un-approvable BLOCK
 * at the policy layer — before the human-approval prompt (issue #313).
 */
@DisabledOnOs(OS.WINDOWS) // POSIX-style absolute paths in these cases
class WorkspaceBoundaryGuardianTest {

    private static final String WORKSPACE = "/tmp/ws-boundary-guardian-test";
    private static final String DEFAULT_ROOT = "/tmp/ws-boundary-default-root";

    private final WorkspaceBoundaryGuardian guardian = new WorkspaceBoundaryGuardian(null);

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

    private ToolInvocationContext read(String path, String basePath) {
        String args = "{\"filePath\":\"" + path + "\"}";
        return ToolInvocationContext.of("read_file", args, "conv", "agent")
                .withWorkspaceBasePath(basePath);
    }

    private ToolInvocationContext code(String language, String src, String basePath) {
        String args = "{\"language\":\"" + language + "\",\"code\":\""
                + src.replace("\"", "\\\"") + "\"}";
        return ToolInvocationContext.of("execute_code", args, "conv", "agent")
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

    // ==================== Inline code execution (execute_code) ====================

    @Test
    @DisplayName("execute_code bash escaping the workspace → CRITICAL BLOCK finding")
    void codeBashEscape_blocked() {
        // The #403 reproduction: `cat /tmp/...` and reading /etc/passwd from
        // shell-language code must be blocked just like the shell tool.
        assertBlocked(guardian.evaluate(code("bash", "cat /etc/passwd", WORKSPACE)));
        assertBlocked(guardian.evaluate(code("sh", "cat /tmp/mate-tool-result-spill/x", WORKSPACE)));
        assertBlocked(guardian.evaluate(code("shell", "ls ..", WORKSPACE)));
    }

    @Test
    @DisplayName("execute_code bash deleting the workspace root → CRITICAL BLOCK finding")
    void codeBashRootDeletion_blocked() {
        assertBlocked(guardian.evaluate(code("bash", "rm -rf " + WORKSPACE, WORKSPACE)));
    }

    @Test
    @DisplayName("execute_code bash inside the workspace → no finding")
    void codeBashInBounds_pass() {
        assertTrue(guardian.evaluate(code("bash", "ls -la", WORKSPACE)).isEmpty());
        assertTrue(guardian.evaluate(code("bash", "cat " + WORKSPACE + "/foo.txt", WORKSPACE)).isEmpty());
    }

    @Test
    @DisplayName("execute_code reports the violating param as 'code'")
    void codeViolation_paramName() {
        List<GuardFinding> findings = guardian.evaluate(code("bash", "cat /etc/passwd", WORKSPACE));
        assertFalse(findings.isEmpty());
        assertEquals("code", findings.get(0).paramName());
    }

    @Test
    @DisplayName("execute_code python/node is not path-scanned (avoids string-literal false positives)")
    void codeNonShell_notScanned() {
        // A Python/Node literal containing an absolute path must NOT be treated
        // as a shell boundary escape — the static shell scan doesn't apply.
        assertTrue(guardian.evaluate(code("python", "open('/etc/passwd')", WORKSPACE)).isEmpty());
        assertTrue(guardian.evaluate(code("node", "fs.readFileSync('/etc/passwd')", WORKSPACE)).isEmpty());
        // Unknown / missing language is likewise not scanned.
        assertTrue(guardian.evaluate(code("ruby", "File.read('/etc/passwd')", WORKSPACE)).isEmpty());
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

    @Test
    @DisplayName("write_file with a relative path resolves against the workspace, not the process CWD (issue #494)")
    void writeRelativePath_pass() {
        // Regression for #494: a plain relative path like "./foo.html" was
        // resolved against the JVM launch directory via toAbsolutePath(), so it
        // fell outside the workspace whenever the server ran from elsewhere and
        // tripped a spurious CRITICAL "工作区越界" block. It must resolve into
        // the workspace and pass.
        assertTrue(guardian.evaluate(write("./fiber-signal-architecture.html", WORKSPACE)).isEmpty());
        assertTrue(guardian.evaluate(write("notes/todo.md", WORKSPACE)).isEmpty());
        assertTrue(guardian.evaluate(write("report.txt", WORKSPACE)).isEmpty());
    }

    @Test
    @DisplayName("write_file with a relative traversal that climbs out is still blocked")
    void writeRelativeTraversal_blocked() {
        // The fix must not weaken the boundary: "../escape.txt" normalizes to a
        // path outside the workspace root and stays blocked.
        assertBlocked(guardian.evaluate(write("../escape.txt", WORKSPACE)));
        assertBlocked(guardian.evaluate(write("../../etc/evil.conf", WORKSPACE)));
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
    @DisplayName("supports() fires for shell, code, and file-path tools")
    void supports_scope() {
        assertTrue(guardian.supports(shell("ls", WORKSPACE)));
        assertTrue(guardian.supports(code("bash", "ls", WORKSPACE)));
        assertTrue(guardian.supports(write("a.txt", WORKSPACE)));
        assertFalse(guardian.supports(
                ToolInvocationContext.of("web_search", "{}", "conv", "agent")));
    }

    // ==================== Chat-upload fallback scope ====================

    @Test
    @DisplayName("A stored chat-upload path outside the workspace is allowed via the DB-backed fallback")
    void chatUpload_storedPath_pass(@TempDir Path uploadRoot) throws Exception {
        Path stored = Files.createDirectories(uploadRoot.resolve("conv"))
                .resolve("1777391026594_secret.txt");
        Files.writeString(stored, "attachment");

        ChatUploadLocationResolver resolver = mock(ChatUploadLocationResolver.class);
        when(resolver.resolveCandidateUploadRoots("conv")).thenReturn(List.of(uploadRoot));
        WorkspaceBoundaryGuardian g = new WorkspaceBoundaryGuardian(resolver);

        // The upload root sits outside the workspace, so the boundary check
        // trips first; the fallback must clear it for the real stored path.
        assertTrue(g.evaluate(read(stored.toString(), WORKSPACE)).isEmpty());
    }

    @Test
    @DisplayName("An outside path merely sharing a basename with an attachment stays blocked")
    void chatUpload_basenameCollision_blocked(@TempDir Path uploadRoot, @TempDir Path elsewhere)
            throws Exception {
        // Store an attachment whose "{millis}_{safeName}" name would basename-match
        // a request for "secret.txt" — the fallback must not let that clear a
        // violation for a path pointing somewhere else entirely.
        Path uploadDir = Files.createDirectories(uploadRoot.resolve("conv"));
        Files.writeString(uploadDir.resolve("1777391026594_secret.txt"), "attachment");
        Path outside = elsewhere.resolve("secret.txt");
        Files.writeString(outside, "not an attachment");

        ChatUploadLocationResolver resolver = mock(ChatUploadLocationResolver.class);
        when(resolver.resolveCandidateUploadRoots("conv")).thenReturn(List.of(uploadRoot));
        WorkspaceBoundaryGuardian g = new WorkspaceBoundaryGuardian(resolver);

        assertBlocked(g.evaluate(read(outside.toString(), WORKSPACE)));
    }

    @Test
    @DisplayName("A path inside a different conversation's upload dir stays blocked")
    void chatUpload_otherConversation_blocked(@TempDir Path uploadRoot) throws Exception {
        Path otherConvFile = Files.createDirectories(uploadRoot.resolve("other-conv"))
                .resolve("1777391026594_secret.txt");
        Files.writeString(otherConvFile, "someone else's attachment");

        ChatUploadLocationResolver resolver = mock(ChatUploadLocationResolver.class);
        when(resolver.resolveCandidateUploadRoots("conv")).thenReturn(List.of(uploadRoot));
        WorkspaceBoundaryGuardian g = new WorkspaceBoundaryGuardian(resolver);

        assertBlocked(g.evaluate(read(otherConvFile.toString(), WORKSPACE)));
    }

    @Test
    @DisplayName("Resolver failure keeps the BLOCK finding (fail closed)")
    void chatUpload_resolverFailure_blocked() {
        ChatUploadLocationResolver resolver = mock(ChatUploadLocationResolver.class);
        when(resolver.resolveCandidateUploadRoots("conv"))
                .thenThrow(new RuntimeException("db down"));
        WorkspaceBoundaryGuardian g = new WorkspaceBoundaryGuardian(resolver);

        assertBlocked(g.evaluate(read("/somewhere/else/file.txt", WORKSPACE)));
    }

    // ==================== Tool-result spill dir stays reachable (issue #403) ====================

    @Test
    @DisplayName("execute_code can still cat a legitimate spilled tool result outside the workspace")
    void codeBashSpill_pass() {
        String spillRoot = "/tmp/mate-tool-result-spill/tool-results";
        WorkspacePathGuard.addTrustedRoot(spillRoot);
        try {
            // The trusted-root mechanism added for #403 must keep working once
            // execute_code is brought under the boundary guard: reading a real
            // spill path is allowed, an unrelated outside path is still blocked.
            assertTrue(guardian.evaluate(
                    code("bash", "cat " + spillRoot + "/conv/call_2.txt", WORKSPACE)).isEmpty());
            assertBlocked(guardian.evaluate(code("bash", "cat /etc/passwd", WORKSPACE)));
        } finally {
            WorkspacePathGuard.clearTrustedRoots();
        }
    }
}
