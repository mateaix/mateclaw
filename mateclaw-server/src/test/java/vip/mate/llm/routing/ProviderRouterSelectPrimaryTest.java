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
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * Bind a single skill that declares the given {@code requires-model}
     * tokens, so {@code aggregateModelNeeds} resolves a non-empty capability
     * set and the capability-gated Pass 1 of {@code selectPrimary} runs.
     */
    private void bindSkillRequiring(String... needs) {
        when(bindingService.getBoundSkillIds(AGENT_ID)).thenReturn(Set.of(1L));
        SkillManifest manifest = mock(SkillManifest.class);
        when(manifest.getRequiresModel()).thenReturn(List.of(needs));
        ResolvedSkill skill = mock(ResolvedSkill.class);
        when(skill.getId()).thenReturn(1L);
        when(skill.getManifest()).thenReturn(manifest);
        when(skillRuntimeService.resolveAllSkillsStatus()).thenReturn(List.of(skill));
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
    @DisplayName("2. Preferred provider satisfying the required capability wins in pass 1")
    void preferredSatisfyingCapabilityWins() {
        bindSkillRequiring("vision");
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        when(modelConfigService.getDefaultModelByProvider("deepseek"))
                .thenReturn(model("deepseek", "deepseek-vl"));
        when(capabilityService.resolve(eq("deepseek-vl"), any()))
                .thenReturn(EnumSet.of(Modality.VISION));

        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("deepseek", result.getProvider());
        assertEquals("deepseek-vl", result.getModelName());
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

    @Test
    @DisplayName("8. Preferred misses required capability but global satisfies → global wins in pass 1")
    void preferredMissesCapabilityGlobalSatisfies() {
        bindSkillRequiring("vision");
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        when(modelConfigService.getDefaultModelByProvider("deepseek"))
                .thenReturn(model("deepseek", "deepseek-chat"));
        when(capabilityService.resolve(eq("deepseek-chat"), any()))
                .thenReturn(EnumSet.noneOf(Modality.class));

        ModelConfigEntity global = model("openai", "gpt-4o");
        when(capabilityService.resolve(eq("gpt-4o"), any()))
                .thenReturn(EnumSet.of(Modality.VISION));

        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("openai", result.getProvider());
        assertEquals("gpt-4o", result.getModelName());
    }
}
