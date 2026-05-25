package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.model.Liveness;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.model.ProviderInfoDTO;
import vip.mate.llm.repository.ModelProviderMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Issue #81: row-based isProviderConfigured + applySuggestedAction. Each test is
 * one row of the truth table in RFC §2.3 (behavior diff vs. v1) and §7
 * (suggestedAction decision tree).
 */
class ModelProviderServiceConfiguredTest {

    private ModelProviderMapper providerMapper;
    private ModelConfigService modelConfigService;
    private ApplicationEventPublisher eventPublisher;
    private ObjectProvider<ClaudeCodeOAuthService> claudeCodeOAuthProvider;
    private ClaudeCodeOAuthService claudeCodeOAuthService;
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
        claudeCodeOAuthService = mock(ClaudeCodeOAuthService.class);
        when(claudeCodeOAuthProvider.getIfAvailable()).thenReturn(null);
        pool = new AvailableProviderPool();
        ProviderHealthProperties props = new ProviderHealthProperties();
        props.setFailureThreshold(1);
        healthTracker = new ProviderHealthTracker(props);
        initProbe = mock(ProviderInitProbe.class);
        initProbeProvider = mock(ObjectProvider.class);
        when(initProbeProvider.getIfAvailable()).thenReturn(initProbe);
        // Default: every provider has been probed so liveness is computed normally.
        when(initProbe.hasBeenProbed(any())).thenReturn(true);

        service = new ModelProviderService(providerMapper, modelConfigService, eventPublisher,
                claudeCodeOAuthProvider, pool, healthTracker, initProbeProvider);
    }

    @Test
    @DisplayName("Issue #81: llama.cpp local + empty Base URL → UNCONFIGURED + fill_base_url + hint")
    void llamacppEmptyBaseUrl() {
        ModelProviderEntity p = local("llamacpp");
        p.setBaseUrl("");
        seedProviderRow(p, false);

        ProviderInfoDTO dto = singleResult();
        assertFalse(dto.getConfigured(), "empty Base URL must NOT be considered configured");
        assertEquals(Liveness.UNCONFIGURED, dto.getLiveness());
        assertEquals("fill_base_url", dto.getSuggestedAction());
        assertEquals("provider.hint.llamacppBaseUrlExample", dto.getSuggestedActionHintKey());
        assertEquals("http://127.0.0.1:8080/v1", dto.getSuggestedActionHintArgs().get("example"));
        assertEquals("baseUrl", dto.getMissingFields());
        assertEquals("NOT_REQUIRED", dto.getAuthStatus());
        assertFalse(dto.getBaseUrlComplete());
    }

    @Test
    @DisplayName("llama.cpp local + Base URL filled but pool REMOVED → REMOVED + reprobe")
    void llamacppBaseUrlFilledButRemoved() {
        ModelProviderEntity p = local("llamacpp");
        p.setBaseUrl("http://127.0.0.1:8080/v1");
        seedProviderRow(p, true);
        pool.remove("llamacpp", AvailableProviderPool.RemovalSource.INIT_PROBE,
                "init probe failed: connection refused");

        ProviderInfoDTO dto = singleResult();
        assertTrue(dto.getConfigured());
        assertEquals(Liveness.REMOVED, dto.getLiveness());
        assertEquals("reprobe", dto.getSuggestedAction());
        assertNull(dto.getSuggestedActionHintKey(), "REMOVED state should not carry a hint key");
    }

    @Test
    @DisplayName("Ollama local + LIVE + 0 models + supportModelDiscovery=true → pull_model")
    void ollamaLiveNoModels() {
        ModelProviderEntity p = local("ollama");
        p.setBaseUrl("http://127.0.0.1:11434");
        p.setSupportModelDiscovery(true);
        when(providerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(p));
        when(modelConfigService.listModels()).thenReturn(List.of());   // no models registered
        pool.add("ollama");

        ProviderInfoDTO dto = singleResult();
        assertEquals(Liveness.LIVE, dto.getLiveness());
        assertEquals("pull_model", dto.getSuggestedAction());
    }

    @Test
    @DisplayName("OpenAI cloud + apiKey empty → UNCONFIGURED + fill_api_key + no hint")
    void openaiCloudEmptyApiKey() {
        ModelProviderEntity p = cloud("openai", true);
        p.setApiKey("");
        seedProviderRow(p, false);

        ProviderInfoDTO dto = singleResult();
        assertFalse(dto.getConfigured());
        assertEquals(Liveness.UNCONFIGURED, dto.getLiveness());
        assertEquals("fill_api_key", dto.getSuggestedAction());
        assertNull(dto.getSuggestedActionHintKey(), "cloud providers don't need a base-url hint");
        assertEquals("MISSING", dto.getAuthStatus());
        assertEquals("apiKey", dto.getMissingFields());
        assertNull(dto.getBaseUrlComplete(), "cloud provider's baseUrlComplete should be null (n/a)");
    }

    @Test
    @DisplayName("OpenAI cloud + apiKey filled + LIVE → none + CONFIGURED")
    void openaiCloudHealthy() {
        ModelProviderEntity p = cloud("openai", true);
        p.setApiKey("sk-test-1234567890");
        seedProviderRow(p, true);
        pool.add("openai");

        ProviderInfoDTO dto = singleResult();
        assertTrue(dto.getConfigured());
        assertEquals(Liveness.LIVE, dto.getLiveness());
        assertEquals("none", dto.getSuggestedAction());
        assertEquals("CONFIGURED", dto.getAuthStatus());
        assertEquals("", dto.getMissingFields());
    }

    @Test
    @DisplayName("Kimi cloud + apiKey empty → fill_api_key (same shape as OpenAI)")
    void kimiCloudEmptyApiKey() {
        ModelProviderEntity p = cloud("kimi", true);
        p.setApiKey("");
        seedProviderRow(p, false);

        ProviderInfoDTO dto = singleResult();
        assertFalse(dto.getConfigured());
        assertEquals("fill_api_key", dto.getSuggestedAction());
    }

    @Test
    @DisplayName("Custom OpenAI-compat + baseUrl empty + apiKey filled + requireApiKey=true → fill_base_url")
    void customOpenAiCompatEmptyBaseUrl() {
        ModelProviderEntity p = custom("my-server");
        p.setRequireApiKey(true);
        p.setBaseUrl("");
        p.setApiKey("sk-test-1234567890");
        seedProviderRow(p, false);

        ProviderInfoDTO dto = singleResult();
        assertFalse(dto.getConfigured());
        assertEquals("fill_base_url", dto.getSuggestedAction());
        assertEquals("baseUrl", dto.getMissingFields());
    }

    @Test
    @DisplayName("Custom OpenAI-compat + baseUrl filled + apiKey empty + requireApiKey=true → fill_api_key")
    void customOpenAiCompatEmptyApiKey() {
        ModelProviderEntity p = custom("my-server");
        p.setRequireApiKey(true);
        p.setBaseUrl("http://x.example.com/v1");
        p.setApiKey("");
        seedProviderRow(p, false);

        ProviderInfoDTO dto = singleResult();
        assertFalse(dto.getConfigured());
        assertEquals("fill_api_key", dto.getSuggestedAction());
        assertEquals("apiKey", dto.getMissingFields());
    }

    @Test
    @DisplayName("Custom OpenAI-compat + both empty + requireApiKey=true → configure_required_fields + both missing")
    void customOpenAiCompatBothEmpty() {
        ModelProviderEntity p = custom("my-server");
        p.setRequireApiKey(true);
        p.setBaseUrl("");
        p.setApiKey("");
        seedProviderRow(p, false);

        ProviderInfoDTO dto = singleResult();
        assertFalse(dto.getConfigured());
        assertEquals("configure_required_fields", dto.getSuggestedAction());
        assertEquals("apiKey,baseUrl", dto.getMissingFields());
        // hint emitted because action is configure_required_fields
        assertEquals("provider.hint.openaiCompatBaseUrlExample", dto.getSuggestedActionHintKey());
    }

    @Test
    @DisplayName("Custom OpenAI-compat + both filled + LIVE → none")
    void customOpenAiCompatHealthy() {
        ModelProviderEntity p = custom("my-server");
        p.setRequireApiKey(true);
        p.setBaseUrl("http://x.example.com/v1");
        p.setApiKey("sk-test-1234567890");
        seedProviderRow(p, true);
        pool.add("my-server");

        ProviderInfoDTO dto = singleResult();
        assertTrue(dto.getConfigured());
        assertEquals(Liveness.LIVE, dto.getLiveness());
        assertEquals("none", dto.getSuggestedAction());
    }

    @Test
    @DisplayName("OAuth provider not connected → UNCONFIGURED + start_oauth + OAUTH_PENDING")
    void oauthNotConnected() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId("some-oauth");
        p.setName("Some OAuth");
        p.setAuthType("oauth");
        // No oauthAccessToken → not configured.
        seedProviderRow(p, false);

        ProviderInfoDTO dto = singleResult();
        assertFalse(dto.getConfigured());
        assertEquals(Liveness.UNCONFIGURED, dto.getLiveness());
        assertEquals("start_oauth", dto.getSuggestedAction());
        assertEquals("OAUTH_PENDING", dto.getAuthStatus());
    }

    @Test
    @DisplayName("OAuth provider connected → LIVE + CONFIGURED")
    void oauthConnected() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId("some-oauth");
        p.setName("Some OAuth");
        p.setAuthType("oauth");
        p.setOauthAccessToken("ya29.test");
        seedProviderRow(p, true);
        pool.add("some-oauth");

        ProviderInfoDTO dto = singleResult();
        assertTrue(dto.getConfigured());
        assertEquals(Liveness.LIVE, dto.getLiveness());
        assertEquals("CONFIGURED", dto.getAuthStatus());
    }

    @Test
    @DisplayName("Default 'enabled' filter: providers without enabled=true are excluded")
    void defaultProviderRespectsEnabledFlag() {
        // Sanity: the existing infrastructure still gates on enabled when listProviders
        // is called. seedProviderRow sets enabled=true so this is just defensive.
        ModelProviderEntity p = local("ollama");
        p.setEnabled(true);
        seedProviderRow(p, true);
        pool.add("ollama");
        assertEquals(1, service.listProviders().size());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void seedProviderRow(ModelProviderEntity p, boolean withModel) {
        if (p.getEnabled() == null) p.setEnabled(true);
        when(providerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(p));
        if (withModel) {
            ModelConfigEntity m = new ModelConfigEntity();
            m.setProvider(p.getProviderId());
            m.setModelName(p.getProviderId() + "-model");
            m.setName(p.getProviderId() + "-model");
            m.setBuiltin(true);
            when(modelConfigService.listModels()).thenReturn(List.of(m));
        } else {
            when(modelConfigService.listModels()).thenReturn(List.of());
        }
    }

    private static ModelProviderEntity cloud(String id, boolean requireApiKey) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setIsLocal(false);
        p.setIsCustom(false);
        p.setRequireApiKey(requireApiKey);
        return p;
    }

    private static ModelProviderEntity local(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setIsLocal(true);
        p.setIsCustom(false);
        p.setRequireApiKey(false);
        p.setBaseUrl("http://127.0.0.1:11434");   // overridden per test as needed
        return p;
    }

    private static ModelProviderEntity custom(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setIsLocal(false);
        p.setIsCustom(true);
        return p;
    }

    private ProviderInfoDTO singleResult() {
        List<ProviderInfoDTO> list = service.listProviders();
        assertEquals(1, list.size());
        return list.get(0);
    }
}
