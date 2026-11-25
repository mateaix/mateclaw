package vip.mate.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.model.AgentEntity;
import vip.mate.i18n.I18nService;
import vip.mate.system.service.SystemSettingService;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentGraphBuilderSearchGuidanceTest {
    private AgentGraphBuilder builder;

    @BeforeEach
    void setUp() {
        builder = mock(AgentGraphBuilder.class, CALLS_REAL_METHODS);
        for (var field : AgentGraphBuilder.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                ReflectionTestUtils.setField(builder, field.getName(), mock(field.getType()));
            }
        }
    }

    @Test void mcpOnlyDoesNotRecommendUnselectedWebTools() throws Exception {
        String prompt = prompt(false, Set.of("mcp_catalog_search"));
        assertFalse(prompt.contains("## Web Search Capability"));
        assertTrue(prompt.contains("exact-name"));
        assertTrue(prompt.contains("serverId prefixes"));
    }

    @Test void noToolsHasNoWebGuidance() throws Exception {
        assertFalse(prompt(false, Set.of()).contains("## Web Search Capability"));
    }

    @Test void searchOnlyUsesExactRuntimeName() throws Exception {
        String prompt = prompt(false, Set.of("web_search"));
        assertTrue(prompt.contains("call the `web_search` tool"));
        assertFalse(prompt.contains("`browser_use`"));
        assertFalse(prompt.contains("call the `search` tool"));
    }

    @Test void browserOnlyRecommendsOnlyBrowserInteraction() throws Exception {
        String prompt = prompt(false, Set.of("browser_use"));
        assertTrue(prompt.contains("Call `browser_use` ONLY"));
        assertFalse(prompt.contains("`web_search`"));
        assertFalse(prompt.contains("`search` for the same query"));
    }

    @Test void bothToolsReceivePriorityGuidance() throws Exception {
        String prompt = prompt(false, Set.of("web_search", "browser_use"));
        assertTrue(prompt.contains("call the `web_search` tool"));
        assertTrue(prompt.contains("Call `browser_use` ONLY"));
        assertTrue(prompt.contains("both `browser_use` and `web_search`"));
    }

    @Test void nativeSearchDoesNotClaimEveryReplyContainsSearchResults() throws Exception {
        String prompt = prompt(true, Set.of());
        assertTrue(prompt.contains("model provider"));
        assertFalse(prompt.contains("automatically incorporate live web search results"));
        assertFalse(prompt.contains("already includes real-time search data"));
        assertFalse(prompt.contains("`web_search`"));
        assertFalse(prompt.contains("`browser_use`"));
        assertFalse(prompt.contains("`search` tool"));
    }

    @Test void nativeSearchUsesReturnedResultsBeforeSupplementaryScopedSearch() throws Exception {
        String prompt = prompt(true, Set.of("web_search"));
        assertTrue(prompt.contains("call the `web_search` tool"));
        assertTrue(prompt.contains("When provider results are available and sufficient, use them directly"));
        assertFalse(prompt.contains("`browser_use`"));
        assertFalse(prompt.contains("already includes real-time search data"));
    }

    @Test void toolsDisabledOmitsEnglishToolManualAndLocalizesAboutYou() throws Exception {
        ReflectionTestUtils.setField(builder, "i18nService", i18nFor("zh-CN"));
        AgentEntity entity = new AgentEntity();
        entity.setId(664L);
        entity.setSystemPrompt("Scoped agent identity");
        entity.setToolsDisabled(true);
        entity.setSkillsDisabled(true);

        String prompt = invokeBuildEnhancedPrompt(entity, false, Set.of());
        assertTrue(prompt.contains("Scoped agent identity"));
        assertTrue(prompt.contains("## 关于你"));
        assertTrue(prompt.contains("MateClaw"));
        assertFalse(prompt.contains("## About You"));
        assertFalse(prompt.contains("Workspace Memory Guidelines"));
        assertFalse(prompt.contains("MCP Tool Naming"));
    }

    @Test void aboutYouSupportsEnglishLocaleAndNullFallback() throws Exception {
        ReflectionTestUtils.setField(builder, "i18nService", i18nFor("en"));
        String englishPrompt = prompt(false, Set.of());
        assertTrue(englishPrompt.contains("## About You"));
        assertTrue(englishPrompt.contains("MateClaw"));

        ReflectionTestUtils.setField(builder, "i18nService", null);
        String fallbackPrompt = prompt(false, Set.of());
        assertTrue(fallbackPrompt.contains("## About You"));
        assertTrue(fallbackPrompt.contains("MateClaw"));
    }

    private String prompt(boolean nativeSearch, Set<String> names) throws Exception {
        AgentEntity entity = new AgentEntity();
        entity.setId(664L);
        entity.setSystemPrompt("Scoped agent identity");
        return invokeBuildEnhancedPrompt(entity, nativeSearch, names);
    }

    private String invokeBuildEnhancedPrompt(AgentEntity entity, boolean nativeSearch, Set<String> names) throws Exception {
        Method method = AgentGraphBuilder.class.getDeclaredMethod("buildEnhancedPrompt",
                AgentEntity.class, boolean.class, int.class, Set.class);
        method.setAccessible(true);
        return (String) method.invoke(builder, entity, nativeSearch, 1024, names);
    }

    private static I18nService i18nFor(String language) {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        SystemSettingService settingService = mock(SystemSettingService.class);
        when(settingService.getLanguage()).thenReturn(language);
        return new I18nService(messageSource, settingService);
    }
}
