package vip.mate.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.oauth.OpenAIOAuthService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real loopback HTTP exercises the public test and discovery paths without provider credentials. */
class ModelDiscoveryServiceProbeValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private HttpServer server;
    private ModelDiscoveryService service;
    private ModelProviderEntity provider;
    private ModelConfigService configService;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = "{\"data\":[{\"id\":\"probe-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        provider = new ModelProviderEntity();
        provider.setProviderId("loopback");
        provider.setChatModel("OpenAIChatModel");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setSupportModelDiscovery(false);
        var providers = mock(ModelProviderService.class);
        when(providers.getProviderConfig("loopback")).thenReturn(provider);
        when(providers.readProviderGenerateKwargs(provider)).thenReturn(Map.of());
        configService = mock(ModelConfigService.class);
        when(configService.listModelsByProvider("loopback")).thenReturn(List.of());
        service = new ModelDiscoveryService(providers, configService, mapper, mock(OpenAIOAuthService.class));
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "", "null", "[]", "{}", "{\"choices\":[]}",
            "{\"error\":{\"message\":\"private-provider-detail\"}}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"   \"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":42}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"reasoning_content\":\"private-provider-detail\",\"content\":\"\"}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"partial\"}}]}"
    })
    void rejectsMalformedOrEmptySuccessfulHttpResponses(String body) {
        response.set(body);
        var result = service.testModel("loopback", "probe-model");
        assertFalse(result.isSuccess(), body);
        assertNotNull(result.getErrorMessage());
        assertFalse(result.getErrorMessage().contains("private-provider-detail"));
        assertNull(result.getMessage(), "Never manufacture a successful reply");
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "tool_calls", "function_call", "unknown"})
    void rejectsIncompleteRepliesEvenWithText(String reason) {
        response.set(completion("partial", reason));
        var result = service.testModel("loopback", "probe-model");
        assertFalse(result.isSuccess(), reason);
        assertNotNull(result.getErrorMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_JSON", "{\"error\":\"failed\"}"})
    void rejectsTrailingContentAfterAValidCompletion(String suffix) {
        response.set(completion("连接正常", "stop") + suffix);
        assertFalse(service.testModel("loopback", "probe-model").isSuccess());
    }

    @Test
    void acceptsCompletedTextAndSendsBoundedBudget() {
        response.set(completion("连接正常", "stop"));
        var result = service.testModel("loopback", "probe-model");
        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("连接正常", result.getMessage());
        assertEquals(512, request.get().path("max_tokens").asInt());
        assertFalse(request.get().has("max_completion_tokens"));
    }

    @Test
    void rejectsErrorEnvelopeEvenWhenItContainsAChoice() {
        response.set("{\"error\":{\"message\":\"private-provider-detail\"}," + completion("ok", "stop").substring(1));
        var result = service.testModel("loopback", "probe-model");
        assertFalse(result.isSuccess());
        assertFalse(result.getErrorMessage().contains("private-provider-detail"));
    }

    @Test
    void connectionFallbackAlsoRejectsReasoningOnly() {
        var model = new ModelConfigEntity();
        model.setModelName("probe-model");
        when(configService.listModelsByProvider("loopback")).thenReturn(List.of(model));
        response.set(completion("", "length"));
        assertFalse(service.testConnection("loopback").isSuccess());
    }

    @Test
    void discoveryRetainsFailedProbeButDoesNotRecommendIt() {
        provider.setSupportModelDiscovery(true);
        response.set(completion("partial", "length"));
        var result = service.discoverModels("loopback");
        assertEquals(1, result.getTotalDiscovered());
        assertEquals(Boolean.FALSE, result.getDiscoveredModels().getFirst().getProbeOk());
        assertNotNull(result.getDiscoveredModels().getFirst().getProbeError());
        assertTrue(result.getNewModels().isEmpty());
    }

    private String completion(String content, String reason) {
        try {
            return mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", reason, "message", Map.of("role", "assistant", "content", content)))));
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
