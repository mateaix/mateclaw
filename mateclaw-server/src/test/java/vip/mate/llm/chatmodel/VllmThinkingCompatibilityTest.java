package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real Spring AI HTTP/SSE boundary, including fragmented UTF-8 events. */
class VllmThinkingCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LinkedBlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private org.springframework.ai.openai.api.OpenAiApi api;
    private volatile String response;
    private volatile boolean streaming = true;

    @BeforeEach
    void start() throws Exception {
        response = "data: " + chunk("\"content\":\"answer\"", "stop") + "\n\ndata: [DONE]\n\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (int offset = 0; offset < bytes.length; offset += 7) {
                    out.write(bytes, offset, Math.min(7, bytes.length - offset));
                    out.flush();
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        ThinkingLevelHolder.clear();
        server.stop(0);
    }

    private ChatModel model(String providerId, Map<String, Object> kwargs) {
        return model(providerId, kwargs, false);
    }

    private ChatModel model(String providerId, Map<String, Object> kwargs, boolean apiOnly) {
        var service = mock(ModelProviderService.class);
        when(service.isProviderConfigured(providerId)).thenReturn(true);
        var provider = new ModelProviderEntity();
        provider.setProviderId(providerId);
        provider.setRequireApiKey(false);
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        when(service.readProviderGenerateKwargs(provider)).thenReturn(kwargs);
        var beans = new StaticListableBeanFactory();
        beans.addBean("rest", RestClient.builder());
        // The application enables this restricted header at process startup; this
        // fixture only tests payload compatibility and does not run that bootstrap.
        beans.addBean("web", WebClient.builder().filter((request, next) -> next.exchange(
                org.springframework.web.reactive.function.client.ClientRequest.from(request)
                        .headers(headers -> headers.remove("Connection")).build())));
        beans.addBean("observations", ObservationRegistry.NOOP);
        var builder = new OpenAiCompatibleChatModelBuilder(service,
                beans.getBeanProvider(RestClient.Builder.class), beans.getBeanProvider(WebClient.Builder.class),
                beans.getBeanProvider(ObservationRegistry.class));
        var config = new ModelConfigEntity();
        config.setModelName("sdhs-model"); // A custom alias must not hide vLLM capabilities.
        config.setMaxTokens(256);
        if (apiOnly) {
            api = builder.buildOpenAiApi(provider, null);
            return null;
        }
        return builder.build(config, provider, RetryTemplate.builder().maxAttempts(1).build());
    }

    private static String chunk(String delta, String finish) {
        return "{\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"sdhs-model\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"," + delta
                + "},\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}";
    }

    @Test
    void streamAcceptsBothReasoningFieldsWithoutChangingContent() {
        response = ": heartbeat\r\n\r\ndata: " + chunk("\"reasoning\":\"推理一\"", null)
                + "\r\n\r\ndata: " + chunk("\"reasoning_content\":\"推理二\"", null)
                + "\n\ndata: " + chunk("\"content\":\"literal reasoning and reasoning_content\"", "stop")
                + "\n\ndata: [DONE]\n\n";
        var responses = model("vllm", Map.of()).stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
        String reasoning = responses.stream().map(r -> r.getResult().getOutput().getMetadata().get("reasoningContent"))
                .filter(java.util.Objects::nonNull).map(Object::toString).reduce("", String::concat);
        assertEquals("推理一推理二", reasoning);
        assertEquals("literal reasoning and reasoning_content", responses.stream()
                .map(r -> r.getResult().getOutput().getText()).filter(java.util.Objects::nonNull).reduce("", String::concat));
    }

    @Test
    void blockingCallAcceptsReasoningAlias() {
        streaming = false;
        response = "{\"id\":\"test\",\"created\":1,\"model\":\"sdhs-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"answer\",\"reasoning\":\"分析\"},\"finish_reason\":\"stop\"}]}";
        model("vllm", Map.of(), true);
        var result = api.chatCompletionEntity(new org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest(List.of(), false));
        assertEquals("分析", result.getBody().choices().getFirst().message().reasoningContent());
        assertEquals("answer", result.getBody().choices().getFirst().message().content());
    }

    @Test
    void switchIsPerRequestAndPreservesTemplateDefaultsAndRuntimeOptions() throws Exception {
        var model = model("vllm", Map.of("chat_template_kwargs", Map.of("enable_thinking", true, "custom", "keep")));
        var options = OpenAiChatOptions.builder().temperature(0.3).extraBody(Map.of("top_k", 20)).build();
        ThinkingLevelHolder.set("off");
        var stream = model.stream(new Prompt("hi", options));
        ThinkingLevelHolder.clear(); // Capture before a subscription moves to a Reactor worker.
        stream.collectList().block(Duration.ofSeconds(10));
        var off = requests.poll(1, TimeUnit.SECONDS);
        assertEquals(false, off.at("/chat_template_kwargs/enable_thinking").booleanValue());
        assertEquals("keep", off.at("/chat_template_kwargs/custom").asText());
        assertEquals(20, off.path("top_k").asInt());
        assertEquals(0.3, off.path("temperature").asDouble());
        assertFalse(off.has("reasoning_effort"));
        ThinkingLevelHolder.set("high");
        model.stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
        var on = requests.poll(1, TimeUnit.SECONDS);
        assertTrue(on.at("/chat_template_kwargs/enable_thinking").booleanValue());
        assertEquals(Map.of("top_k", 20), options.getExtraBody());
        assertEquals(true, ((Map<?, ?>) ((OpenAiChatOptions) model.getDefaultOptions()).getExtraBody()
                .get("chat_template_kwargs")).get("enable_thinking"));
    }

    @Test
    void unspecifiedLevelPreservesExplicitProviderDefault() throws Exception {
        model("vllm", Map.of("chat_template_kwargs", Map.of("enable_thinking", false)))
                .stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
        assertFalse(requests.poll(1, TimeUnit.SECONDS).at("/chat_template_kwargs/enable_thinking").booleanValue());
    }

    @Test
    void otherProvidersDoNotReceiveVllmOptions() throws Exception {
        ThinkingLevelHolder.set("off");
        model("azure", Map.of()).stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
        assertFalse(requests.poll(1, TimeUnit.SECONDS).has("chat_template_kwargs"));
    }
    @Test
    void explicitTemplateSwitchOptsCustomProviderIn() throws Exception {
        ThinkingLevelHolder.set("off");
        model("local-inference", Map.of("chat_template_kwargs", Map.of("enable_thinking", true)))
                .stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
        var sent = requests.poll(1, TimeUnit.SECONDS);
        assertTrue(sent.at("/chat_template_kwargs/enable_thinking").isBoolean());
        assertFalse(sent.at("/chat_template_kwargs/enable_thinking").booleanValue());
    }

    @Test
    void vllmWithoutTemplateDefaultsReceivesExplicitOff() throws Exception {
        ThinkingLevelHolder.set("off");
        model("vllm", Map.of()).stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
        var sent = requests.poll(1, TimeUnit.SECONDS);
        assertTrue(sent.at("/chat_template_kwargs/enable_thinking").isBoolean());
        assertFalse(sent.at("/chat_template_kwargs/enable_thinking").booleanValue());
    }

    @Test
    void canonicalReasoningWinsAndStructuredToolArgumentsAreUntouched() throws Exception {
        // Build arguments as JSON rather than relying on nested Java/JSON escaping.
        var root = json.createObjectNode();
        var delta = root.putArray("choices").addObject().putObject("delta");
        delta.put("reasoning", "alias");
        delta.put("reasoning_content", "canonical");
        delta.putArray("tool_calls").addObject().putObject("function")
                .put("arguments", "{reasoning: keep}");
        assertEquals(root.toString(), OpenAiReasoningResponseNormalizer.normalize(root.toString()));
        delta.remove("reasoning_content");
        JsonNode normalized = json.readTree(OpenAiReasoningResponseNormalizer.normalize(root.toString()));
        assertEquals("alias", normalized.at("/choices/0/delta/reasoning_content").asText());
        assertEquals("{reasoning: keep}", normalized.at("/choices/0/delta/tool_calls/0/function/arguments").asText());
    }

    @Test
    void genericToolCallingOptionsSurviveThinkingSwitch() throws Exception {
        var callback = mock(org.springframework.ai.tool.ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("lookup").description("lookup a value").inputSchema("{} ").build());
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(callback)).internalToolExecutionEnabled(false).build();
        ThinkingLevelHolder.set("off");
        model("vllm", Map.of()).stream(new Prompt("hi", options)).collectList().block(Duration.ofSeconds(10));
        var sent = requests.poll(1, TimeUnit.SECONDS);
        assertEquals("lookup", sent.at("/tools/0/function/name").asText());
    }

    @Test
    void streamedToolCallFragmentsSurviveReasoningNormalization() throws Exception {
        var first = json.readTree(chunk("\"reasoning\":\"planning\"", null));
        var firstDelta = (com.fasterxml.jackson.databind.node.ObjectNode) first.at("/choices/0/delta");
        var firstTool = firstDelta.putArray("tool_calls").addObject();
        firstTool.put("index", 0).put("id", "call-1").put("type", "function");
        firstTool.putObject("function").put("name", "lookup").put("arguments", "{\"reasoning\":");
        var second = json.readTree(chunk("\"content\":null", "tool_calls"));
        var secondDelta = (com.fasterxml.jackson.databind.node.ObjectNode) second.at("/choices/0/delta");
        secondDelta.putArray("tool_calls").addObject().put("index", 0)
                .putObject("function").put("arguments", "\"keep\"}");
        response = "data: " + first + "\n\ndata: " + second + "\n\ndata: [DONE]\n\n";
        var result = model("vllm", Map.of()).stream(new Prompt("hi")).collectList().block(Duration.ofSeconds(10));
        var calls = result.stream().flatMap(r -> r.getResult().getOutput().getToolCalls().stream()).toList();
        assertEquals(1, calls.size());
        assertEquals("lookup", calls.getFirst().name());
        assertEquals("keep", json.readTree(calls.getFirst().arguments()).path("reasoning").asText());
    }

}
