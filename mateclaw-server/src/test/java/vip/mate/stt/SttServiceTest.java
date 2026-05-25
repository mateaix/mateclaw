package vip.mate.stt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SttService} — the dispatch + fallback orchestration.
 *
 * <p>Pre-fix behavior had two failure paths that were indistinguishable to
 * the user (both surfaced as "STT 不可用"):
 * <ol>
 *   <li>{@code sttEnabled=false} (the default) — no STT call ever attempted.</li>
 *   <li>No provider had API key configured — silent fallthrough to "no provider".</li>
 * </ol>
 * These tests pin the new behavior: distinct error messages, fallback engages
 * when configured, primary's error is preserved when fallback also fails.
 */
class SttServiceTest {

    private SystemSettingService systemSettingService;

    @BeforeEach
    void setUp() {
        systemSettingService = mock(SystemSettingService.class);
    }

    @Test
    @DisplayName("transcribe returns clear 'STT 未启用' when sttEnabled is false")
    void transcribe_returnsDisabledMessageWhenOff() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSttEnabled(false);
        when(systemSettingService.getAllSettings()).thenReturn(config);

        SttService svc = new SttService(systemSettingService, registryWith(/* providers */));
        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "audio.wav", "audio/wav", null);

        assertFalse((boolean) result.get("success"));
        assertTrue(result.get("error").toString().contains("未启用"),
                "User must see 'feature is off' rather than a generic provider error");
    }

    @Test
    @DisplayName("transcribe returns success from primary provider when it works")
    void transcribe_primarySuccess() {
        SystemSettingsDTO config = enabledConfig("auto", true);
        when(systemSettingService.getAllSettings()).thenReturn(config);

        StubProvider primary = new StubProvider("openai", 100, true, SttResult.success("hello world"));
        SttService svc = new SttService(systemSettingService, registryWith(primary));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        assertTrue((boolean) result.get("success"));
        assertEquals("hello world", result.get("text"));
        assertEquals(1, primary.callCount.get());
    }

    @Test
    @DisplayName("transcribe falls back to next provider when primary fails AND fallback is enabled")
    void transcribe_fallsBackWhenEnabled() {
        SystemSettingsDTO config = enabledConfig("auto", true);
        when(systemSettingService.getAllSettings()).thenReturn(config);

        StubProvider primary = new StubProvider("openai", 100, true, SttResult.failure("HTTP 500"));
        StubProvider fallback = new StubProvider("dashscope", 200, true, SttResult.success("叫我 fallback"));
        SttService svc = new SttService(systemSettingService, registryWith(primary, fallback));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        assertTrue((boolean) result.get("success"));
        assertEquals("叫我 fallback", result.get("text"));
        assertEquals(1, primary.callCount.get(), "primary must still have been tried first");
        assertEquals(1, fallback.callCount.get(), "fallback should kick in only after primary fails");
    }

    @Test
    @DisplayName("transcribe does NOT fall back when fallback is disabled")
    void transcribe_noFallbackWhenDisabled() {
        SystemSettingsDTO config = enabledConfig("auto", false);
        when(systemSettingService.getAllSettings()).thenReturn(config);

        StubProvider primary = new StubProvider("openai", 100, true, SttResult.failure("HTTP 500"));
        StubProvider candidate = new StubProvider("dashscope", 200, true, SttResult.success("never reached"));
        SttService svc = new SttService(systemSettingService, registryWith(primary, candidate));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        assertFalse((boolean) result.get("success"));
        assertEquals(0, candidate.callCount.get(), "fallback must NOT be tried when sttFallbackEnabled=false");
    }

    @Test
    @DisplayName("transcribe surfaces all failures when every provider rejects")
    void transcribe_allFailedAggregatesErrors() {
        SystemSettingsDTO config = enabledConfig("auto", true);
        when(systemSettingService.getAllSettings()).thenReturn(config);

        StubProvider p1 = new StubProvider("openai", 100, true, SttResult.failure("HTTP 401"));
        StubProvider p2 = new StubProvider("dashscope", 200, true, SttResult.failure("HTTP 400"));
        SttService svc = new SttService(systemSettingService, registryWith(p1, p2));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        String error = result.get("error").toString();
        assertFalse((boolean) result.get("success"));
        // Both provider IDs must appear so the operator can tell which API
        // keys are wrong without grep-ing the server log.
        assertTrue(error.contains("openai"), "aggregate error must mention every failed provider");
        assertTrue(error.contains("dashscope"));
        assertTrue(error.contains("HTTP 401"));
        assertTrue(error.contains("HTTP 400"));
    }

    @Test
    @DisplayName("transcribe returns actionable hint when no provider has a key configured")
    void transcribe_returnsActionableHintWhenNoProviderAvailable() {
        SystemSettingsDTO config = enabledConfig("auto", true);
        when(systemSettingService.getAllSettings()).thenReturn(config);

        // Neither provider available — the most common real-world failure
        // mode. Pre-fix this surfaced as a generic "no provider" with no
        // actionable hint pointing the user at the model-management page.
        StubProvider p1 = new StubProvider("openai", 100, false, null);
        StubProvider p2 = new StubProvider("dashscope", 200, false, null);
        SttService svc = new SttService(systemSettingService, registryWith(p1, p2));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        String error = result.get("error").toString();
        assertFalse((boolean) result.get("success"));
        assertTrue(error.contains("API Key") || error.contains("模型管理"),
                "error message must point the user at the API key configuration UI");
        assertEquals(0, p1.callCount.get());
        assertEquals(0, p2.callCount.get());
    }

    @Test
    @DisplayName("Chinese language hint pulls DashScope (Paraformer) above Whisper")
    void transcribe_chineseLanguagePrefersDashScope() {
        // Stub provider mirrors DashScopeSttProvider's real
        // autoDetectOrder(zh) so the routing test pins the actual numbers
        // we ship, not arbitrary values.
        SystemSettingsDTO config = enabledConfig("auto", true);
        config.setLanguage("zh-CN");
        when(systemSettingService.getAllSettings()).thenReturn(config);

        AtomicReference<String> calledFirst = new AtomicReference<>();
        StubProvider openai = recordingStub("openai", 100, calledFirst,
                p -> p.startsWith("zh") ? 250 : 100);   // mirrors OpenAiSttProvider
        StubProvider zhProvider = recordingStub("dashscope", 150, calledFirst,
                p -> p.startsWith("zh") ? 60 : 150);
        SttService svc = new SttService(systemSettingService, registryWith(openai, zhProvider));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        assertTrue((boolean) result.get("success"));
        assertEquals("dashscope", calledFirst.get(),
                "Chinese hint should put the dashscope provider ahead of Whisper");
    }

    @Test
    @DisplayName("English language hint keeps Whisper as primary")
    void transcribe_englishLanguagePrefersWhisper() {
        SystemSettingsDTO config = enabledConfig("auto", true);
        config.setLanguage("en-US");
        when(systemSettingService.getAllSettings()).thenReturn(config);

        AtomicReference<String> calledFirst = new AtomicReference<>();
        StubProvider openai = recordingStub("openai", 100, calledFirst,
                p -> p != null && p.startsWith("en") ? 80 : 100);
        StubProvider zhProvider = recordingStub("dashscope", 150, calledFirst,
                p -> p != null && p.startsWith("zh") ? 60 : 150);
        SttService svc = new SttService(systemSettingService, registryWith(openai, zhProvider));

        svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        assertEquals("openai", calledFirst.get(),
                "English hint should keep Whisper primary");
    }

    @Test
    @DisplayName("explicit per-call language hint overrides system-settings language")
    void transcribe_explicitLanguageOverridesSetting() {
        // System UI is English but the caller passes zh — the request-level
        // hint must win so a Chinese-speaking user inside an English UI still
        // gets the dashscope provider.
        SystemSettingsDTO config = enabledConfig("auto", true);
        config.setLanguage("en-US");
        when(systemSettingService.getAllSettings()).thenReturn(config);

        AtomicReference<String> calledFirst = new AtomicReference<>();
        StubProvider openai = recordingStub("openai", 100, calledFirst,
                p -> p != null && p.startsWith("zh") ? 250 : 80);
        StubProvider zhProvider = recordingStub("dashscope", 150, calledFirst,
                p -> p != null && p.startsWith("zh") ? 60 : 150);
        SttService svc = new SttService(systemSettingService, registryWith(openai, zhProvider));

        svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", "zh");

        assertEquals("dashscope", calledFirst.get(),
                "Per-call language must override system UI language for routing");
    }

    @Test
    @DisplayName("fallback list also respects language ordering")
    void transcribe_fallbackOrderRespectsLanguage() {
        // Three providers; primary fails. Verify the fallback we hit next is
        // the language-preferred one, not whatever default order picked. With
        // language=zh: dashscope=60, openai=250, fake=200 → fallback after
        // openai (forced primary) should pick dashscope before fake.
        SystemSettingsDTO config = enabledConfig("openai", true);  // pin openai as primary
        config.setLanguage("zh-CN");
        when(systemSettingService.getAllSettings()).thenReturn(config);

        StubProvider openai = new StubProvider("openai", 100, true, SttResult.failure("primary fail"));
        StubProvider zhProvider = new StubProvider("dashscope", 150, true, SttResult.success("from dashscope")) {
            @Override public int autoDetectOrder(String language) {
                return language != null && language.startsWith("zh") ? 60 : 150;
            }
        };
        StubProvider fake = new StubProvider("fake-cloud", 200, true, SttResult.success("from fake"));
        SttService svc = new SttService(systemSettingService, registryWith(openai, zhProvider, fake));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        assertTrue((boolean) result.get("success"));
        assertEquals("from dashscope", result.get("text"),
                "Chinese fallback must hit the dashscope provider before language-agnostic fallbacks");
    }

    @Test
    @DisplayName("explicit sttProvider selection overrides auto-detect order")
    void transcribe_explicitProviderOverridesOrder() {
        // User explicitly chose a non-default provider. Registry must honour
        // the explicit pick even when another provider has a lower
        // autoDetectOrder.
        SystemSettingsDTO config = enabledConfig("explicit-pick", true);
        when(systemSettingService.getAllSettings()).thenReturn(config);

        AtomicReference<String> calledFirst = new AtomicReference<>();
        StubProvider openai = new StubProvider("openai", 100, true, SttResult.success("from openai")) {
            @Override public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
                calledFirst.compareAndSet(null, id());
                return super.transcribe(request, config);
            }
        };
        StubProvider explicitPick = new StubProvider("explicit-pick", 200, true, SttResult.success("from explicit")) {
            @Override public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
                calledFirst.compareAndSet(null, id());
                return super.transcribe(request, config);
            }
        };
        SttService svc = new SttService(systemSettingService, registryWith(openai, explicitPick));

        Map<String, Object> result = svc.transcribe(new byte[]{1, 2, 3}, "a.wav", "audio/wav", null);

        assertTrue((boolean) result.get("success"));
        assertEquals("from explicit", result.get("text"));
        assertEquals("explicit-pick", calledFirst.get(), "explicit provider must run first");
    }

    /* --------------------------------- helpers --------------------------------- */

    private static SystemSettingsDTO enabledConfig(String provider, boolean fallback) {
        SystemSettingsDTO c = new SystemSettingsDTO();
        c.setSttEnabled(true);
        c.setSttProvider(provider);
        c.setSttFallbackEnabled(fallback);
        return c;
    }

    private static SttProviderRegistry registryWith(SttProvider... providers) {
        return new SttProviderRegistry(List.of(providers));
    }

    /**
     * Variant of {@link StubProvider} that records which stub got hit first
     * (so tests can assert ordering) and exposes a custom
     * {@link SttProvider#autoDetectOrder(String)} hook for the language-
     * routing tests. Returns a successful canned result so the call chain
     * doesn't try fallbacks unrelated to the test's intent.
     */
    private static StubProvider recordingStub(String id, int defaultOrder,
                                              AtomicReference<String> firstCalled,
                                              java.util.function.Function<String, Integer> langOrder) {
        return new StubProvider(id, defaultOrder, true, SttResult.success(id + ":ok")) {
            @Override
            public int autoDetectOrder(String language) {
                return langOrder.apply(language);
            }
            @Override
            public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
                firstCalled.compareAndSet(null, id());
                return super.transcribe(request, config);
            }
        };
    }

    /**
     * Test double: returns a canned result and counts invocations. Avoids
     * pulling in Mockito for the {@link SttProvider} interface — call
     * counting is the only behaviour these tests need.
     */
    private static class StubProvider implements SttProvider {
        private final String id;
        private final int order;
        private final boolean available;
        private final SttResult canned;
        final AtomicInteger callCount = new AtomicInteger();

        StubProvider(String id, int order, boolean available, SttResult canned) {
            this.id = id;
            this.order = order;
            this.available = available;
            this.canned = canned;
        }

        @Override public String id() { return id; }
        @Override public String label() { return id; }
        @Override public boolean requiresCredential() { return true; }
        @Override public int autoDetectOrder() { return order; }
        @Override public boolean isAvailable(SystemSettingsDTO config) { return available; }

        @Override
        public SttResult transcribe(SttRequest request, SystemSettingsDTO config) {
            callCount.incrementAndGet();
            assertNotNull(canned, "stub for " + id + " was called but no canned result was set");
            return canned;
        }
    }
}
