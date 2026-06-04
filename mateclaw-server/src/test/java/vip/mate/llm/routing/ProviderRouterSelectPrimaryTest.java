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
import vip.mate.llm.service.ModelProviderService;
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
    @Mock private ModelProviderService modelProviderService;

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

    /**
     * Stub a preferred provider as configured (has usable credentials) and
     * resolving to the given primary chat model. Mirrors the runtime path
     * {@code pickProviderDefault} takes: a provider must be configured before
     * its primary chat model is considered.
     */
    private void stubConfiguredProvider(String providerId, ModelConfigEntity primaryModel) {
        when(modelProviderService.isProviderConfigured(providerId)).thenReturn(true);
        when(modelConfigService.getPrimaryChatModelByProvider(providerId)).thenReturn(primaryModel);
    }

    // ---- tests ----

    @Test
    @DisplayName("1. Preferred provider wins when no capability requirements")
    void preferredWinsWithoutCapabilities() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        stubConfiguredProvider("deepseek", model("deepseek", "deepseek-chat"));

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
        stubConfiguredProvider("deepseek", model("deepseek", "deepseek-vl"));
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
    @DisplayName("4. Unconfigured first preferred is skipped → second preferred wins")
    void firstPreferredUnavailableSecondWins() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek", "dashscope"));
        // deepseek has no usable credentials → must be skipped, not selected
        // and then bounced to the global default.
        when(modelProviderService.isProviderConfigured("deepseek")).thenReturn(false);
        stubConfiguredProvider("dashscope", model("dashscope", "qwen-max"));

        ModelConfigEntity global = model("openai", "gpt-4o");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("dashscope", result.getProvider());
        assertEquals("qwen-max", result.getModelName());
    }

    @Test
    @DisplayName("5. All preferred unconfigured → global default")
    void allPreferredUnavailableFallsBackToGlobal() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        when(modelProviderService.isProviderConfigured("deepseek")).thenReturn(false);

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
        stubConfiguredProvider("deepseek", model("deepseek", "deepseek-chat"));
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

    @Test
    @DisplayName("9. Configured preferred provider without a system-default model still resolves")
    void preferredResolvesViaPerProviderFallback() {
        stubNoCapabilities();
        when(bindingService.getPreferredProviderIds(AGENT_ID)).thenReturn(List.of("deepseek"));
        // getPrimaryChatModelByProvider encapsulates the system-default →
        // first-enabled-chat fallback, so a preferred provider that does not
        // hold the single global default still contributes a primary model.
        stubConfiguredProvider("deepseek", model("deepseek", "deepseek-chat"));

        ModelConfigEntity global = model("volcengine-plan", "doubao-seed");
        ModelConfigEntity result = router.selectPrimary(AGENT_ID, global);

        assertNotNull(result);
        assertEquals("deepseek", result.getProvider());
        assertEquals("deepseek-chat", result.getModelName());
    }
}
