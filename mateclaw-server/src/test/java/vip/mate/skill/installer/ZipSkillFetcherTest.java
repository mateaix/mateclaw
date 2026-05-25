package vip.mate.skill.installer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link ZipSkillFetcher#extract}.
 *
 * <p>The original single-pass extractor depended on SKILL.md being seen
 * before any {@code scripts/} or {@code references/} entry, so packaging
 * tools that emitted entries in a different order silently dropped scripts.
 * Issue #104 hit this with {@code tencent-meeting-mcp.zip}: the zip's
 * scripts streamed first and were never persisted, leaving the installed
 * skill unable to run. The two-pass extractor must classify entries
 * regardless of order.
 */
class ZipSkillFetcherTest {

    private static final String SKILL_MD = """
            ---
            name: tencent-meeting
            description: Test
            version: 1.0.0
            ---
            # Test skill
            """;

    private record Entry(String name, String content) {}

    private static byte[] zipOf(List<Entry> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(e.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("scripts emitted BEFORE SKILL.md (issue #104) are still classified")
    void extractsScriptsEvenWhenTheyComeBeforeSkillMd() throws IOException {
        byte[] zip = zipOf(List.of(
                new Entry("tencent-meeting-mcp/scripts/run.py", "print('hi')\n"),
                new Entry("tencent-meeting-mcp/scripts/helper.py", "x = 1\n"),
                new Entry("tencent-meeting-mcp/references/notes.md", "# notes\n"),
                new Entry("tencent-meeting-mcp/SKILL.md", SKILL_MD)
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertNotNull(ex.skillMdContent());
        assertEquals(2, ex.scripts().size(),
                "Both scripts must survive even though they preceded SKILL.md");
        assertEquals("print('hi')\n", ex.scripts().get("run.py"));
        assertEquals("x = 1\n", ex.scripts().get("helper.py"));
        assertEquals(1, ex.references().size());
        assertEquals("# notes\n", ex.references().get("notes.md"));
    }

    @Test
    @DisplayName("scripts emitted AFTER SKILL.md still work (no regression)")
    void extractsScriptsWhenSkillMdComesFirst() throws IOException {
        byte[] zip = zipOf(List.of(
                new Entry("pkg/SKILL.md", SKILL_MD),
                new Entry("pkg/scripts/run.py", "print('after')\n"),
                new Entry("pkg/references/cfg.md", "cfg\n")
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertEquals(1, ex.scripts().size());
        assertEquals("print('after')\n", ex.scripts().get("run.py"));
        assertEquals(1, ex.references().size());
    }

    @Test
    @DisplayName("SKILL.md at zip root: scripts in same root level still classify correctly")
    void extractsWhenSkillMdAtRoot() throws IOException {
        byte[] zip = zipOf(List.of(
                new Entry("scripts/a.py", "a"),
                new Entry("scripts/sub/b.py", "b"),
                new Entry("references/r.md", "r"),
                new Entry("SKILL.md", SKILL_MD)
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertEquals(2, ex.scripts().size());
        assertEquals("a", ex.scripts().get("a.py"));
        assertEquals("b", ex.scripts().get("sub/b.py"));
        assertEquals(1, ex.references().size());
    }

    @Test
    @DisplayName("Missing SKILL.md still throws")
    void rejectsZipWithoutSkillMd() throws IOException {
        byte[] zip = zipOf(List.of(new Entry("scripts/run.py", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> ZipSkillFetcher.extract(new ByteArrayInputStream(zip)));
    }

    @Test
    @DisplayName("Nested entries outside scripts/ and references/ are dropped (no extension fallback)")
    void ignoresNestedNoiseEntries() throws IOException {
        // README inside the wrapper dir is unclear (could be docs vs install
        // instructions) — strict mode wins here. Only root-level files get
        // the extension fallback.
        byte[] zip = zipOf(List.of(
                new Entry("pkg/SKILL.md", SKILL_MD),
                new Entry("pkg/docs/extra.md", "ignored"),
                new Entry("pkg/scripts/run.py", "x"),
                new Entry("pkg/.git/HEAD", "ref: refs/heads/main")
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertEquals(Map.of("run.py", "x"), ex.scripts());
        assertTrue(ex.references().isEmpty());
    }

    @Test
    @DisplayName("Real-world tencent layout: setup.sh at zip root → classified as script")
    void rootLevelSetupShIsClassifiedAsScript() throws IOException {
        // Verbatim shape of the official tencent-meeting-mcp.zip:
        //   setup.sh
        //   references/api_references.md
        //   SKILL.md
        // setup.sh sits at the zip root, not under scripts/. Without the
        // extension fallback the skill installs with an empty scripts/
        // and SKILL.md's `bash setup.sh` instruction goes nowhere.
        byte[] zip = zipOf(List.of(
                new Entry("setup.sh", "#!/bin/bash\necho hello\n"),
                new Entry("references/api_references.md", "# api docs"),
                new Entry("SKILL.md", SKILL_MD)
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertEquals(1, ex.scripts().size(),
                "setup.sh at zip root should land in scripts via extension fallback");
        assertEquals("#!/bin/bash\necho hello\n", ex.scripts().get("setup.sh"));
        assertEquals(1, ex.references().size());
        assertEquals("# api docs", ex.references().get("api_references.md"));
    }

    @Test
    @DisplayName("Root-level README.md is auto-classified into references/")
    void rootLevelMarkdownGoesToReferences() throws IOException {
        byte[] zip = zipOf(List.of(
                new Entry("SKILL.md", SKILL_MD),
                new Entry("README.md", "# top-level readme"),
                new Entry("config.yaml", "key: value\n")
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertEquals(2, ex.references().size());
        assertEquals("# top-level readme", ex.references().get("README.md"));
        assertEquals("key: value\n", ex.references().get("config.yaml"));
        assertTrue(ex.scripts().isEmpty());
    }

    @Test
    @DisplayName("Root-level file with unknown extension is still dropped (with WARN)")
    void rootLevelUnknownExtensionStillDropped() throws IOException {
        byte[] zip = zipOf(List.of(
                new Entry("SKILL.md", SKILL_MD),
                new Entry("mystery.bin", "binary blob")
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertTrue(ex.scripts().isEmpty());
        assertTrue(ex.references().isEmpty());
    }

    @Test
    @DisplayName("Root-level fallback also works when SKILL.md is in a wrapper dir")
    void rootLevelFallbackWorksAfterPrefixStrip() throws IOException {
        // pkg/setup.sh becomes "setup.sh" after prefix strip, so the same
        // fallback rules apply — packagers shouldn't have to choose between
        // "wrap everything" and "use a sub-script-dir".
        byte[] zip = zipOf(List.of(
                new Entry("pkg/setup.sh", "#!/bin/sh\n"),
                new Entry("pkg/SKILL.md", SKILL_MD)
        ));

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(new ByteArrayInputStream(zip));

        assertEquals(1, ex.scripts().size());
        assertEquals("#!/bin/sh\n", ex.scripts().get("setup.sh"));
    }

    @Test
    @DisplayName("GBK-encoded entry names (Windows-authored zip) fall back from UTF-8 to GBK")
    void extractsGbkEncodedNames() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.charset.Charset.isSupported("GBK"), "GBK charset not available on this JVM");
        java.nio.charset.Charset gbk = java.nio.charset.Charset.forName("GBK");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, gbk)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(SKILL_MD.getBytes(gbk));
            zos.closeEntry();
            // Chinese filename whose GBK bytes are invalid UTF-8 → forces the fallback.
            zos.putNextEntry(new ZipEntry("references/中文说明.md"));
            zos.write("# 中文内容\n".getBytes(gbk));
            zos.closeEntry();
        }

        ZipSkillFetcher.ExtractedSkill ex = ZipSkillFetcher.extract(baos.toByteArray());

        assertNotNull(ex.skillMdContent());
        assertEquals(1, ex.references().size(), "GBK-named reference should survive the charset fallback");
        assertEquals("# 中文内容\n", ex.references().get("中文说明.md"));
    }
}
