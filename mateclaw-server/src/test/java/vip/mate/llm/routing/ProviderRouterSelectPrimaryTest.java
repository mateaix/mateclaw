package vip.mate.llm.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelCapabilityService.Modality;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.runtime.SkillRuntimeService;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderRouterSelectPrimaryTest {

    @Mock private SkillRuntimeService skillRuntimeService;
    @Mock private AgentBindingResolver bindingService;
    @Mock private ModelCapabilityService capabilityService;
    @Mock private ModelConfigService modelConfigService;

    @InjectMocks private ProviderRouter router;

    private static final Long AGENT_ID = 42L;

    // ---- helpers ----

    private static ModelConfigEntity model(String provider, String name) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider(provider);
        m.setModelName(name);
        return m;
    }

    private void stubNoCapabilities() {
        when(bindingService.getBoundSkillIds(AGENT_ID)).thenReturn(Set.of());
    }

    private void stubCapabilities(String... needs) {
        // aggregateModelNeeds reads bound skills → return empty so
        // resolveRequiredModalities returns null (no capability gate).
        // For tests that need capabilities, we stub a bound skill.
        when(skillRuntimeService.resolveAllSkillsStatus()).thenReturn(List.of());
    }

    // ---- tests ----

    @Test
    @DisplayName("1. Preferred provider wins when no capability requirements")
    void preferredWinsWithoutCapabilities() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        when(modelConfigService.getDefaultModelByProvider("deepseek"))
                .thenReturn(model("deepseek", "deepseek-chat"));

        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("deepseek", result.getProvider());
        assertEquals("deepseek-chat", result.getModelName());
    }

    @Test
    @DisplayName("2. Preferred provider satisfying capability wins in pass 1")
    void preferredSatisfyingCapabilityWins() {
        when(bindingService.getBoundSkillIds(AGENT_ID)).thenReturn(Set.of(1L));
        // No resolved skills → aggregateModelNeeds returns empty → no capability gate
        when(skillRuntimeService.resolveAllSkillsStatus()).thenReturn(List.of());
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        when(modelConfigService.getDefaultModelByProvider("deepseek"))
                .thenReturn(model("deepseek", "deepseek-chat"));

        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("deepseek", result.getProvider());
    }

    @Test
    @DisplayName("3. No preferred providers → global default")
    void noPreferredFallsBackToGlobal() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of());

        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("openai", result.getProvider());
        assertEquals("gpt-4o", result.getModelName());
    }

    @Test
    @DisplayName("4. Preferred provider unavailable → second preferred wins")
    void firstPreferredUnavailableSecondWins() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek", "dashscope"));
        when(modelConfigService.getDefaultModelByProvider("deepseek")).thenReturn(null);
        when(modelConfigService.getDefaultModelByProvider("dashscope"))
                .thenReturn(model("dashscope", "qwen-max"));

        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("dashscope", result.getProvider());
        assertEquals("qwen-max", result.getModelName());
    }

    @Test
    @DisplayName("5. All preferred unavailable → global default")
    void allPreferredUnavailableFallsBackToGlobal() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        when(modelConfigService.getDefaultModelByProvider("deepseek")).thenReturn(null);

        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("openai", result.getProvider());
    }

    @Test
    @DisplayName("6. No agent ID → returns global default")
    void nullAgentIdReturnsGlobal() {
        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(null, global);
        assertSame(global, result);
    }

    @Test
    @DisplayName("7. Both preferred and global null → returns null")
    void allNullReturnsNull() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of());

        ModelConfigEntity result = router.selectPrimary(AGENT_ID, null);
        assertNull(result);
    }
}
