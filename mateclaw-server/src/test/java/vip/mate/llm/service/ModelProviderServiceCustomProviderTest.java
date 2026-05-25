package vip.mate.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.model.CreateCustomProviderRequest;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.model.ProviderConfigRequest;
import vip.mate.llm.repository.ModelProviderMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * Issue #39 regression: provider id ends up as a single path segment in
 * {@code /custom-providers/{providerId}}, so any unsafe character (slash,
 * space, {@code #}, {@code ?}) makes Spring's PathPatternParser miss the
 * controller and fall through to the static-resource handler — symptom is
 * a {@code NoResourceFoundException} on the DELETE the user reported.
 *
 * <p>These tests pin the two layers of the fix:</p>
 * <ul>
 *   <li>{@code createCustomProvider} rejects unsafe ids server-side, so a
 *       non-UI client (curl / Electron / 3rd-party) cannot bypass the
 *       front-end regex and persist a row that's later undeletable.</li>
 *   <li>{@code deleteCustomProvider} itself doesn't care about the shape
 *       of the id — it deletes by primary key. Anything that <em>did</em>
 *       slip into the DB before the create-side guard existed can still be
 *       cleaned up via the query-param controller variant.</li>
 * </ul>
 */
class ModelProviderServiceCustomProviderTest {

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

    // ==================== create-side guard ====================

    @Test
    @DisplayName("createCustomProvider rejects ids containing '/' (issue #39 root cause)")
    void rejectsSlashInId() {
        CreateCustomProviderRequest req = req("google/gemma-4-e4b", "Local Gemma");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.createCustomProvider(req));

        assertEquals("err.llm.provider_id_invalid", ex.getMsgKey());
        verify(providerMapper, never()).insert(any(ModelProviderEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("createCustomProvider rejects ids containing whitespace")
    void rejectsSpaceInId() {
        CreateCustomProviderRequest req = req("my provider", "Local Gemma");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.createCustomProvider(req));

        assertEquals("err.llm.provider_id_invalid", ex.getMsgKey());
        verify(providerMapper, never()).insert(any(ModelProviderEntity.class));
    }

    @Test
    @DisplayName("createCustomProvider rejects ids starting with '-' (regex requires alnum first char)")
    void rejectsLeadingHyphen() {
        CreateCustomProviderRequest req = req("-foo", "Local Gemma");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.createCustomProvider(req));

        assertEquals("err.llm.provider_id_invalid", ex.getMsgKey());
    }

    @Test
    @DisplayName("createCustomProvider rejects ids longer than 64 characters")
    void rejectsOverlongId() {
        // 65 chars: 'a' followed by 64 'b's.
        String tooLong = "a" + "b".repeat(64);
        CreateCustomProviderRequest req = req(tooLong, "Local Gemma");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.createCustomProvider(req));

        assertEquals("err.llm.provider_id_invalid", ex.getMsgKey());
    }

    @Test
    @DisplayName("createCustomProvider accepts a normal id (e.g. 'local-gemma') and persists")
    void acceptsNormalId() {
        CreateCustomProviderRequest req = req("local-gemma", "Local Gemma");
        when(providerMapper.selectById("local-gemma")).thenReturn(null);

        service.createCustomProvider(req);

        verify(providerMapper).insert(any(ModelProviderEntity.class));
    }

    @Test
    @DisplayName("createCustomProvider accepts ids with dot/underscore/hyphen and digits")
    void acceptsRichButSafeChars() {
        CreateCustomProviderRequest req = req("My_Local-Gemma.v2", "Local Gemma");
        when(providerMapper.selectById("My_Local-Gemma.v2")).thenReturn(null);

        service.createCustomProvider(req);

        verify(providerMapper).insert(any(ModelProviderEntity.class));
    }

    @Test
    @DisplayName("createCustomProvider persists requireApiKey=false for keyless internal OpenAI-compatible endpoints")
    void createCustomProviderCanDisableApiKeyRequirement() {
        CreateCustomProviderRequest req = req("internal-llm", "Internal LLM");
        req.setDefaultBaseUrl("http://llm.internal/v1");
        req.setRequireApiKey(false);
        when(providerMapper.selectById("internal-llm")).thenReturn(null);

        service.createCustomProvider(req);

        ArgumentCaptor<ModelProviderEntity> captor = ArgumentCaptor.forClass(ModelProviderEntity.class);
        verify(providerMapper).insert(captor.capture());
        assertFalse(captor.getValue().getRequireApiKey());
    }

    @Test
    @DisplayName("Empty id still produces 'fields_required' (existing guard, not the new regex)")
    void emptyIdStillReportsFieldsRequired() {
        CreateCustomProviderRequest req = req("", "Local Gemma");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.createCustomProvider(req));

        assertEquals("err.llm.provider_fields_required", ex.getMsgKey());
    }

    // ==================== delete-side: dirty data rescue ====================

    @Test
    @DisplayName("deleteCustomProvider works for an id with '/' once it reaches the service "
            + "(query-param controller variant is the URL bridge)")
    void deletesIdContainingSlash() {
        String dirtyId = "google/gemma-4-e4b";
        ModelProviderEntity dirty = customProvider(dirtyId);
        when(providerMapper.selectById(dirtyId)).thenReturn(dirty);

        service.deleteCustomProvider(dirtyId);

        verify(modelConfigService).deleteModelsByProvider(dirtyId);
        verify(providerMapper).deleteById(dirtyId);
    }

    @Test
    @DisplayName("deleteCustomProvider on a normal id (path-variant happy path) still works")
    void deletesNormalId() {
        String id = "local-gemma";
        ModelProviderEntity p = customProvider(id);
        when(providerMapper.selectById(id)).thenReturn(p);

        service.deleteCustomProvider(id);

        verify(modelConfigService).deleteModelsByProvider(id);
        verify(providerMapper).deleteById(id);
    }

    @Test
    @DisplayName("deleteCustomProvider refuses to delete a built-in (non-custom) provider")
    void refusesToDeleteBuiltin() {
        String id = "openai";
        ModelProviderEntity builtin = customProvider(id);
        builtin.setIsCustom(false);
        when(providerMapper.selectById(id)).thenReturn(builtin);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.deleteCustomProvider(id));

        assertEquals("err.llm.provider_builtin_readonly", ex.getMsgKey());
        verify(providerMapper, never()).deleteById(any(String.class));
        verify(modelConfigService, never()).deleteModelsByProvider(any());
    }

    @Test
    @DisplayName("updateProviderConfig can switch an existing custom provider to keyless mode")
    void updateProviderConfigCanDisableApiKeyRequirement() {
        String id = "internal-llm";
        ModelProviderEntity existing = customProvider(id);
        existing.setBaseUrl("http://llm.internal/v1");
        existing.setRequireApiKey(true);
        when(providerMapper.selectById(id)).thenReturn(existing);
        when(modelConfigService.listModelsByProvider(id)).thenReturn(java.util.List.of());

        ProviderConfigRequest req = new ProviderConfigRequest();
        req.setBaseUrl("http://llm.internal/v1");
        req.setProtocol("openai-compatible");
        req.setChatModel("OpenAIChatModel");
        req.setRequireApiKey(false);

        service.updateProviderConfig(id, req);

        assertFalse(existing.getRequireApiKey());
        verify(providerMapper).updateById(existing);
    }

    // ==================== fixtures ====================

    private static CreateCustomProviderRequest req(String id, String name) {
        CreateCustomProviderRequest r = new CreateCustomProviderRequest();
        r.setId(id);
        r.setName(name);
        r.setProtocol("openai-compatible");
        r.setChatModel("OpenAIChatModel");
        return r;
    }

    private static ModelProviderEntity customProvider(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setIsCustom(true);
        p.setIsLocal(false);
        p.setEnabled(true);
        return p;
    }
}
