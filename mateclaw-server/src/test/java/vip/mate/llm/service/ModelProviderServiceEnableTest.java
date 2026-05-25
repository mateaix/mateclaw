package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.model.EnableResult;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.repository.ModelProviderMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RFC-074: covers the enable / disable lifecycle:
 * <ul>
 *   <li>setEnabled flips the column and publishes {@link ModelConfigChangedEvent}.</li>
 *   <li>Disabling the provider that owns the current default model auto-promotes
 *       a replacement so chat doesn't break on the next request.</li>
 *   <li>Disabling a provider whose model is NOT the current default is a no-op
 *       on the default model.</li>
 *   <li>If no replacement provider exists, the call returns {@code unchanged()}
 *       and the broken default is left for the empty-state UI to catch.</li>
 *   <li>setEnabled(true) on an already-enabled row (or false on disabled) is a no-op.</li>
 * </ul>
 *
 * <p>List-vs-catalog filtering is intentionally <b>not</b> tested here — the
 * MyBatis Plus mapper is mocked, so the {@code .eq(enabled, true)} clause
 * doesn't actually run. That's an integration concern handled by manual
 * Flyway smoke verification (and would need a Testcontainers test to cover
 * properly). The unit test concerns are state transitions + side effects.</p>
 */
class ModelProviderServiceEnableTest {

    private ModelProviderMapper providerMapper;
    private ModelConfigService modelConfigService;
    private ApplicationEventPublisher eventPublisher;
    private ObjectProvider<ClaudeCodeOAuthService> claudeCodeOAuthProvider;
    private AvailableProviderPool pool;
    private ProviderHealthTracker healthTracker;
    private ProviderInitProbe initProbe;
    private ObjectProvider<ProviderInitProbe> initProbeProvider;

    private ModelProviderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        providerMapper = mock(ModelProviderMapper.class);
        modelConfigService = mock(ModelConfigService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        claudeCodeOAuthProvider = mock(ObjectProvider.class);
        when(claudeCodeOAuthProvider.getIfAvailable()).thenReturn(null);
        pool = new AvailableProviderPool();
        healthTracker = new ProviderHealthTracker(new ProviderHealthProperties());
        initProbe = mock(ProviderInitProbe.class);
        initProbeProvider = mock(ObjectProvider.class);
        when(initProbeProvider.getIfAvailable()).thenReturn(initProbe);

        service = new ModelProviderService(providerMapper, modelConfigService, eventPublisher,
                claudeCodeOAuthProvider, pool, healthTracker, initProbeProvider);
    }

    @Test
    @DisplayName("setEnabled(true) on disabled row: flips flag, persists, publishes 'provider-enabled' event")
    void enableFlipsFlag() {
        ModelProviderEntity openai = providerEntity("openai", false /* disabled */);
        when(providerMapper.selectById("openai")).thenReturn(openai);

        EnableResult result = service.setEnabled("openai", true);

        assertFalse(result.defaultSwitched());
        assertTrue(openai.getEnabled(), "in-memory entity flipped");
        verify(providerMapper).updateById(openai);
        ArgumentCaptor<ModelConfigChangedEvent> evtCap = ArgumentCaptor.forClass(ModelConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(evtCap.capture());
        assertEquals("provider-enabled", evtCap.getValue().reason());
    }

    @Test
    @DisplayName("setEnabled(true) on already-enabled row: no DB write, no event")
    void enableNoOpOnAlreadyEnabled() {
        ModelProviderEntity openai = providerEntity("openai", true /* already enabled */);
        when(providerMapper.selectById("openai")).thenReturn(openai);

        EnableResult result = service.setEnabled("openai", true);

        assertFalse(result.defaultSwitched());
        verify(providerMapper, never()).updateById(any(ModelProviderEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("setEnabled(false) when provider's model is current default: auto-switches and reports new")
    void disableSwitchesDefault() {
        ModelProviderEntity disabled = providerEntity("openai", true);
        ModelProviderEntity replacement = providerEntity("dashscope", true);
        when(providerMapper.selectById("openai")).thenReturn(disabled);

        // Current default belongs to openai
        ModelConfigEntity currentDefault = new ModelConfigEntity();
        currentDefault.setProvider("openai");
        currentDefault.setModelName("gpt-4");
        when(modelConfigService.getDefaultModel()).thenReturn(currentDefault);

        // After excluding openai, dashscope is the only candidate
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(replacement));

        ModelConfigEntity dashModel = new ModelConfigEntity();
        dashModel.setProvider("dashscope");
        dashModel.setModelName("qwen-plus");
        when(modelConfigService.listModelsByProvider("dashscope")).thenReturn(List.of(dashModel));

        EnableResult result = service.setEnabled("openai", false);

        assertTrue(result.defaultSwitched());
        assertEquals("dashscope", result.newDefaultProviderId());
        assertEquals("qwen-plus", result.newDefaultModel());
        verify(modelConfigService).setDefaultModel("dashscope", "qwen-plus");
    }

    @Test
    @DisplayName("setEnabled(false) when current default belongs to another provider: no switch")
    void disableLeavesDefaultAlone() {
        ModelProviderEntity disabled = providerEntity("openai", true);
        when(providerMapper.selectById("openai")).thenReturn(disabled);

        // Current default belongs to a different provider — no switch needed
        ModelConfigEntity currentDefault = new ModelConfigEntity();
        currentDefault.setProvider("dashscope");
        currentDefault.setModelName("qwen-plus");
        when(modelConfigService.getDefaultModel()).thenReturn(currentDefault);

        EnableResult result = service.setEnabled("openai", false);

        assertFalse(result.defaultSwitched());
        verify(modelConfigService, never()).setDefaultModel(anyString(), anyString());
    }

    @Test
    @DisplayName("setEnabled(false) with no replacement candidate: returns unchanged, leaves broken default for UI")
    void disableNoReplacement() {
        ModelProviderEntity disabled = providerEntity("openai", true);
        when(providerMapper.selectById("openai")).thenReturn(disabled);
        ModelConfigEntity currentDefault = new ModelConfigEntity();
        currentDefault.setProvider("openai");
        currentDefault.setModelName("gpt-4");
        when(modelConfigService.getDefaultModel()).thenReturn(currentDefault);

        // No other enabled providers
        when(providerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>());

        EnableResult result = service.setEnabled("openai", false);

        assertFalse(result.defaultSwitched(),
                "no replacement → unchanged; UI empty-state will catch the broken default");
        verify(modelConfigService, never()).setDefaultModel(anyString(), anyString());
    }

    @Test
    @DisplayName("setEnabled(false) when getDefaultModel throws (no default at all): returns unchanged")
    void disableWhenNoDefaultExists() {
        ModelProviderEntity disabled = providerEntity("openai", true);
        when(providerMapper.selectById("openai")).thenReturn(disabled);
        when(modelConfigService.getDefaultModel())
                .thenThrow(new MateClawException("err.test.no_default", "no default"));

        EnableResult result = service.setEnabled("openai", false);

        assertFalse(result.defaultSwitched());
        verify(modelConfigService, never()).setDefaultModel(anyString(), anyString());
    }

    @Test
    @DisplayName("setEnabled(false) auto-switch skips replacement candidates with no models")
    void disableSkipsReplacementWithNoModels() {
        ModelProviderEntity disabled = providerEntity("openai", true);
        ModelProviderEntity emptyCandidate = providerEntity("anthropic", true);
        ModelProviderEntity goodCandidate = providerEntity("dashscope", true);
        when(providerMapper.selectById("openai")).thenReturn(disabled);

        ModelConfigEntity currentDefault = new ModelConfigEntity();
        currentDefault.setProvider("openai");
        currentDefault.setModelName("gpt-4");
        when(modelConfigService.getDefaultModel()).thenReturn(currentDefault);

        // anthropic appears first in the candidates list but has no models
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(emptyCandidate, goodCandidate));
        when(modelConfigService.listModelsByProvider("anthropic")).thenReturn(new ArrayList<>());
        ModelConfigEntity dashModel = new ModelConfigEntity();
        dashModel.setProvider("dashscope");
        dashModel.setModelName("qwen-plus");
        when(modelConfigService.listModelsByProvider("dashscope")).thenReturn(List.of(dashModel));

        EnableResult result = service.setEnabled("openai", false);

        assertTrue(result.defaultSwitched());
        assertEquals("dashscope", result.newDefaultProviderId());
        verify(modelConfigService, never()).setDefaultModel(eq("anthropic"), anyString());
        verify(modelConfigService).setDefaultModel("dashscope", "qwen-plus");
    }

    /** Build a fully-configured cloud entity with the given enabled state. */
    private static ModelProviderEntity providerEntity(String id, boolean enabled) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setIsLocal(false);
        p.setIsCustom(false);
        p.setRequireApiKey(true);
        p.setApiKey("sk-test-key-1234567890");
        p.setBaseUrl("https://api.example.com/v1");
        p.setEnabled(enabled);
        return p;
    }
}
