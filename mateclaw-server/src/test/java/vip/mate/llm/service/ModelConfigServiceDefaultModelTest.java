package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for ModelConfigService.getDefaultModel() provider-availability filtering.
 *
 * Scenario: system has a default chat model but its provider is unconfigured (e.g. DashScope
 * marked as default but no API key). The method must skip it and return the first chat model
 * whose provider IS configured instead of blindly returning the unconfigured default.
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigServiceDefaultModelTest {

    @Mock
    private ModelConfigMapper modelConfigMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ModelProviderService modelProviderService;

    @InjectMocks
    private ModelConfigService service;

    @BeforeEach
    void injectLazyDep() {
        // Simulate the @Lazy @Autowired field injection Spring does at runtime.
        ReflectionTestUtils.setField(service, "modelProviderService", modelProviderService);
    }

    private static ModelConfigEntity chatModel(String provider, String modelName, boolean isDefault) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider(provider);
        m.setModelName(modelName);
        m.setIsDefault(isDefault);
        m.setEnabled(true);
        m.setModelType("chat");
        return m;
    }

    // ── Scenario 1: default model available ────────────────────────────────────

    @Test
    @DisplayName("configured default model is returned directly")
    void defaultModelConfigured_returnsIt() {
        ModelConfigEntity dashscopeDefault = chatModel("dashscope", "qwen-plus", true);

        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dashscopeDefault);
        when(modelProviderService.isProviderEnabledAndConfigured("dashscope")).thenReturn(true);

        ModelConfigEntity result = service.getDefaultModel();

        assertEquals("dashscope", result.getProvider());
        assertEquals("qwen-plus", result.getModelName());
        // Should not proceed to the full-scan fallback path.
        verify(modelConfigMapper, times(1)).selectOne(any());
        verify(modelConfigMapper, never()).selectList(any());
    }

    // ── Scenario 2: default model provider unavailable → fallback ─────────────

    @Test
    @DisplayName("default model provider unconfigured: falls back to first configured alternative")
    void defaultModelProviderUnconfigured_returnsFallback() {
        ModelConfigEntity dashscopeDefault = chatModel("dashscope", "qwen-plus", true);
        ModelConfigEntity zhipuModel = chatModel("zhipu", "glm-4", false);

        // First selectOne → the is_default=true model
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dashscopeDefault);
        // dashscope is NOT configured, zhipu IS
        when(modelProviderService.isProviderEnabledAndConfigured("dashscope")).thenReturn(false);
        when(modelProviderService.isProviderEnabledAndConfigured("zhipu")).thenReturn(true);
        // Full-scan returns both; zhipu comes second but dashscope is skipped
        when(modelConfigMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(dashscopeDefault, zhipuModel));

        ModelConfigEntity result = service.getDefaultModel();

        assertEquals("zhipu", result.getProvider());
        assertEquals("glm-4", result.getModelName());
    }

    // ── Scenario 3: no configured provider at all ──────────────────────────────

    @Test
    @DisplayName("all enabled chat model providers unconfigured: throws with clear message")
    void allProvidersUnconfigured_throws() {
        ModelConfigEntity dashscopeDefault = chatModel("dashscope", "qwen-plus", true);
        ModelConfigEntity zhipuModel = chatModel("zhipu", "glm-4", false);

        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dashscopeDefault);
        when(modelProviderService.isProviderEnabledAndConfigured(any())).thenReturn(false);
        when(modelConfigMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(dashscopeDefault, zhipuModel));

        MateClawException ex = assertThrows(MateClawException.class, () -> service.getDefaultModel());
        assertEquals("err.llm.no_configured_provider", ex.getMsgKey());
    }

    // ── Scenario 4: no enabled model at all ───────────────────────────────────

    @Test
    @DisplayName("no enabled chat model at all: throws no_available_model")
    void noEnabledModel_throws() {
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(modelConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        MateClawException ex = assertThrows(MateClawException.class, () -> service.getDefaultModel());
        assertEquals("err.llm.no_available_model", ex.getMsgKey());
    }

    // ── Scenario 5: modelProviderService unavailable (bootstrap) ──────────────

    @Test
    @DisplayName("modelProviderService null (bootstrap): default model returned without filtering")
    void providerServiceNull_returnsDefaultWithoutFilter() {
        ReflectionTestUtils.setField(service, "modelProviderService", null);
        ModelConfigEntity dashscopeDefault = chatModel("dashscope", "qwen-plus", true);

        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dashscopeDefault);

        // With null providerService, isProviderEnabledAndConfigured returns true (lenient bootstrap)
        ModelConfigEntity result = service.getDefaultModel();
        assertEquals("dashscope", result.getProvider());
    }
}
