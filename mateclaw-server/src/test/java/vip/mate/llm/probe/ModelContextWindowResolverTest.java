package vip.mate.llm.probe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelContextWindowResolver} — priority order,
 * caching, disabled flag, and error-text reconciliation.
 */
class ModelContextWindowResolverTest {

    private ContextProbeProperties properties;
    private AtomicInteger probeCalls;

    @BeforeEach
    void setUp() {
        properties = new ContextProbeProperties();
        probeCalls = new AtomicInteger();
    }

    private LocalContextProbe fixedProbe(Integer value) {
        return new LocalContextProbe() {
            @Override
            public boolean supports(ModelProviderEntity provider, ModelConfigEntity model) {
                return true;
            }

            @Override
            public Optional<Integer> probeContextLength(ModelProviderEntity provider, ModelConfigEntity model) {
                probeCalls.incrementAndGet();
                return Optional.ofNullable(value);
            }
        };
    }

    private static ModelProviderEntity provider(String id) {
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId(id);
        return provider;
    }

    private static ModelProviderEntity cloudProvider(String id) {
        ModelProviderEntity provider = provider(id);
        provider.setBaseUrl("https://api.deepseek.com");
        return provider;
    }

    private static ModelConfigEntity model(String name, Integer maxInputTokens) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setModelName(name);
        model.setMaxInputTokens(maxInputTokens);
        return model;
    }

    @Test
    @DisplayName("explicit maxInputTokens always wins — probe never runs")
    void explicitConfigWins() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(fixedProbe(16384)), properties);
        Integer resolved = resolver.resolveMaxInputTokens(provider("ollama"), model("m", 128000));
        assertEquals(128000, resolved);
        assertEquals(0, probeCalls.get());
    }

    @Test
    @DisplayName("no explicit config → probed value used and cached")
    void probeFillsGapAndCaches() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(fixedProbe(16384)), properties);
        assertEquals(16384, resolver.resolveMaxInputTokens(provider("ollama"), model("m", null)));
        assertEquals(16384, resolver.resolveMaxInputTokens(provider("ollama"), model("m", 0)));
        assertEquals(1, probeCalls.get(), "second call must hit the cache");
    }

    @Test
    @DisplayName("probe miss is negative-cached — the endpoint is not hammered")
    void negativeCache() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(fixedProbe(null)), properties);
        assertNull(resolver.resolveMaxInputTokens(provider("ollama"), model("m", null)));
        assertNull(resolver.resolveMaxInputTokens(provider("ollama"), model("m", null)));
        assertEquals(1, probeCalls.get());
    }

    @Test
    @DisplayName("disabled → null without probing")
    void disabledSkipsProbing() {
        properties.setEnabled(false);
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(fixedProbe(16384)), properties);
        assertNull(resolver.resolveMaxInputTokens(provider("ollama"), model("m", null)));
        assertEquals(0, probeCalls.get());
    }

    @Test
    @DisplayName("a probe that throws is skipped, not fatal")
    void throwingProbeIsSkipped() {
        LocalContextProbe throwing = new LocalContextProbe() {
            @Override
            public boolean supports(ModelProviderEntity provider, ModelConfigEntity model) {
                return true;
            }

            @Override
            public Optional<Integer> probeContextLength(ModelProviderEntity provider, ModelConfigEntity model) {
                throw new IllegalStateException("boom");
            }
        };
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(throwing, fixedProbe(8192)), properties);
        assertEquals(8192, resolver.resolveMaxInputTokens(provider("ollama"), model("m", null)));
    }

    @Test
    @DisplayName("context-limit error text seeds the cache for later turns")
    void errorTextReconciliation() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(), properties);
        resolver.noteContextLimitError("vllm-local", "m",
                "Input prompt (40000 tokens) exceeds the max_model_len 32768");
        assertEquals(32768, resolver.resolveMaxInputTokens(provider("vllm-local"), model("m", null)));
    }

    @Test
    @DisplayName("unparseable error text changes nothing")
    void unparseableErrorIgnored() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(), properties);
        resolver.noteContextLimitError("p", "m", "connection refused");
        assertNull(resolver.resolveMaxInputTokens(provider("p"), model("m", null)));
    }

    @Test
    @DisplayName("cloud model with no config falls back to the built-in window table")
    void catalogFillsCloudModels() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(), properties);
        assertEquals(1_000_000,
                resolver.resolveMaxInputTokens(cloudProvider("deepseek"), model("deepseek-v4-pro", null)));
        assertEquals(128_000,
                resolver.resolveMaxInputTokens(cloudProvider("deepseek"), model("deepseek-chat", 0)));
    }

    @Test
    @DisplayName("catalog applies with probing disabled — it costs no request")
    void catalogWorksWhenProbingDisabled() {
        properties.setEnabled(false);
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(fixedProbe(16384)), properties);
        assertEquals(1_000_000,
                resolver.resolveMaxInputTokens(cloudProvider("deepseek"), model("deepseek-v4-flash", null)));
        assertEquals(0, probeCalls.get());
    }

    @Test
    @DisplayName("self-hosted endpoints skip the table — only the real server knows its window")
    void catalogSkippedForLocalEndpoints() {
        ModelProviderEntity local = provider("lmstudio");
        local.setBaseUrl("http://127.0.0.1:1234");
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(), properties);
        assertNull(resolver.resolveMaxInputTokens(local, model("deepseek-v4-pro", null)));
        assertNull(resolver.resolveMaxInputTokens(provider("ollama"), model("deepseek-r1:latest", null)));
    }

    @Test
    @DisplayName("a limit parsed from a provider error outranks the table")
    void errorTextOutranksCatalog() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(), properties);
        resolver.noteContextLimitError("deepseek", "deepseek-v4-pro",
                "This model's maximum context length is 65536 tokens");
        assertEquals(65536,
                resolver.resolveMaxInputTokens(cloudProvider("deepseek"), model("deepseek-v4-pro", null)));
    }

    @Test
    @DisplayName("an unknown model stays on the caller's global default")
    void unknownModelStaysNull() {
        ModelContextWindowResolver resolver =
                new ModelContextWindowResolver(List.of(), properties);
        assertNull(resolver.resolveMaxInputTokens(cloudProvider("acme"), model("acme-llm-1", null)));
    }
}
