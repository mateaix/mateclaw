package vip.mate.skill.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for {@link SkillWorkspaceManager#resolveConventionPath}.
 *
 * <p>Issue #254: non-ASCII (e.g. Chinese) skill names collapsed to underscores in the
 * workspace path, so distinct names resolved to the same directory and overwrote each
 * other. The fix preserves Unicode letters/digits so distinct names map to distinct
 * directories. The path is the bare sanitized name (no {@code -hash} suffix), so ASCII
 * workspace paths stay stable across upgrades.
 */
class SkillWorkspaceManagerPathTest {

    @TempDir
    Path tmp;

    private SkillWorkspaceManager newManager() {
        SkillWorkspaceProperties props = new SkillWorkspaceProperties();
        props.setRoot(tmp.toString());
        return new SkillWorkspaceManager(props, mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("distinct non-ASCII names resolve to distinct directories (no collision)")
    void nonAsciiNamesDoNotCollide() {
        SkillWorkspaceManager m = newManager();
        Path a = m.resolveConventionPath("我的技能");
        Path b = m.resolveConventionPath("你的技能");
        assertNotEquals(a, b, "Chinese names must not collapse to the same directory");
        assertTrue(a.getFileName().toString().contains("我的技能"), "Unicode letters must be preserved");
        assertTrue(b.getFileName().toString().contains("你的技能"), "Unicode letters must be preserved");
    }

    @Test
    @DisplayName("ASCII name maps to the bare sanitized name with no -hash suffix")
    void asciiNameKeepsBarePath() {
        SkillWorkspaceManager m = newManager();
        assertEquals(tmp.resolve("my-skill"), m.resolveConventionPath("my-skill"));
    }

    @Test
    @DisplayName("path is deterministic for the same name")
    void deterministicForSameName() {
        SkillWorkspaceManager m = newManager();
        assertEquals(m.resolveConventionPath("demo"), m.resolveConventionPath("demo"));
    }
}
