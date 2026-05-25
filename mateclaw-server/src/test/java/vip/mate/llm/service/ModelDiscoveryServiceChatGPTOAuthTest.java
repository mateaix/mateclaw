package vip.mate.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelInfoDTO;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.oauth.OpenAIOAuthService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for ChatGPT OAuth model discovery — the only protocol where we
 * call a separate endpoint with the user's OAuth bearer token instead of an
 * API key. Lower-protocol behaviour (filter, probe, dedupe) is exercised by
 * the rest of {@link ModelDiscoveryService} indirectly and out of scope here.
 */
class ModelDiscoveryServiceChatGPTOAuthTest {

    private ModelDiscoveryService service;
    private OpenAIOAuthService oauthService;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        ModelProviderService providerService = mock(ModelProviderService.class);
        ModelConfigService configService = mock(ModelConfigService.class);
        oauthService = mock(OpenAIOAuthService.class);
        when(oauthService.ensureValidAccessToken()).thenReturn("test-access-token");
        when(configService.listModelsByProvider(any())).thenReturn(List.of());

        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId("openai-chatgpt");
        provider.setChatModel("ChatGPTChatModel");
        provider.setSupportModelDiscovery(true);
        when(providerService.getProviderConfig("openai-chatgpt")).thenReturn(provider);

        service = new ModelDiscoveryService(providerService, configService,
                new ObjectMapper(), oauthService);

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service.setChatgptCodexClient(builder.build());
    }

    // ---------------------------------------------------------------------
    // parseChatGPTCodexModelsResponse — pure parsing tests
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("parser drops supported_in_api=false and visibility=hide entries")
    void parser_dropsHiddenAndUnsupported() {
        String body = "{\"models\":["
                + "{\"slug\":\"gpt-5.4\",\"supported_in_api\":true,\"visibility\":\"shown\",\"priority\":10},"
                + "{\"slug\":\"gpt-internal\",\"supported_in_api\":false,\"priority\":5},"
                + "{\"slug\":\"gpt-research\",\"supported_in_api\":true,\"visibility\":\"hide\",\"priority\":1},"
                + "{\"slug\":\"gpt-5.4-mini\",\"supported_in_api\":true,\"visibility\":\"shown\",\"priority\":20}"
                + "]}";

        List<ModelInfoDTO> models = service.parseChatGPTCodexModelsResponse(body);
        List<String> ids = models.stream().map(ModelInfoDTO::getId).toList();

        assertEquals(List.of("gpt-5.4", "gpt-5.4-mini"), ids);
    }

    @Test
    @DisplayName("parser sorts by priority ascending")
    void parser_sortsByPriority() {
        String body = "{\"models\":["
                + "{\"slug\":\"third\",\"supported_in_api\":true,\"priority\":30},"
                + "{\"slug\":\"first\",\"supported_in_api\":true,\"priority\":1},"
                + "{\"slug\":\"second\",\"supported_in_api\":true,\"priority\":15}"
                + "]}";

        List<String> ids = service.parseChatGPTCodexModelsResponse(body)
                .stream().map(ModelInfoDTO::getId).toList();
        assertEquals(List.of("first", "second", "third"), ids);
    }

    @Test
    @DisplayName("parser tolerates missing or non-list bodies")
    void parser_tolerantOfBadInput() {
        assertTrue(service.parseChatGPTCodexModelsResponse(null).isEmpty());
        assertTrue(service.parseChatGPTCodexModelsResponse("").isEmpty());
        assertTrue(service.parseChatGPTCodexModelsResponse("{}").isEmpty());
        assertTrue(service.parseChatGPTCodexModelsResponse("{\"models\": \"not-a-list\"}").isEmpty());
        assertTrue(service.parseChatGPTCodexModelsResponse("not-json").isEmpty());
    }

    // ---------------------------------------------------------------------
    // addChatGPTForwardCompatModels — the synthesis layer
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("forward-compat synthesizes gpt-5.5 when only gpt-5.4 is exposed")
    void forwardCompat_synthesizesGpt55FromGpt54() {
        List<ModelInfoDTO> input = List.of(new ModelInfoDTO("gpt-5.4", "gpt-5.4"));
        List<String> out = ModelDiscoveryService.addChatGPTForwardCompatModels(input)
                .stream().map(ModelInfoDTO::getId).toList();
        assertTrue(out.contains("gpt-5.5"), "Expected gpt-5.5 to be appended; got " + out);
        assertTrue(out.contains("gpt-5.4"));
    }

    @Test
    @DisplayName("forward-compat does not duplicate slugs already in the input")
    void forwardCompat_noDuplicates() {
        List<ModelInfoDTO> input = List.of(
                new ModelInfoDTO("gpt-5.5", "gpt-5.5"),
                new ModelInfoDTO("gpt-5.4", "gpt-5.4"));
        List<String> out = ModelDiscoveryService.addChatGPTForwardCompatModels(input)
                .stream().map(ModelInfoDTO::getId).toList();
        assertEquals(1, out.stream().filter("gpt-5.5"::equals).count());
        assertEquals(1, out.stream().filter("gpt-5.4"::equals).count());
    }

    @Test
    @DisplayName("forward-compat is a no-op when no template ancestor is present")
    void forwardCompat_noOpOnEmptyOrUnrelated() {
        List<String> empty = ModelDiscoveryService.addChatGPTForwardCompatModels(List.of())
                .stream().map(ModelInfoDTO::getId).toList();
        assertTrue(empty.isEmpty());

        List<String> unrelated = ModelDiscoveryService.addChatGPTForwardCompatModels(
                        List.of(new ModelInfoDTO("gpt-3.5", "gpt-3.5")))
                .stream().map(ModelInfoDTO::getId).toList();
        assertEquals(List.of("gpt-3.5"), unrelated);
    }

    // ---------------------------------------------------------------------
    // discoverModels — end-to-end through the OAuth path
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("discoverModels sends Bearer token and returns sorted+forward-compat catalog")
    void discoverModels_endToEnd() {
        mockServer.expect(requestTo(ModelDiscoveryService.CHATGPT_CODEX_MODELS_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
                .andRespond(withSuccess(
                        "{\"models\":["
                                + "{\"slug\":\"gpt-5.4\",\"supported_in_api\":true,\"priority\":10},"
                                + "{\"slug\":\"gpt-5.4-mini\",\"supported_in_api\":true,\"priority\":20},"
                                + "{\"slug\":\"gpt-internal\",\"supported_in_api\":false,\"priority\":5}"
                                + "]}",
                        MediaType.APPLICATION_JSON));

        var result = service.discoverModels("openai-chatgpt");
        List<String> all = result.getDiscoveredModels().stream().map(ModelInfoDTO::getId).toList();

        // priority-sorted real models, plus gpt-5.5 synthesised by forward-compat
        assertEquals(List.of("gpt-5.4", "gpt-5.4-mini", "gpt-5.5"), all);
        verify(oauthService).ensureValidAccessToken();
        mockServer.verify();
    }

    @Test
    @DisplayName("discoverModels surfaces fetch failures as err.llm.chatgpt_models_fetch_failed")
    void discoverModels_surfacesFetchFailure() {
        mockServer.expect(requestTo(ModelDiscoveryService.CHATGPT_CODEX_MODELS_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.discoverModels("openai-chatgpt"));
        assertEquals("err.llm.chatgpt_models_fetch_failed", ex.getMsgKey());
    }

    @Test
    @DisplayName("discoverModels propagates oauth_not_connected from OpenAIOAuthService unchanged")
    void discoverModels_propagatesOauthNotConnected() {
        when(oauthService.ensureValidAccessToken())
                .thenThrow(new MateClawException("err.llm.oauth_not_connected", "未连接"));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.discoverModels("openai-chatgpt"));
        assertEquals("err.llm.oauth_not_connected", ex.getMsgKey());
    }
}
