package vip.mate.skill.installer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BuiltinSkillSeedService}. Deliberately avoids Mockito
 * so the suite runs on every JDK/OS combination (Windows + JDK 21 + inline
 * byte-buddy self-attach is flaky). The merge / build helpers are exercised
 * directly via reflection with parsed frontmatter; the mapper is never
 * touched, so {@code null} is safe.
 */
class BuiltinSkillSeedServiceTest {

    private BuiltinSkillSeedService service;
    private SkillFrontmatterParser parser;

    @BeforeEach
    void setUp() {
        parser = new SkillFrontmatterParser();
        // Mapper stays null: none of the tests below go through syncBuiltinSkills()
        // — they drive the private buildNew / mergeIntoExisting helpers directly.
        service = new BuiltinSkillSeedService(null, parser, new ObjectMapper());
    }

    @Test
    @DisplayName("New skill: insert with frontmatter values + sensible defaults")
    void insertsNewSkillWithDefaults() throws Exception {
        String md = """
                ---
                name: my_skill
                version: "2.1.0"
                description: "Pretend skill for testing."
                dependencies:
                  tools:
                    - read_file
                ---
                # body
                """;

        SkillEntity built = invokeBuildNew(md);

        assertEquals("my_skill", built.getName());
        assertEquals("2.1.0", built.getVersion());
        assertEquals("Pretend skill for testing.", built.getDescription());
        assertEquals("builtin", built.getSkillType());
        assertEquals(Boolean.TRUE, built.getBuiltin());
        assertEquals(Boolean.TRUE, built.getEnabled());
        assertEquals("MateClaw", built.getAuthor(), "default author");
        assertEquals("🛠️", built.getIcon(), "default icon");
        assertEquals("my_skill", built.getTags(), "default tag = name");
        assertNotNull(built.getSkillContent());
        assertTrue(built.getSkillContent().contains("# body"));
        assertTrue(built.getConfigJson().contains("\"requiredTools\""), "tools deps should land in configJson");
    }

    @Test
    @DisplayName("Existing skill: frontmatter wins for declared fields, DB values preserved otherwise")
    void mergeKeepsDbFieldsWhenFrontmatterSilent() throws Exception {
        SkillEntity existing = new SkillEntity();
        existing.setId(1000000001L);
        existing.setName("cron");
        existing.setDescription("OLD");
        existing.setVersion("1.0.0");
        existing.setIcon("⏰");
        existing.setTags("cron,schedule");
        existing.setAuthor("MateClaw");
        existing.setSkillType("builtin");
        existing.setBuiltin(true);
        existing.setSkillContent("OLD CONTENT");
        existing.setConfigJson("{\"upstream\":\"mateclaw\",\"entryFile\":\"SKILL.md\"}");

        String md = """
                ---
                name: cron
                version: "1.4.0"
                description: "NEW description"
                ---
                # cron body
                """;

        boolean dirty = invokeMerge(existing, md);

        assertTrue(dirty);
        assertEquals("1.4.0", existing.getVersion(), "version updated from frontmatter");
        assertEquals("NEW description", existing.getDescription(), "description updated");
        // Frontmatter omitted these — DB values preserved:
        assertEquals("⏰", existing.getIcon(), "icon preserved when frontmatter silent");
        assertEquals("cron,schedule", existing.getTags(), "tags preserved when frontmatter silent");
        assertEquals("MateClaw", existing.getAuthor(), "author preserved when frontmatter silent");
        // skill_content always re-syncs from bundled SKILL.md:
        assertTrue(existing.getSkillContent().contains("# cron body"));
    }

    @Test
    @DisplayName("Existing skill: idempotent — second pass with identical frontmatter is a no-op")
    void mergeIsIdempotent() throws Exception {
        SkillEntity existing = new SkillEntity();
        existing.setName("cron");
        existing.setVersion("1.4.0");
        existing.setDescription("Same desc.");
        existing.setSkillType("builtin");
        existing.setBuiltin(true);
        existing.setIcon("⏰");
        existing.setTags("cron");
        existing.setAuthor("MateClaw");
        // The configJson the service produces for this frontmatter (no tools, no platforms)
        existing.setConfigJson("{\"upstream\":\"mateclaw\",\"entryFile\":\"SKILL.md\"}");
        String md = """
                ---
                name: cron
                version: "1.4.0"
                description: "Same desc."
                ---
                # body
                """;
        existing.setSkillContent(md);

        assertFalse(invokeMerge(existing, md), "no fields should change on second pass");
    }

    @Test
    @DisplayName("Frontmatter tags as YAML list serialize to CSV")
    void tagsListSerializesToCsv() throws Exception {
        String md = """
                ---
                name: my_skill
                tags:
                  - alpha
                  - beta
                  - gamma
                ---
                """;
        SkillEntity built = invokeBuildNew(md);
        assertEquals("alpha,beta,gamma", built.getTags());
    }

    @Test
    @DisplayName("Frontmatter `optional: true` seeds the row as enabled=false")
    void optionalFrontmatterSeedsAsDisabled() throws Exception {
        String md = """
                ---
                name: heavy_skill
                description: "Needs paid API + manual OAuth — ship dark."
                optional: true
                ---
                # body
                """;

        SkillEntity built = invokeBuildNew(md);

        assertEquals("heavy_skill", built.getName());
        assertEquals(Boolean.TRUE, built.getBuiltin(), "still a builtin row");
        assertEquals(Boolean.FALSE, built.getEnabled(),
                "optional: true must flip the initial enabled to false");
    }

    @Test
    @DisplayName("Frontmatter absent / false defaults to enabled=true (back-compat)")
    void defaultRemainsEnabled() throws Exception {
        // Frontmatter doesn't mention `optional` → current behavior preserved.
        SkillEntity defaultCase = invokeBuildNew("""
                ---
                name: lightweight_skill
                ---
                # body
                """);
        assertEquals(Boolean.TRUE, defaultCase.getEnabled());

        // Explicit `optional: false` is equivalent.
        SkillEntity explicitFalse = invokeBuildNew("""
                ---
                name: lightweight_too
                optional: false
                ---
                # body
                """);
        assertEquals(Boolean.TRUE, explicitFalse.getEnabled());
    }

    @Test
    @DisplayName("mergeIntoExisting leaves `enabled` alone so user toggles aren't clobbered by frontmatter")
    void mergeNeverFlipsEnabled() throws Exception {
        // User installed an optional skill (enabled=false at seed time), then
        // turned it on from the UI. Subsequent boots must not silently turn
        // it back off just because the frontmatter still says optional: true.
        SkillEntity existing = new SkillEntity();
        existing.setName("heavy_skill");
        existing.setDescription("Needs paid API + manual OAuth — ship dark.");
        existing.setSkillType("builtin");
        existing.setBuiltin(true);
        existing.setIcon("🛠️");
        existing.setTags("heavy_skill");
        existing.setAuthor("MateClaw");
        existing.setEnabled(true); // user activated it
        existing.setConfigJson("{\"upstream\":\"mateclaw\",\"entryFile\":\"SKILL.md\"}");
        String md = """
                ---
                name: heavy_skill
                description: "Needs paid API + manual OAuth — ship dark."
                optional: true
                ---
                # body
                """;
        existing.setSkillContent(md);

        invokeMerge(existing, md);
        assertEquals(Boolean.TRUE, existing.getEnabled(),
                "merge must never override a user-toggled enabled flag");
    }

    @Test
    @DisplayName("Frontmatter without `name` is skipped — never inserts a nameless row")
    void skippedWhenNameMissing() {
        // Empty frontmatter and a namespace clash both produce an empty `name`.
        SkillFrontmatterParser.ParsedSkillMd empty = parser.parse("# only body, no frontmatter");
        assertEquals("", empty.getName());
        // Nothing to assert against the mock — buildNew shouldn't be called when
        // the orchestrator sees an empty name. We're just locking the contract
        // that getName() returns "" for malformed input so the orchestrator's
        // guard works.
    }

    // ==================== reflection helpers ====================
    // These two private methods are the load-bearing logic; we test them
    // directly to keep the suite fast (no DB) and focused.

    private SkillEntity invokeBuildNew(String md) throws Exception {
        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse(md);
        Method m = BuiltinSkillSeedService.class.getDeclaredMethod(
                "buildNew", SkillFrontmatterParser.ParsedSkillMd.class, String.class);
        m.setAccessible(true);
        return (SkillEntity) m.invoke(service, parsed, md);
    }

    private boolean invokeMerge(SkillEntity existing, String md) throws Exception {
        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse(md);
        Method m = BuiltinSkillSeedService.class.getDeclaredMethod(
                "mergeIntoExisting", SkillEntity.class,
                SkillFrontmatterParser.ParsedSkillMd.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(service, existing, parsed, md);
    }
}
