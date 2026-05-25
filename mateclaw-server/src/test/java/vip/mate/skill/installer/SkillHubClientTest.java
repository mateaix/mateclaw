package vip.mate.skill.installer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.installer.model.HubSkillInfo;
import vip.mate.skill.installer.model.SkillBundle;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the ClawHub schema mismatch reported in GitHub issue #42.
 * <p>
 * The hub's actual JSON shape uses {@code displayName} / {@code summary}
 * and nests skill metadata under a {@code skill} key, with the SKILL.md
 * content delivered separately as a ZIP via {@code /api/v1/download}. The
 * earlier client expected a flat {@code {name, description, content}} JSON,
 * which made search results render blank and every install fail with
 * "empty content; treat as failure". These tests pin the parsing.
 */
class SkillHubClientTest {

    private static SkillHubClient newClient() {
        SkillHubProperties props = new SkillHubProperties();
        return new SkillHubClient(props, new ObjectMapper(), new SkillFrontmatterParser());
    }

    @Test
    @DisplayName("Search: clawhub.ai response shape — displayName→name, summary→description")
    void searchMapsHubFieldsToHubSkillInfo() throws Exception {
        // Verbatim shape from https://clawhub.ai/api/v1/search?q=feishu-room-booking
        String body = """
                {
                  "results": [
                    {
                      "score": 2.87,
                      "slug": "feishu-room-booking",
                      "displayName": "Feishu Room Booking",
                      "summary": "Book meeting rooms on Feishu/Lark.",
                      "version": null,
                      "updatedAt": 1777359717617
                    }
                  ]
                }
                """;

        @SuppressWarnings("unchecked")
        List<HubSkillInfo> parsed = (List<HubSkillInfo>) invokePrivate(
                newClient(), "parseSearchResponse", new Class<?>[]{String.class}, body);

        assertEquals(1, parsed.size());
        HubSkillInfo info = parsed.get(0);
        assertEquals("feishu-room-booking", info.getSlug());
        assertEquals("Feishu Room Booking", info.getName(),
                "displayName must populate name (was blank in the bug report)");
        assertEquals("Book meeting rooms on Feishu/Lark.", info.getDescription(),
                "summary must populate description");
    }

    @Test
    @DisplayName("Search: legacy flat shape with name/description still works")
    void searchAcceptsLegacyShape() throws Exception {
        String body = """
                {
                  "results": [
                    {
                      "slug": "x",
                      "name": "Legacy Name",
                      "description": "Legacy description"
                    }
                  ]
                }
                """;
        @SuppressWarnings("unchecked")
        List<HubSkillInfo> parsed = (List<HubSkillInfo>) invokePrivate(
                newClient(), "parseSearchResponse", new Class<?>[]{String.class}, body);
        assertEquals(1, parsed.size());
        assertEquals("Legacy Name", parsed.get(0).getName());
        assertEquals("Legacy description", parsed.get(0).getDescription());
    }

    @Test
    @DisplayName("Metadata: nested {skill, latestVersion, owner} shape extracts all fields")
    void metadataExtractsNestedFields() throws Exception {
        // Verbatim shape from https://clawhub.ai/api/v1/skills/feishu-room-booking
        String body = """
                {
                  "skill": {
                    "slug": "feishu-room-booking",
                    "displayName": "Feishu Room Booking",
                    "summary": "Book meeting rooms on Feishu."
                  },
                  "latestVersion": {
                    "version": "2.9.0",
                    "license": "MIT-0"
                  },
                  "owner": {
                    "handle": "qiushibang",
                    "displayName": "qiushibang"
                  }
                }
                """;

        Object metadata = invokePrivate(newClient(), "parseMetadataResponse", new Class<?>[]{String.class}, body);
        assertNotNull(metadata, "Nested metadata must parse successfully");

        // Use reflection on the record to verify all four fields land.
        assertEquals("Feishu Room Booking", recordField(metadata, "displayName"));
        assertEquals("Book meeting rooms on Feishu.", recordField(metadata, "summary"));
        assertEquals("2.9.0", recordField(metadata, "version"));
        assertEquals("qiushibang", recordField(metadata, "owner"));
    }

    @Test
    @DisplayName("Bundle ZIP extraction: SKILL.md frontmatter wins, references/scripts have no prefix in keys")
    void zipExtractStoresKeysWithoutPrefix() throws Exception {
        byte[] zip = buildZipBundle();
        ZipSkillFetcher.ExtractedSkill extracted = ZipSkillFetcher.extract(new java.io.ByteArrayInputStream(zip));

        assertTrue(extracted.skillMdContent().contains("name: feishu-room-booking"));
        // Keys must be relative to references/ and scripts/ — installers prepend the prefix themselves.
        assertTrue(extracted.references().containsKey("rooms.json"),
                "expected 'rooms.json' (no 'references/' prefix), got: " + extracted.references().keySet());
        assertTrue(extracted.scripts().containsKey("query.py"),
                "expected 'query.py' (no 'scripts/' prefix), got: " + extracted.scripts().keySet());
        assertEquals("{\"a\":1}", extracted.references().get("rooms.json"));
        assertEquals("print('hi')\n", extracted.scripts().get("query.py"));
    }

    @Test
    @DisplayName("Bundle ZIP missing SKILL.md throws IllegalArgumentException")
    void zipExtractRequiresSkillMd() throws Exception {
        byte[] zip;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("scripts/query.py"));
            zos.write("print('hi')\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            zip = out.toByteArray();
        }
        assertThrows(IllegalArgumentException.class,
                () -> ZipSkillFetcher.extract(new java.io.ByteArrayInputStream(zip)));
    }

    // ==================== helpers ====================

    private static byte[] buildZipBundle() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(out)) {
            String md = """
                    ---
                    name: feishu-room-booking
                    description: Book meeting rooms.
                    version: "2.9.0"
                    ---
                    body
                    """;
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(md.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("references/rooms.json"));
            zos.write("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("scripts/query.py"));
            zos.write("print('hi')\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.finish();
            return out.toByteArray();
        }
    }

    /** Round-trip a SkillBundle assembly purely via the data we'd get from the hub. */
    @Test
    @DisplayName("End-to-end shape: bundle assembled from ZIP + metadata has non-empty content")
    void assembledBundleHasNonEmptyContent() throws Exception {
        byte[] zip = buildZipBundle();
        ZipSkillFetcher.ExtractedSkill extracted = ZipSkillFetcher.extract(new java.io.ByteArrayInputStream(zip));
        SkillFrontmatterParser parser = new SkillFrontmatterParser();
        var parsed = parser.parse(extracted.skillMdContent());

        SkillBundle bundle = new SkillBundle(
                parsed.getName(),
                extracted.skillMdContent(),
                extracted.references(),
                extracted.scripts(),
                "clawhub",
                "https://clawhub.ai/skills/feishu-room-booking@2.9.0",
                "2.9.0",
                parsed.getDescription(),
                "qiushibang",
                "📦"
        );

        // The original bug rejected bundles with bundle.content().isBlank().
        assertNotNull(bundle.content());
        assertFalse(bundle.content().isBlank(), "content must be non-empty so installer doesn't reject as failure");
        assertEquals("feishu-room-booking", bundle.name());
        assertEquals("2.9.0", bundle.version());
    }

    private static Object invokePrivate(Object target, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Object recordField(Object record, String fieldName) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(fieldName);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }
}
