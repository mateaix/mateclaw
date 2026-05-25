package vip.mate.skill.workspace.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strategy + materializer round-trip. Uses {@code test-bundles/sample/}
 * (in {@code src/test/resources}) as a deterministic fixture so the test
 * doesn't depend on whatever real builtin skills happen to ship.
 */
class SkillBundleMaterializerTest {

    private static final String FIXTURE_ROOT = "test-bundles/sample";

    private final SkillBundleMaterializer materializer = new SkillBundleMaterializer();
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Test
    @DisplayName("verbatim mode copies SKILL.md + scripts + references with subdirs preserved")
    void verbatimCopiesEverything(@TempDir Path target) throws IOException {
        SkillBundleSource source = new ClasspathBundleSource(resolver, FIXTURE_ROOT);

        SkillBundleMaterializer.Result result = materializer.materialize(
                source, target, MaterializeOptions.verbatim());

        assertEquals(3, result.copied(), "expected SKILL.md + scripts/run.sh + references/notes.md");
        assertEquals(0, result.skipped());
        assertTrue(Files.exists(target.resolve("SKILL.md")));
        assertTrue(Files.exists(target.resolve("scripts/run.sh")));
        assertTrue(Files.exists(target.resolve("references/notes.md")));
        // Spot-check content survived the InputStream round-trip.
        assertTrue(Files.readString(target.resolve("scripts/run.sh"))
                .contains("hello from sample bundle"));
    }

    @Test
    @DisplayName("templateOverlay mode skips top-level SKILL.md so the wizard's manifest stays authoritative")
    void templateOverlaySkipsSkillMd(@TempDir Path target) throws IOException {
        SkillBundleSource source = new ClasspathBundleSource(resolver, FIXTURE_ROOT);

        // Pretend the wizard already wrote its rendered manifest.
        Files.writeString(target.resolve("SKILL.md"), "RENDERED_BY_WIZARD");

        SkillBundleMaterializer.Result result = materializer.materialize(
                source, target, MaterializeOptions.templateOverlay());

        assertEquals(2, result.copied(), "scripts/run.sh + references/notes.md only");
        assertEquals(1, result.skipped(), "top-level SKILL.md should be skipped");
        assertEquals("RENDERED_BY_WIZARD", Files.readString(target.resolve("SKILL.md")),
                "wizard-owned SKILL.md must not be overwritten");
        assertTrue(Files.exists(target.resolve("scripts/run.sh")));
        assertTrue(Files.exists(target.resolve("references/notes.md")));
    }

    @Test
    @DisplayName("path traversal entries are rejected without writing outside targetDir")
    void pathTraversalGuard(@TempDir Path target) throws IOException {
        SkillBundleSource malicious = new SkillBundleSource() {
            @Override public String origin() { return "test:malicious"; }
            @Override public List<BundleAsset> assets() {
                return List.of(
                        new BundleAsset("../escaped.txt",
                                () -> new ByteArrayInputStream("nope".getBytes())),
                        new BundleAsset("ok.txt",
                                () -> new ByteArrayInputStream("ok".getBytes())));
            }
        };

        SkillBundleMaterializer.Result result = materializer.materialize(
                malicious, target, MaterializeOptions.verbatim());

        assertEquals(1, result.copied(), "only the safe entry should be copied");
        assertEquals(1, result.skipped(), "the .. entry must be skipped");
        assertTrue(Files.exists(target.resolve("ok.txt")));
        assertFalse(Files.exists(target.getParent().resolve("escaped.txt")),
                "traversal target must not exist on disk");
    }

    @Test
    @DisplayName("creates the target directory when it doesn't yet exist")
    void createsTargetDirectory(@TempDir Path tmp) throws IOException {
        Path nested = tmp.resolve("a/b/c");
        assertFalse(Files.exists(nested));

        SkillBundleSource source = new ClasspathBundleSource(resolver, FIXTURE_ROOT);
        SkillBundleMaterializer.Result result = materializer.materialize(
                source, nested, MaterializeOptions.verbatim());

        assertTrue(Files.isDirectory(nested));
        assertEquals(3, result.copied());
    }
}
