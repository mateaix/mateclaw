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
 * RFC-073: covers the five {@link Liveness} states surfaced through
 * {@code listProviders()}. The five branches must remain orthogonal and
 * mutually exclusive — the UI relies on it as a state machine.
 *
 * <p>Real {@link AvailableProviderPool} and {@link ProviderHealthTracker}
 * (no Spring deps); {@link ProviderInitProbe} is a Mockito mock since its
 * own constructor pulls the Spring context.</p>
 */
class ModelProviderServiceLivenessTest {

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
        // failure-threshold = 1 so a single recordFailure() trips cooldown deterministically.
        ProviderHealthProperties props = new ProviderHealthProperties();
        props.setFailureThreshold(1);
        healthTracker = new ProviderHealthTracker(props);
        initProbe = mock(ProviderInitProbe.class);
        initProbeProvider = mock(ObjectProvider.class);
        when(initProbeProvider.getIfAvailable()).thenReturn(initProbe);

        service = new ModelProviderService(providerMapper, modelConfigService, eventPublisher,
                claudeCodeOAuthProvider, pool, healthTracker, initProbeProvider);
    }

    @Test
    @DisplayName("LIVE: configured + probed + in pool + not in cooldown")
    void liveProvider() {
        seedProvider("openai", false);
        when(initProbe.hasBeenProbed("openai")).thenReturn(true);
        pool.add("openai");

        ProviderInfoDTO dto = singleResult();
        assertEquals(Liveness.LIVE, dto.getLiveness());
        assertNull(dto.getUnavailableReason());
        assertNull(dto.getCooldownRemainingMs());
        assertTrue(dto.getAvailable(), "available must be true when LIVE and has models");
    }

    @Test
    @DisplayName("UNCONFIGURED: cloud provider with no api key — short-circuit before pool / probe checks")
    void unconfiguredProvider() {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId("openai");
        p.setName("OpenAI");
        p.setIsLocal(false);
        p.setIsCustom(false);
        p.setRequireApiKey(true);
        p.setApiKey("");
        p.setBaseUrl("https://api.openai.com/v1");
        when(providerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(p));
        when(modelConfigService.listModels()).thenReturn(List.of());

        ProviderInfoDTO dto = singleResult();
        assertEquals(Liveness.UNCONFIGURED, dto.getLiveness());
        // Probe should not even be consulted for unconfigured providers.
        verify(initProbe, never()).hasBeenProbed("openai");
        assertFalse(dto.getAvailable());
    }

    @Test
    @DisplayName("UNPROBED: configured but probe hasn't fired yet (startup window)")
    void unprobedProvider() {
        seedProvider("ollama", true);
        when(initProbe.hasBeenProbed("ollama")).thenReturn(false);
        // pool intentionally empty — UNPROBED takes precedence over REMOVED so the UI
        // can render skeletons during the startup window instead of false negatives.

        ProviderInfoDTO dto = singleResult();
        assertEquals(Liveness.UNPROBED, dto.getLiveness());
        assertFalse(dto.getAvailable());
    }

    @Test
    @DisplayName("REMOVED: probed and HARD-removed — reason + lastProbedAtMs populated")
    void removedProvider() {
        seedProvider("openai", false);
        when(initProbe.hasBeenProbed("openai")).thenReturn(true);
        pool.remove("openai", AvailableProviderPool.RemovalSource.AUTH_ERROR, "401 Unauthorized");

        ProviderInfoDTO dto = singleResult();
        assertEquals(Liveness.REMOVED, dto.getLiveness());
        assertEquals("401 Unauthorized", dto.getUnavailableReason());
        assertNotNull(dto.getLastProbedAtMs());
        assertFalse(dto.getAvailable());
    }

    @Test
    @DisplayName("COOLDOWN: in pool but tracker reports cooldown remaining")
    void cooldownProvider() {
        seedProvider("openai", false);
        when(initProbe.hasBeenProbed("openai")).thenReturn(true);
        pool.add("openai");
        // failure-threshold = 1 → one recorded failure trips cooldown immediately.
        healthTracker.recordFailure("openai");

        ProviderInfoDTO dto = singleResult();
        assertEquals(Liveness.COOLDOWN, dto.getLiveness());
        assertNotNull(dto.getCooldownRemainingMs());
        assertTrue(dto.getCooldownRemainingMs() > 0);
        assertFalse(dto.getAvailable(), "cooldown is not LIVE so available must be false");
    }

    @Test
    @DisplayName("Probe-bean absent (test context with no init probe) → fall back to LIVE not UNPROBED")
    void noProbeBeanFallsOpen() {
        when(initProbeProvider.getIfAvailable()).thenReturn(null);
        seedProvider("openai", false);
        pool.add("openai");

        ProviderInfoDTO dto = singleResult();
        assertEquals(Liveness.LIVE, dto.getLiveness(),
                "no probe bean must not strand all providers in UNPROBED forever");
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Wire mapper / model service to return a single configured provider with one model. */
    private void seedProvider(String id, boolean local) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setIsLocal(local);
        p.setIsCustom(false);
        p.setRequireApiKey(!local);
        p.setApiKey(local ? "" : "sk-test-key-1234567890");
        p.setBaseUrl(local ? "http://127.0.0.1:11434" : "https://api.example.com/v1");
        when(providerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(p));

        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider(id);
        m.setModelName(id + "-model");
        m.setName(id + "-model");
        m.setBuiltin(true);
        when(modelConfigService.listModels()).thenReturn(List.of(m));
    }

    private ProviderInfoDTO singleResult() {
        List<ProviderInfoDTO> list = service.listProviders();
        assertEquals(1, list.size());
        return list.get(0);
    }
}
