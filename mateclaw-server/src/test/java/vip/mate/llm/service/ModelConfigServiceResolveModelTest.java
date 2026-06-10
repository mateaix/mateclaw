package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ModelConfigService#resolveModel(String)} — the lookup
 * path used by {@code AgentGraphBuilder} to honor a per-Agent model override
 * (RFC-03 Lane G1).
 *
 * <p>Contract:
 * <ul>
 *   <li>Blank / null name → fall back to {@link ModelConfigService#getDefaultModel()}</li>
 *   <li>Name matches an enabled model → return that entity</li>
 *   <li>Name does not match (deleted / disabled / typo) → fall back to default</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigServiceResolveModelTest {

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

    // ── Blank input → fall back to default ─────────────────────────────────────

    @Test
    @DisplayName("null name falls back to global default")
    void nullNameFallsBack() {
        ModelConfigEntity defaultModel = chatModel("dashscope", "qwen-plus", true);
        // resolveModel skips its own selectOne for null/blank input, then calls getDefaultModel(),
        // which itself runs one selectOne lookup for the default flag.
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(defaultModel);
        when(modelProviderService.isProviderEnabledAndConfigured("dashscope")).thenReturn(true);

        ModelConfigEntity result = service.resolveModel(null);

        assertNotNull(result);
        assertEquals("qwen-plus", result.getModelName());
        // Exactly one lookup — the default-model query inside getDefaultModel().
        verify(modelConfigMapper, times(1)).selectOne(any());
    }

    @Test
    @DisplayName("blank/whitespace name falls back to global default")
    void blankNameFallsBack() {
        ModelConfigEntity defaultModel = chatModel("dashscope", "qwen-plus", true);
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(defaultModel);
        when(modelProviderService.isProviderEnabledAndConfigured("dashscope")).thenReturn(true);

        ModelConfigEntity result = service.resolveModel("   ");

        assertNotNull(result);
        assertEquals("qwen-plus", result.getModelName());
        verify(modelConfigMapper, times(1)).selectOne(any());
    }

    // ── Match → return named model ─────────────────────────────────────────────

    @Test
    @DisplayName("named model match returns the entity (no default fallback)")
    void namedMatchReturnsEntity() {
        ModelConfigEntity claude = chatModel("anthropic", "claude-3-5-sonnet", false);
        // resolveModel's first selectOne (lookup by name) hits.
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(claude);

        ModelConfigEntity result = service.resolveModel("claude-3-5-sonnet");

        assertNotNull(result);
        assertEquals("anthropic", result.getProvider());
        assertEquals("claude-3-5-sonnet", result.getModelName());
        // Exactly one lookup — getDefaultModel must NOT be called.
        verify(modelConfigMapper, times(1)).selectOne(any());
        verify(modelProviderService, never()).isProviderEnabledAndConfigured(any());
    }

    // ── Unmatched → fall back to default ───────────────────────────────────────

    @Test
    @DisplayName("named model not found (typo / deleted) falls back to default")
    void unmatchedNameFallsBack() {
        ModelConfigEntity defaultModel = chatModel("dashscope", "qwen-plus", true);
        // First call (lookup by name) returns null; second call (default) returns the default.
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null)         // 1st: name lookup misses
                .thenReturn(defaultModel); // 2nd: default flag lookup
        when(modelProviderService.isProviderEnabledAndConfigured("dashscope")).thenReturn(true);

        ModelConfigEntity result = service.resolveModel("ghost-model");

        assertNotNull(result);
        assertEquals("qwen-plus", result.getModelName());
        // Two queries — one miss, then the default fallback.
        verify(modelConfigMapper, times(2)).selectOne(any());
    }
}
