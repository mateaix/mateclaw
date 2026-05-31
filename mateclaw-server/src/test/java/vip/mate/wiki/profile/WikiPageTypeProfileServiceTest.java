package vip.mate.wiki.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiPageTypeProfileEntity;
import vip.mate.wiki.repository.WikiPageTypeProfileMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiPageTypeProfileService}: default profile loading,
 * KB profile resolution and fallback behaviour. The default profile is loaded
 * from the real classpath resource; the mapper is mocked.
 */
class WikiPageTypeProfileServiceTest {

    private WikiPageTypeProfileMapper mapper;
    private WikiPageTypeProfileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WikiPageTypeProfileMapper.class);
        service = new WikiPageTypeProfileService(mapper, new ObjectMapper());
        service.loadDefault();
    }

    @Test
    void defaultProfileReproducesBuiltInPageTypes() {
        WikiPageTypeProfile def = service.getDefaultProfile();
        assertTrue(def.hasPageType("concept"));
        assertTrue(def.hasPageType("person"));
        assertTrue(def.hasPageType("process"));
        assertTrue(def.hasPageType("other"));
        assertEquals(10, def.getPageTypes().size());
    }

    @Test
    void noConfiguredProfile_resolvesToDefault() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(service.allowedPageTypes(42L).contains("concept"));
    }

    @Test
    void configuredProfile_isParsedAndVersionStamped() {
        WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
        row.setKbId(42L);
        row.setVersion(5);
        row.setEnabled(1);
        row.setConfigJson("{\"version\":1,\"pageTypes\":{\"episode\":{\"label\":\"Episode\"}}}");
        when(mapper.selectOne(any())).thenReturn(row);

        WikiPageTypeProfile resolved = service.resolveProfile(42L);
        assertTrue(resolved.hasPageType("episode"));
        assertFalse(resolved.hasPageType("concept"));
        assertEquals(5, resolved.getVersion());  // stamped from the row
    }

    @Test
    void unparseableConfig_fallsBackToDefault() {
        WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
        row.setKbId(42L);
        row.setEnabled(1);
        row.setConfigJson("{ not valid json");
        when(mapper.selectOne(any())).thenReturn(row);

        assertTrue(service.resolveProfile(42L).hasPageType("concept"));
    }

    @Test
    void normalizePageType_keepsKnown_downgradesUnknown() {
        when(mapper.selectOne(any())).thenReturn(null); // default profile
        assertEquals("person", service.normalizePageType(1L, "Person"));
        assertEquals("concept", service.normalizePageType(1L, "made-up-type"));
        assertEquals("concept", service.normalizePageType(1L, null));
    }

    @Test
    void describeForPrompt_defaultProfile_listsBuiltInTypes() {
        when(mapper.selectOne(any())).thenReturn(null);
        String fragment = service.describeForPrompt(1L);
        assertTrue(fragment.contains("- concept"), fragment);
        assertTrue(fragment.contains("- person"), fragment);
        assertTrue(fragment.contains("- other"), fragment);
    }

    @Test
    void resolveLayer_defaultsKnownTypesToFact_experienceWhenDeclared() {
        WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
        row.setKbId(1L);
        row.setEnabled(1);
        row.setConfigJson("{\"pageTypes\":{"
                + "\"episode\":{\"layer\":\"fact\"},"
                + "\"concept\":{},"            // known type, no layer → defaults to fact
                + "\"pattern\":{\"layer\":\"experience\"}}}");
        when(mapper.selectOne(any())).thenReturn(row);

        assertEquals("fact", service.resolveLayer(1L, "episode"));
        assertEquals("fact", service.resolveLayer(1L, "concept"));
        assertEquals("experience", service.resolveLayer(1L, "pattern"));
        assertTrue(service.isExperience(1L, "pattern"));
        assertFalse(service.isExperience(1L, "episode"));
        // unknown type → null (caller leaves layer untouched)
        assertNull(service.resolveLayer(1L, "made-up"));
    }

    @Test
    void stageInstructionAndTemplate_areResolvedPerType() {
        WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
        row.setKbId(1L);
        row.setEnabled(1);
        row.setConfigJson("{\"pageTypes\":{\"episode\":{"
                + "\"route\":{\"instructions\":\"仅当有明确日期时路由为 episode\"},"
                + "\"merge\":{\"instructions\":\"保留已审阅分析,追加新证据\"},"
                + "\"create\":{\"instructions\":\"抽取 event_date\"},"
                + "\"template\":{\"markdown\":\"## Summary\\n{{summary}}\\n## 事件\"}}}}");
        when(mapper.selectOne(any())).thenReturn(row);

        assertTrue(service.stageInstruction(1L, "episode", "route").contains("明确日期"));
        assertTrue(service.stageInstruction(1L, "episode", "merge").contains("已审阅"));
        assertTrue(service.stageInstruction(1L, "episode", "create").contains("event_date"));
        assertTrue(service.templateMarkdown(1L, "episode").contains("## Summary"));
        // unknown type / stage → empty, never null
        assertEquals("", service.stageInstruction(1L, "nope", "route"));
        assertEquals("", service.templateMarkdown(1L, "nope"));
    }

    @Test
    void describeForPrompt_customProfile_showsRequiredMetadata() {
        WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
        row.setKbId(1L);
        row.setEnabled(1);
        row.setConfigJson("{\"pageTypes\":{\"episode\":{\"description\":\"a dated event\","
                + "\"schema\":{\"event_date\":{\"type\":\"date\",\"required\":true},"
                + "\"note\":{\"type\":\"string\",\"required\":false}}}}}");
        when(mapper.selectOne(any())).thenReturn(row);

        String fragment = service.describeForPrompt(1L);
        assertTrue(fragment.contains("- episode: a dated event"), fragment);
        assertTrue(fragment.contains("required metadata: event_date"), fragment);
        assertFalse(fragment.contains("note"), fragment);  // optional field not listed as required
    }
}
