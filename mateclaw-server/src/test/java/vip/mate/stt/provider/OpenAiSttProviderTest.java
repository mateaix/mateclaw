package vip.mate.stt.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.stt.SttRequest;
import vip.mate.stt.SttResult;
import vip.mate.stt.SttTransportConfig;
import vip.mate.stt.transport.OpenAiCompatibleSttTransport;
import vip.mate.system.model.SystemSettingsDTO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Issue #76: covers the credential-routing thin-wrapper logic — the actual
 * wire transport is exercised separately by {@code SttServiceTest} and the
 * transport's own pure-logic test.
 */
class OpenAiSttProviderTest {

    private ModelProviderService modelProviderService;
    private OpenAiCompatibleSttTransport transport;
    private OpenAiSttProvider provider;

    @BeforeEach
    void setUp() {
        modelProviderService = mock(ModelProviderService.class);
        transport = mock(OpenAiCompatibleSttTransport.class);
        provider = new OpenAiSttProvider(modelProviderService, transport);
    }

    @Test
    @DisplayName("Default config routes to id=openai with whisper-1 (legacy compatibility)")
    void defaultsToLegacyOpenai() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        // Both fields null — the provider should fall back to the legacy defaults.
        ModelProviderEntity entity = providerRow("openai", "https://api.openai.com", "sk-test", true);
        when(modelProviderService.getProviderConfig("openai")).thenReturn(entity);
        when(transport.transcribe(any(), any())).thenReturn(SttResult.success("ok"));

        SttResult result = provider.transcribe(req(), config);

        assertTrue(result.isSuccess());
        ArgumentCaptor<SttTransportConfig> captor = ArgumentCaptor.forClass(SttTransportConfig.class);
        verify(transport).transcribe(any(), captor.capture());
        SttTransportConfig sent = captor.getValue();
        assertEquals("https://api.openai.com", sent.baseUrl());
        assertEquals("sk-test", sent.apiKey());
        assertEquals("whisper-1", sent.model());
    }

    @Test
    @DisplayName("Issue #76: configured providerId routes to that row's baseUrl + key")
    void honoursConfiguredProviderId() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSttOpenAiCompatProviderId("funasr-internal");
        config.setSttOpenAiCompatModel("paraformer-large");
        ModelProviderEntity entity = providerRow("funasr-internal",
                "http://10.0.0.5:9999/v1", "internal-token", false);
        when(modelProviderService.getProviderConfig("funasr-internal")).thenReturn(entity);
        when(transport.transcribe(any(), any())).thenReturn(SttResult.success("hello"));

        SttResult result = provider.transcribe(req(), config);

        assertTrue(result.isSuccess());
        ArgumentCaptor<SttTransportConfig> captor = ArgumentCaptor.forClass(SttTransportConfig.class);
        verify(transport).transcribe(any(), captor.capture());
        SttTransportConfig sent = captor.getValue();
        assertEquals("http://10.0.0.5:9999/v1", sent.baseUrl());
        assertEquals("internal-token", sent.apiKey());
        assertEquals("paraformer-large", sent.model());
    }

    @Test
    @DisplayName("requireApiKey=false provider with blank key still goes through (self-hosted FunASR)")
    void allowsBlankKeyWhenProviderDoesNotRequireOne() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSttOpenAiCompatProviderId("funasr-noauth");
        ModelProviderEntity entity = providerRow("funasr-noauth",
                "http://10.0.0.5:9999/v1", "", false);
        when(modelProviderService.getProviderConfig("funasr-noauth")).thenReturn(entity);
        when(transport.transcribe(any(), any())).thenReturn(SttResult.success("ok"));

        SttResult result = provider.transcribe(req(), config);

        assertTrue(result.isSuccess());
        verify(transport).transcribe(any(), any());
    }

    @Test
    @DisplayName("requireApiKey=true provider with blank key fails fast with actionable message")
    void rejectsBlankKeyWhenRequired() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSttOpenAiCompatProviderId("openai");
        ModelProviderEntity entity = providerRow("openai", "https://api.openai.com", "", true);
        when(modelProviderService.getProviderConfig("openai")).thenReturn(entity);

        SttResult result = provider.transcribe(req(), config);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("openai"));
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("Unknown providerId surfaces a typed failure instead of leaking the underlying exception")
    void missingProviderIsSurfacedAsTypedFailure() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSttOpenAiCompatProviderId("does-not-exist");
        when(modelProviderService.getProviderConfig("does-not-exist"))
                .thenThrow(new MateClawException("err.llm.provider_not_found", "missing"));

        SttResult result = provider.transcribe(req(), config);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("does-not-exist"));
        verifyNoInteractions(transport);
    }

    @Test
    @DisplayName("Empty baseUrl on provider row falls back to https://api.openai.com")
    void emptyBaseUrlFallsBackToOpenAiDefault() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        ModelProviderEntity entity = providerRow("openai", "", "sk-test", true);
        when(modelProviderService.getProviderConfig("openai")).thenReturn(entity);
        when(transport.transcribe(any(), any())).thenReturn(SttResult.success("ok"));

        provider.transcribe(req(), config);

        ArgumentCaptor<SttTransportConfig> captor = ArgumentCaptor.forClass(SttTransportConfig.class);
        verify(transport).transcribe(any(), captor.capture());
        assertEquals("https://api.openai.com", captor.getValue().baseUrl());
    }

    @Test
    @DisplayName("isAvailable defers to the configured provider row, not hard-coded \"openai\"")
    void isAvailableHonoursConfiguredProviderId() {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSttOpenAiCompatProviderId("siliconflow");
        when(modelProviderService.isProviderConfigured("siliconflow")).thenReturn(true);

        assertTrue(provider.isAvailable(config));
        verify(modelProviderService).isProviderConfigured("siliconflow");
        verify(modelProviderService, never()).isProviderConfigured("openai");
    }

    private static ModelProviderEntity providerRow(String id, String baseUrl, String apiKey, boolean requireApiKey) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setRequireApiKey(requireApiKey);
        return p;
    }

    private static SttRequest req() {
        return SttRequest.builder()
                .audioData(new byte[]{1, 2, 3})
                .fileName("a.wav")
                .contentType("audio/wav")
                .build();
    }
}
