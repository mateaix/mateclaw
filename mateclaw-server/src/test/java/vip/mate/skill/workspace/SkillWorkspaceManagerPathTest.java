package vip.mate.skill.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SkillWorkspaceManager#resolveConventionPath} and the
 * workspace-scoped layout.
 *
 * <p>Issue #254: non-ASCII (e.g. Chinese) skill names collapsed to underscores in the
 * workspace path, so distinct names resolved to the same directory and overwrote each
 * other. The fix preserves Unicode letters/digits so distinct names map to distinct
 * directories.
 *
 * <p>Workspace isolation: the path is scoped by {@code workspaceId}
 * ({@code {root}/{workspaceId}/{name}}) so two skills with the SAME name in DIFFERENT
 * workspaces no longer share a directory. {@link #migrateLegacyFlatDir} moves a
 * pre-existing flat {@code {root}/{name}} directory into its scoped location.
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
        Path a = m.resolveConventionPath("我的技能", 1L);
        Path b = m.resolveConventionPath("你的技能", 1L);
        assertNotEquals(a, b, "Chinese names must not collapse to the same directory");
        assertTrue(a.getFileName().toString().contains("我的技能"), "Unicode letters must be preserved");
        assertTrue(b.getFileName().toString().contains("你的技能"), "Unicode letters must be preserved");
    }

    @Test
    @DisplayName("ASCII name maps to {root}/{workspaceId}/{name}")
    void asciiNameScopedByWorkspace() {
        SkillWorkspaceManager m = newManager();
        assertEquals(tmp.resolve("1").resolve("my-skill"), m.resolveConventionPath("my-skill", 1L));
    }

    @Test
    @DisplayName("path is deterministic for the same (name, workspaceId)")
    void deterministicForSameName() {
        SkillWorkspaceManager m = newManager();
        assertEquals(m.resolveConventionPath("demo", 1L), m.resolveConventionPath("demo", 1L));
    }

    // ─── Workspace isolation ────────────────────────────────────────────────

    @Test
    @DisplayName("same name in different workspaces resolves to disjoint directories")
    void sameNameDifferentWorkspacesAreDisjoint() {
        SkillWorkspaceManager m = newManager();
        Path ws1 = m.resolveConventionPath("预约会议", 1L);
        Path ws2 = m.resolveConventionPath("预约会议", 2055554700078690305L);
        assertNotEquals(ws1, ws2, "same-named skills in different workspaces must not share a directory");
        assertTrue(ws1.startsWith(tmp.resolve("1")), "workspace-1 skill lives under {root}/1");
        assertTrue(ws2.startsWith(tmp.resolve("2055554700078690305")), "workspace-2 skill lives under {root}/<wsId>");
    }

    @Test
    @DisplayName("null workspaceId falls back to workspace 1 (defensive only)")
    void nullWorkspaceIdFallsBackToOne() {
        SkillWorkspaceManager m = newManager();
        assertEquals(m.resolveConventionPath("demo", 1L), m.resolveConventionPath("demo", null));
    }

    // ─── Legacy layout migration ────────────────────────────────────────────

    @Test
    @DisplayName("migrateLegacyFlatDir moves {root}/{name} to {root}/{wsId}/{name}")
    void migratesLegacyFlatDir() throws IOException {
        SkillWorkspaceManager m = newManager();
        Path legacy = tmp.resolve("demo");
        Files.createDirectories(legacy.resolve("scripts"));
        Files.writeString(legacy.resolve("SKILL.md"), "# demo");
        Files.writeString(legacy.resolve("scripts").resolve("run.py"), "print(1)");

        boolean moved = m.migrateLegacyFlatDir("demo", 7L);

        assertTrue(moved, "a flat dir with SKILL.md should migrate");
        Path scoped = tmp.resolve("7").resolve("demo");
        assertTrue(Files.exists(scoped.resolve("SKILL.md")), "SKILL.md follows to the scoped location");
        assertTrue(Files.exists(scoped.resolve("scripts").resolve("run.py")), "on-disk scripts survive the move");
        assertFalse(Files.exists(legacy), "the legacy flat dir is gone after migration");
    }

    @Test
    @DisplayName("migrateLegacyFlatDir is idempotent and never overwrites an existing scoped dir")
    void migrationIdempotentAndNonDestructive() throws IOException {
        SkillWorkspaceManager m = newManager();
        Path legacy = tmp.resolve("demo");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("SKILL.md"), "# legacy");
        Path scoped = tmp.resolve("7").resolve("demo");
        Files.createDirectories(scoped);
        Files.writeString(scoped.resolve("SKILL.md"), "# already-migrated");

        boolean moved = m.migrateLegacyFlatDir("demo", 7L);

        assertFalse(moved, "must not move when the scoped target already exists");
        assertEquals("# already-migrated", Files.readString(scoped.resolve("SKILL.md")),
                "existing scoped content must never be overwritten");
    }

    @Test
    @DisplayName("migrateLegacyFlatDir skips a directory without SKILL.md (e.g. a scoped root)")
    void migrationSkipsNonSkillDirs() throws IOException {
        SkillWorkspaceManager m = newManager();
        // A workspace-scoped root {root}/1 whose child is a skill — no top-level SKILL.md.
        Path scopedRoot = tmp.resolve("1");
        Files.createDirectories(scopedRoot.resolve("inner").resolve("scripts"));

        boolean moved = m.migrateLegacyFlatDir("1", 1L);

        assertFalse(moved, "a dir without a top-level SKILL.md is not a legacy flat skill workspace");
        assertTrue(Files.exists(scopedRoot.resolve("inner")), "the scoped root is left untouched");
    }
}
