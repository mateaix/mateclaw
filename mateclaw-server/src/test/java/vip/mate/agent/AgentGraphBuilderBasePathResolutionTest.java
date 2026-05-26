package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the agent-vs-workspace basePath precedence rules used by
 * {@link AgentGraphBuilder#resolveAgentBasePath(String, String)}.
 *
 * <p>The UI advertises agent-level paths as "relative to the workspace root",
 * so a relative agent override must compose with the workspace basePath rather
 * than fall through to the JVM working directory.
 */
class AgentGraphBuilderBasePathResolutionTest {

    @Test
    @DisplayName("Both null/blank → null (no working directory configured)")
    void noOverrideNoWorkspace_returnsNull() {
        assertNull(AgentGraphBuilder.resolveAgentBasePath(null, null));
        assertNull(AgentGraphBuilder.resolveAgentBasePath("", ""));
        assertNull(AgentGraphBuilder.resolveAgentBasePath("  ", null));
    }

    @Test
    @DisplayName("No agent override → workspace basePath inherited verbatim")
    void noOverride_inheritsWorkspace() {
        assertEquals("/srv/ws-root",
                AgentGraphBuilder.resolveAgentBasePath(null, "/srv/ws-root"));
        assertEquals("/srv/ws-root",
                AgentGraphBuilder.resolveAgentBasePath("", "/srv/ws-root"));
    }

    @Test
    @DisplayName("Agent override is absolute and inside workspace → used verbatim")
    @DisabledOnOs(OS.WINDOWS)
    void absoluteOverride_insideWs_usedAsIs_unix() {
        assertEquals("/srv/ws-root/agents/code-review",
                AgentGraphBuilder.resolveAgentBasePath("/srv/ws-root/agents/code-review", "/srv/ws-root"));
        assertEquals("/opt/agents/code-review",
                AgentGraphBuilder.resolveAgentBasePath("/opt/agents/code-review", null));
    }

    @Test
    @DisplayName("Agent override is absolute (Windows) and inside workspace → used verbatim")
    @EnabledOnOs(OS.WINDOWS)
    void absoluteOverride_insideWs_usedAsIs_windows() {
        assertEquals("C:\\ws-root\\agents\\code-review",
                AgentGraphBuilder.resolveAgentBasePath("C:\\ws-root\\agents\\code-review", "C:\\ws-root"));
    }

    @Test
    @DisplayName("Relative agent override + workspace basePath → resolved under workspace")
    void relativeOverride_resolvedUnderWorkspace() {
        String expected = Paths.get("/srv/ws-root").resolve("projects/code-review").toString();
        assertEquals(expected,
                AgentGraphBuilder.resolveAgentBasePath("projects/code-review", "/srv/ws-root"));
    }

    @Test
    @DisplayName("Relative agent override with no workspace → used verbatim (legacy fallback)")
    void relativeOverride_noWorkspace_usedAsIs() {
        assertEquals("projects/code-review",
                AgentGraphBuilder.resolveAgentBasePath("projects/code-review", null));
        assertEquals("projects/code-review",
                AgentGraphBuilder.resolveAgentBasePath("projects/code-review", ""));
    }

    @Test
    @DisplayName("Blank workspace basePath treated like null when override is relative")
    void relativeOverride_blankWorkspace_usedAsIs() {
        assertEquals("agent-dir",
                AgentGraphBuilder.resolveAgentBasePath("agent-dir", "   "));
    }

    // ==================== Absolute override scoped to workspace root ====================

    @Test
    @DisplayName("Absolute override inside workspace root → allowed verbatim")
    @DisabledOnOs(OS.WINDOWS)
    void absoluteOverride_insideWorkspace_allowed() {
        // /srv/ws-root/agents/code-review starts with /srv/ws-root → fine.
        assertEquals("/srv/ws-root/agents/code-review",
                AgentGraphBuilder.resolveAgentBasePath("/srv/ws-root/agents/code-review", "/srv/ws-root"));
        // Identical to workspace root → trivially allowed.
        assertEquals("/srv/ws-root",
                AgentGraphBuilder.resolveAgentBasePath("/srv/ws-root", "/srv/ws-root"));
    }

    @Test
    @DisplayName("Absolute override outside workspace root → rejected (workspace-scoping bypass)")
    @DisabledOnOs(OS.WINDOWS)
    void absoluteOverride_outsideWorkspace_rejected() {
        // A less-trusted admin could set / or another repo and bypass scoping —
        // reject it so the override stays inside the team workspace.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                AgentGraphBuilder.resolveAgentBasePath("/etc", "/srv/ws-root"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                AgentGraphBuilder.resolveAgentBasePath("/", "/srv/ws-root"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                AgentGraphBuilder.resolveAgentBasePath("/Users/admin/other-project",
                        "/srv/ws-root"));
    }

    @Test
    @DisplayName("Absolute override with no workspace basePath → used verbatim (legacy)")
    @DisabledOnOs(OS.WINDOWS)
    void absoluteOverride_noWorkspace_allowed() {
        // No workspace boundary to enforce — fall back to legacy behavior.
        assertEquals("/Users/admin/scratch",
                AgentGraphBuilder.resolveAgentBasePath("/Users/admin/scratch", null));
        assertEquals("/Users/admin/scratch",
                AgentGraphBuilder.resolveAgentBasePath("/Users/admin/scratch", ""));
    }
}
