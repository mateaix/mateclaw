package vip.mate.llm.failover;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.repository.ModelProviderMapper;
import vip.mate.llm.service.ModelProviderService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the startup-probe orchestration:
 * <ul>
 *   <li>Healthy probes → provider added to pool.</li>
 *   <li>Failed probes → provider removed with INIT_PROBE source.</li>
 *   <li>Slow probe → fail-open (in-pool) so chat isn't gated by a stalled probe.</li>
 *   <li>Missing strategy → fail-open (in-pool).</li>
 *   <li>{@code probeOne} updates pool state on demand.</li>
 *   <li>Duplicate strategies for the same protocol fail-fast at construction.</li>
 * </ul>
 *
 * <p>Strategies are real test-double instances (not Mockito mocks) so we can
 * inject latency or throw cheaply; the mapper / service collaborators are
 * stock Mockito mocks because they're MyBatis-Plus / Spring beans.</p>
 */
class ProviderInitProbeTest {

    private ModelProviderMapper mapper;
    private ModelProviderService providerService;
    private AvailableProviderPool pool;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelProviderMapper.class);
        providerService = mock(ModelProviderService.class);
        pool = new AvailableProviderPool();
    }

    @Test
    @DisplayName("All strategies pass: every configured provider lands in the pool")
    void allHealthy() {
        ModelProviderEntity openai = provider("openai", ModelProtocol.OPENAI_COMPATIBLE);
        ModelProviderEntity anthropic = provider("anthropic", ModelProtocol.ANTHROPIC_MESSAGES);
        ModelProviderEntity dashscope = provider("dashscope", ModelProtocol.DASHSCOPE_NATIVE);
        configure(List.of(openai, anthropic, dashscope), id -> true);

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of(
                stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.ok(10)),
                stub(ModelProtocol.ANTHROPIC_MESSAGES, p -> ProbeResult.ok(20)),
                stub(ModelProtocol.DASHSCOPE_NATIVE, p -> ProbeResult.ok(30))));
        probe.probeAllConfigured();

        assertTrue(pool.contains("openai"));
        assertTrue(pool.contains("anthropic"));
        assertTrue(pool.contains("dashscope"));
    }

    @Test
    @DisplayName("Failed probe removes provider with INIT_PROBE source and the error message")
    void failurePathRemovesWithReason() {
        configure(List.of(provider("openai", ModelProtocol.OPENAI_COMPATIBLE)), id -> true);

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of(
                stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.fail(50, "401 Unauthorized"))));
        probe.probeAllConfigured();

        assertFalse(pool.contains("openai"));
        var reason = pool.snapshot().get("openai");
        assertNotNull(reason);
        assertEquals(AvailableProviderPool.RemovalSource.INIT_PROBE, reason.source());
        assertTrue(reason.message().contains("401 Unauthorized"),
                "removal message must surface the underlying probe error");
    }

    @Test
    @DisplayName("Mixed batch: pass + fail in one run leaves correct pool state")
    void mixedBatch() {
        configure(List.of(
                provider("openai", ModelProtocol.OPENAI_COMPATIBLE),
                provider("anthropic", ModelProtocol.ANTHROPIC_MESSAGES)), id -> true);

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of(
                stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.ok(10)),
                stub(ModelProtocol.ANTHROPIC_MESSAGES, p -> ProbeResult.fail(15, "auth"))));
        probe.probeAllConfigured();

        assertTrue(pool.contains("openai"));
        assertFalse(pool.contains("anthropic"));
        assertEquals(AvailableProviderPool.RemovalSource.INIT_PROBE,
                pool.snapshot().get("anthropic").source());
    }

    @Test
    @DisplayName("Strategy throwing is treated as a probe failure (no startup crash)")
    void strategyThrowsHandledAsFailure() {
        configure(List.of(provider("openai", ModelProtocol.OPENAI_COMPATIBLE)), id -> true);

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of(
                stub(ModelProtocol.OPENAI_COMPATIBLE, p -> {
                    throw new RuntimeException("network down");
                })));
        probe.probeAllConfigured();

        assertFalse(pool.contains("openai"),
                "a throwing strategy must not leave the provider falsely in-pool");
        assertNotNull(pool.snapshot().get("openai"));
    }

    @Test
    @DisplayName("No strategy registered for protocol: fail-open (provider stays in pool)")
    void missingStrategyFailsOpen() {
        configure(List.of(provider("gemini", ModelProtocol.GEMINI_NATIVE)), id -> true);

        // Empty strategy list — no GEMINI_NATIVE handler.
        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of());
        probe.probeAllConfigured();

        assertTrue(pool.contains("gemini"),
                "without a probe strategy we must default to in-pool, not block chat");
    }

    @Test
    @DisplayName("No configured providers: probe is a no-op, pool stays empty")
    void emptyConfigurationIsNoOp() {
        configure(List.of(), id -> false);

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of(
                stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.ok(0))));
        probe.probeAllConfigured();

        assertTrue(pool.snapshot().isEmpty());
    }

    @Test
    @DisplayName("Unconfigured providers are skipped (not probed and not added)")
    void unconfiguredSkipped() {
        ModelProviderEntity openai = provider("openai", ModelProtocol.OPENAI_COMPATIBLE);
        ModelProviderEntity anthropic = provider("anthropic", ModelProtocol.ANTHROPIC_MESSAGES);
        // mapper returns both, but only openai is "configured"
        when(mapper.selectList(any())).thenReturn(List.of(openai, anthropic));
        when(providerService.isProviderConfigured("openai")).thenReturn(true);
        when(providerService.isProviderConfigured("anthropic")).thenReturn(false);

        AtomicInteger anthropicCalls = new AtomicInteger();
        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of(
                stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.ok(0)),
                stub(ModelProtocol.ANTHROPIC_MESSAGES, p -> {
                    anthropicCalls.incrementAndGet();
                    return ProbeResult.ok(0);
                })));
        probe.probeAllConfigured();

        assertTrue(pool.contains("openai"));
        assertFalse(pool.contains("anthropic"));
        assertEquals(0, anthropicCalls.get(),
                "unconfigured providers must not even be probed");
    }

    @Test
    @DisplayName("probeOne(unknown) returns failure and does not pollute pool")
    void probeOneUnknownProvider() {
        when(mapper.selectById(anyString())).thenReturn(null);

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of());
        ProbeResult r = probe.probeOne("ghost");

        assertFalse(r.success());
        assertTrue(pool.snapshot().isEmpty());
    }

    @Test
    @DisplayName("probeOne(unconfigured) HARD-removes from pool")
    void probeOneUnconfigured() {
        when(mapper.selectById("openai")).thenReturn(provider("openai", ModelProtocol.OPENAI_COMPATIBLE));
        when(providerService.isProviderConfigured("openai")).thenReturn(false);

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of());
        ProbeResult r = probe.probeOne("openai");

        assertFalse(r.success());
        assertFalse(pool.contains("openai"));
        assertEquals(AvailableProviderPool.RemovalSource.INIT_PROBE,
                pool.snapshot().get("openai").source());
    }

    @Test
    @DisplayName("probeOne(healthy) re-adds previously-removed provider to pool")
    void probeOneRecoversRemovedProvider() {
        when(mapper.selectById("openai")).thenReturn(provider("openai", ModelProtocol.OPENAI_COMPATIBLE));
        when(providerService.isProviderConfigured("openai")).thenReturn(true);

        // Pre-remove openai to simulate a HARD-error eviction.
        pool.remove("openai", AvailableProviderPool.RemovalSource.AUTH_ERROR, "401");
        assertFalse(pool.contains("openai"));

        ProviderInitProbe probe = new ProviderInitProbe(mapper, providerService, pool, List.of(
                stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.ok(5))));
        ProbeResult r = probe.probeOne("openai");

        assertTrue(r.success());
        assertTrue(pool.contains("openai"),
                "a successful reprobe must rehabilitate a previously removed provider");
    }

    @Test
    @DisplayName("Duplicate strategy for same protocol fails-fast at construction")
    void duplicateStrategyRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new ProviderInitProbe(mapper, providerService, pool, List.of(
                        stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.ok(0)),
                        stub(ModelProtocol.OPENAI_COMPATIBLE, p -> ProbeResult.ok(0)))));
        assertTrue(ex.getMessage().contains("OPENAI_COMPATIBLE"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static ModelProviderEntity provider(String id, ModelProtocol protocol) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setChatModel(protocol.getChatModelClass());
        p.setApiKey("sk-test");
        p.setBaseUrl("https://example.com");
        // RFC-074: probe filters out enabled=false rows. The pre-RFC-074 default
        // for these test fixtures was "everything participates" — preserve that.
        p.setEnabled(true);
        return p;
    }

    /** Wires the mapper and service so {@code listConfiguredProviders()} returns the given list,
     *  filtered through {@code configuredPredicate}. */
    private void configure(List<ModelProviderEntity> all, Function<String, Boolean> configuredPredicate) {
        when(mapper.selectList(any())).thenReturn(all);
        Map<String, Boolean> map = new HashMap<>();
        for (ModelProviderEntity p : all) {
            map.put(p.getProviderId(), configuredPredicate.apply(p.getProviderId()));
        }
        when(providerService.isProviderConfigured(anyString()))
                .thenAnswer(inv -> map.getOrDefault(inv.<String>getArgument(0), false));
    }

    /** Lambda-driven fake of {@link ProviderProbeStrategy}. */
    private static ProviderProbeStrategy stub(ModelProtocol protocol,
                                              Function<ModelProviderEntity, ProbeResult> body) {
        return new ProviderProbeStrategy() {
            @Override public ModelProtocol supportedProtocol() { return protocol; }
            @Override public ProbeResult probe(ModelProviderEntity provider) { return body.apply(provider); }
        };
    }
}
