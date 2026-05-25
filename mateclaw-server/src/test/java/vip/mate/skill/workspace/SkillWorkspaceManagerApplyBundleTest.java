package vip.mate.skill.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for {@link SkillWorkspaceManager#applyBundleFiles}.
 *
 * <p>Issue #104: a malformed ZIP that produced an empty {@code scripts}
 * map used to wipe pre-existing scripts because the installer ran
 * "clean-then-write". Write-then-prune + empty-bundle guard preserves
 * existing files when the new bundle has nothing to say about a bucket.
 */
class SkillWorkspaceManagerApplyBundleTest {

    @TempDir
    Path tmp;

    private SkillWorkspaceManager manager;
    private final String skill = "demo";

    @BeforeEach
    void setUp() {
        SkillWorkspaceProperties props = new SkillWorkspaceProperties();
        props.setRoot(tmp.toString());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        manager = new SkillWorkspaceManager(props, publisher);
        manager.initWorkspace(skill, "---\nname: demo\n---\nbody\n");
    }

    @Test
    @DisplayName("write-then-prune: new files added, removed files pruned")
    void writeThenPruneNormalCase() throws IOException {
        Path scripts = tmp.resolve(skill).resolve("scripts");
        Files.writeString(scripts.resolve("old.py"), "old");
        Files.writeString(scripts.resolve("keep.py"), "v1");

        var result = manager.applyBundleFiles(skill,
                Map.of(),
                Map.of("keep.py", "v2", "new.py", "fresh"),
                false);

        assertEquals(2, result.scriptsWritten());
        assertEquals(1, result.scriptsPruned(), "old.py should be pruned");
        assertFalse(result.scriptsPreservedDueToEmptyBundle());
        assertEquals("v2", Files.readString(scripts.resolve("keep.py")));
        assertEquals("fresh", Files.readString(scripts.resolve("new.py")));
        assertFalse(Files.exists(scripts.resolve("old.py")));
    }

    @Test
    @DisplayName("empty-bundle guard: existing scripts preserved when new bundle has none")
    void emptyBundleGuardPreservesExistingScripts() throws IOException {
        Path scripts = tmp.resolve(skill).resolve("scripts");
        Files.writeString(scripts.resolve("run.py"), "important");
        Files.writeString(scripts.resolve("helper.py"), "more important");

        var result = manager.applyBundleFiles(skill,
                Map.of("notes.md", "ref"),
                Map.of(), // empty scripts — simulates the issue #104 extractor bug
                false);

        assertEquals(0, result.scriptsWritten());
        assertEquals(0, result.scriptsPruned());
        assertTrue(result.scriptsPreservedDueToEmptyBundle(),
                "Empty-bundle guard must mark scripts as preserved");
        assertEquals("important", Files.readString(scripts.resolve("run.py")),
                "Existing script must NOT be wiped by an empty bundle");
        assertEquals("more important", Files.readString(scripts.resolve("helper.py")));
    }

    @Test
    @DisplayName("force=true bypasses empty-bundle guard and prunes everything")
    void forceFlagPrunesEvenWhenBundleEmpty() throws IOException {
        Path scripts = tmp.resolve(skill).resolve("scripts");
        Files.writeString(scripts.resolve("doomed.py"), "x");

        var result = manager.applyBundleFiles(skill,
                Map.of(),
                Map.of(),
                true);

        assertFalse(result.scriptsPreservedDueToEmptyBundle());
        assertEquals(1, result.scriptsPruned());
        assertFalse(Files.exists(scripts.resolve("doomed.py")));
    }

    @Test
    @DisplayName("references and scripts buckets prune independently")
    void bucketsAreIndependent() throws IOException {
        Path scripts = tmp.resolve(skill).resolve("scripts");
        Path references = tmp.resolve(skill).resolve("references");
        Files.writeString(scripts.resolve("run.py"), "stay-on-disk");
        Files.writeString(references.resolve("notes.md"), "stale-ref");

        var result = manager.applyBundleFiles(skill,
                Map.of("notes.md", "fresh-ref"),
                Map.of(), // empty scripts → preserved
                false);

        assertTrue(result.scriptsPreservedDueToEmptyBundle());
        assertFalse(result.referencesPreservedDueToEmptyBundle());
        assertEquals("stay-on-disk", Files.readString(scripts.resolve("run.py")));
        assertEquals("fresh-ref", Files.readString(references.resolve("notes.md")));
    }
}
