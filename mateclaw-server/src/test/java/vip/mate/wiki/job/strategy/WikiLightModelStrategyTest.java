package vip.mate.wiki.job.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.system.service.SystemSettingService;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WikiLightModelStrategyTest {

    private SystemSettingService settings;
    private WikiLightModelStrategy strategy;

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingService.class);
        strategy = new WikiLightModelStrategy(new ObjectMapper(), settings);
    }

    private WikiKnowledgeBaseEntity kb(String configContent) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setConfigContent(configContent);
        return kb;
    }

    @Test
    @DisplayName("supports() only the cheap, high-volume steps")
    void supportsOnlyCheapSteps() {
        assertTrue(strategy.supports(WikiJobStep.ROUTE));
        assertTrue(strategy.supports(WikiJobStep.ENRICH));
        assertTrue(strategy.supports(WikiJobStep.SUMMARY));
        assertTrue(strategy.supports(WikiJobStep.ENTITY_EXTRACTION));
        assertFalse(strategy.supports(WikiJobStep.CREATE_PAGE));
        assertFalse(strategy.supports(WikiJobStep.MERGE_PAGE));
    }

    @Test
    @DisplayName("No light model configured anywhere → null (behavior unchanged)")
    void noLightModelConfigured() {
        when(settings.getString(WikiLightModelStrategy.SETTING_KEY, null)).thenReturn(null);
        assertNull(strategy.selectModelId(null, null, WikiJobStep.ROUTE));
        assertNull(strategy.selectModelId(null, kb("{}"), WikiJobStep.SUMMARY));
    }

    @Test
    @DisplayName("System light model applies to cheap steps")
    void systemLightModelApplies() {
        when(settings.getString(WikiLightModelStrategy.SETTING_KEY, null)).thenReturn("777");
        assertEquals(777L, strategy.selectModelId(null, null, WikiJobStep.ENRICH));
        // Strong steps are never routed here even if asked directly.
        assertNull(strategy.selectModelId(null, null, WikiJobStep.CREATE_PAGE));
    }

    @Test
    @DisplayName("Per-KB light model overrides the system light model")
    void perKbOverridesSystem() {
        when(settings.getString(WikiLightModelStrategy.SETTING_KEY, null)).thenReturn("777");
        Long picked = strategy.selectModelId(null, kb("{\"wikiLightModelId\": 555}"), WikiJobStep.SUMMARY);
        assertEquals(555L, picked);
    }

    @Test
    @DisplayName("Invalid system setting is ignored (null, no crash)")
    void invalidSystemSetting() {
        when(settings.getString(WikiLightModelStrategy.SETTING_KEY, null)).thenReturn("not-a-number");
        assertNull(strategy.selectModelId(null, null, WikiJobStep.ROUTE));
    }
}
