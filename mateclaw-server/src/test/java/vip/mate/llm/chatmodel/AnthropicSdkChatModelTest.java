package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.retry.support.RetryTemplate;

import vip.mate.llm.cache.AnthropicCacheOptionsFactory;
import vip.mate.llm.cache.CacheProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class AnthropicSdkChatModelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private AnthropicSdkChatModel model;
    private AnthropicChatOptions defaults;
    private String response;
    private int status = 200;
    private int frameDelayMillis;
    private String contentType = "application/json";
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> correlation = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    request.set(mapper.readTree(exchange.getRequestBody()));
                    apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    correlation.set(
                            exchange.getRequestHeaders().getFirst("X-MateClaw-Anthropic-Request"));
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.sendResponseHeaders(status, frameDelayMillis == 0 ? bytes.length : 0);
                    if (frameDelayMillis == 0) exchange.getResponseBody().write(bytes);
                    else {
                        for (String event : response.split("\n\n")) {
                            exchange.getResponseBody()
                                    .write((event + "\n\n").getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                            try {
                                Thread.sleep(frameDelayMillis);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    exchange.close();
                });
        server.start();
        defaults =
                AnthropicChatOptions.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .apiKey("test-key")
                        .model("claude-sonnet-4")
                        .maxTokens(100)
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(3))
                        .build();
        model = AnthropicSdkChatModel.create(defaults, ObservationRegistry.NOOP, Map.of(), false);
        response =
                """
{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-4",
"stop_reason":"tool_use","stop_sequence":null,"content":[
{"type":"thinking","thinking":"Reason carefully","signature":"signed-original"},
{"type":"redacted_thinking","data":"opaque-data"},
{"type":"tool_use","id":"tool-1","name":"echo","input":{"x":1}}],
"usage":{"input_tokens":20,"output_tokens":7,"cache_read_input_tokens":4,"cache_creation_input_tokens":2}}
""";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void runtimeOptionsWithoutModelKeepDefaultModelTimeoutAndSampling() {
        assertDoesNotThrow(
                () -> model.call(new Prompt("Hi", ChatOptions.builder().maxTokens(50).build())));
        assertEquals("claude-sonnet-4", request.get().path("model").asText());
        assertEquals(50, request.get().path("max_tokens").asInt());
    }

    @Test
    void signedAndRedactedThinkingRoundtripThroughJsonHistory() throws Exception {
        ChatResponse result = model.call(new Prompt("Hi"));
        assertEquals(1, result.getResults().size());
        assertEquals("tool-1", result.getResult().getOutput().getToolCalls().getFirst().id());
        Map<String, Object> metadata = result.getResult().getOutput().getMetadata();
        assertTrue(metadata.containsKey(AnthropicSdkChatModel.THINKING_BLOCKS));
        Map<String, Object> persisted =
                mapper.readValue(mapper.writeValueAsString(metadata), new TypeReference<>() {});
        AssistantMessage assistant =
                AssistantMessage.builder()
                        .content("")
                        .properties(persisted)
                        .toolCalls(result.getResult().getOutput().getToolCalls())
                        .build();
        ToolResponseMessage tools =
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "tool-1", "echo", "1")))
                        .build();
        model.call(new Prompt(List.of(new UserMessage("Hi"), assistant, tools)));
        JsonNode blocks = request.get().at("/messages/1/content");
        assertEquals(3, blocks.size(), () -> "Missing tool history: " + request.get() + " result=" + result);
        assertEquals("thinking", blocks.get(0).path("type").asText());
        assertEquals("Reason carefully", blocks.get(0).path("thinking").asText());
        assertEquals("signed-original", blocks.get(0).path("signature").asText());
        assertEquals("redacted_thinking", blocks.get(1).path("type").asText());
        assertEquals("opaque-data", blocks.get(1).path("data").asText());
        assertEquals("tool_use", blocks.get(2).path("type").asText());
        assertNull(correlation.get());
    }

    @Test
    void synchronousThinkingDoesNotReplaceTheFinalTextAnswer() {
        response = response.replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"end_turn\"")
                .replace("{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"echo\",\"input\":{\"x\":1}}",
                        "{\"type\":\"text\",\"text\":\"Final answer\"}");
        ChatResponse result = model.call(new Prompt("Hi"));
        assertEquals(1, result.getResults().size());
        assertEquals("Final answer", result.getResult().getOutput().getText());
        assertTrue(result.getResult().getOutput().getToolCalls().isEmpty());
        assertEquals("Reason carefully", result.getResult().getOutput().getMetadata().get("reasoningContent"));
        assertTrue(result.getResult().getOutput().getMetadata().containsKey(AnthropicSdkChatModel.THINKING_BLOCKS));
    }

    @Test
    void liveThinkingDeltasAndSignedBlocksReachApplicationWithoutToolExecution() {
        contentType = "text/event-stream";
        response =
                """
event: message_start
data: {"type":"message_start","message":{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-4","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":20,"output_tokens":0,"cache_read_input_tokens":4,"cache_creation_input_tokens":2}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"First "}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"second"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-stream"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-1","name":"echo","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"x\\":1}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":7}}

event: message_stop
data: {"type":"message_stop"}

""";
        AtomicInteger executions = new AtomicInteger();
        ToolCallback callback =
                new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("echo")
                                .description("Echo")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    @Override
                    public String call(String input) {
                        executions.incrementAndGet();
                        return input;
                    }
                };
        List<ChatResponse> chunks =
                model.stream(new Prompt("Hi", defaults.mutate().toolCallbacks(callback).build()))
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertNotNull(chunks);
        List<String> reasoning =
                chunks.stream()
                        .flatMap(c -> c.getResults().stream())
                        .map(g -> g.getOutput().getMetadata().get("reasoningContent"))
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(s -> !s.isEmpty())
                        .toList();
        assertEquals(List.of("First ", "second"), reasoning);
        ChatResponse terminal = chunks.getLast();
        assertNotNull(
                terminal.getResult()
                        .getOutput()
                        .getMetadata()
                        .get(AnthropicSdkChatModel.THINKING_BLOCKS));
        assertEquals(4L, terminal.getMetadata().getUsage().getCacheReadInputTokens());
        assertEquals(0, executions.get());
        assertEquals(1, requests.get());
    }

    @Test
    void oauthUsesBearerWithoutApiKeyAndSystemArray() {
        model =
                AnthropicSdkChatModel.create(
                        defaults,
                        ObservationRegistry.NOOP,
                        Map.of("Authorization", "Bearer test-oauth", "x-app", "cli"),
                        true);
        model.call(
                new Prompt(
                        List.of(
                                new SystemMessage("Identity"),
                                new SystemMessage("Instructions"),
                                new UserMessage("Hi"))));
        assertNull(apiKey.get());
        assertEquals("Bearer test-oauth", authorization.get());
        assertTrue(request.get().path("system").isArray());
        assertEquals("Identity", request.get().at("/system/0/text").asText());
        assertEquals("Instructions", request.get().at("/system/1/text").asText());
    }

    @Test
    void oauthCachedSystemKeepsDistinctIdentityAndFinalCacheBoundary() {
        CacheProperties properties = new CacheProperties();
        properties.setEnabled(true);
        var cache = new AnthropicCacheOptionsFactory(properties).build();
        model =
                AnthropicSdkChatModel.create(
                        defaults.mutate().cacheOptions(cache).build(),
                        ObservationRegistry.NOOP,
                        Map.of("Authorization", "Bearer test-oauth"),
                        true);
        String instructions = "Instructions ".repeat(100);
        model.call(
                new Prompt(
                        List.of(
                                new SystemMessage("Identity"),
                                new SystemMessage(instructions),
                                new UserMessage("Hi"))));
        JsonNode blocks = request.get().path("system");
        assertEquals(2, blocks.size());
        assertEquals("Identity", blocks.get(0).path("text").asText());
        assertFalse(blocks.get(0).has("cache_control"));
        assertEquals(instructions, blocks.get(1).path("text").asText());
        assertEquals("ephemeral", blocks.get(1).at("/cache_control/type").asText());
        assertNull(apiKey.get());
    }

    @Test
    void cancellationClosesAnActiveSdkSseConnection() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        server.createContext(
                "/hanging",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    String first =
                            "event: message_start\n"
                                + "data:"
                                + " {\"type\":\"message_start\",\"message\":{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                                + "event: content_block_start\n"
                                + "data:"
                                + " {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                                + "event: content_block_delta\n"
                                + "data:"
                                + " {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n";
                    try {
                        exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        byte[] heartbeat =
                                (":" + "x".repeat(65536) + "\n\n").getBytes(StandardCharsets.UTF_8);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                        while (System.nanoTime() < deadline) {
                            exchange.getResponseBody().write(heartbeat);
                            exchange.getResponseBody().flush();
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } catch (IOException expectedDisconnect) {
                        disconnected.countDown();
                    } finally {
                        exchange.close();
                    }
                });
        model =
                AnthropicSdkChatModel.create(
                        defaults.mutate()
                                .baseUrl(
                                        "http://127.0.0.1:"
                                                + server.getAddress().getPort()
                                                + "/hanging")
                                .build(),
                        ObservationRegistry.NOOP,
                        Map.of(),
                        false);
        assertNotNull(model.stream(new Prompt("Hi")).take(1).blockLast(Duration.ofSeconds(2)));
        assertTrue(
                disconnected.await(2, TimeUnit.SECONDS),
                "SDK request must disconnect promptly on cancellation");
    }

    @Test
    void callerOneShotRetryOverridesSdkDefaultRetries() {
        status = 429;
        response =
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Try"
                        + " later\"}}";
        RetryTemplate retry =
                RetryTemplate.builder().maxAttempts(1).noBackoff().retryOn(Exception.class).build();
        model =
                AnthropicSdkChatModel.create(
                        defaults.mutate().maxRetries(3).build(),
                        ObservationRegistry.NOOP,
                        Map.of(),
                        false,
                        retry);
        assertThrows(RuntimeException.class, () -> model.call(new Prompt("Hi")));
        assertEquals(1, requests.get());
    }

    @Test
    void callerRetryTemplateControlsSyncAttemptCount() {
        status = 503;
        response =
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Try"
                        + " later\"}}";
        RetryTemplate retry =
                RetryTemplate.builder().maxAttempts(2).noBackoff().retryOn(Exception.class).build();
        model =
                AnthropicSdkChatModel.create(
                        defaults, ObservationRegistry.NOOP, Map.of(), false, retry);
        assertThrows(RuntimeException.class, () -> model.call(new Prompt("Hi")));
        assertEquals(2, requests.get());
    }

    @Test
    void continuousStreamCanOutlastConfiguredReadTimeout() {
        frameDelayMillis = 200;
        defaults = defaults.mutate().timeout(Duration.ofSeconds(1)).build();
        model = AnthropicSdkChatModel.create(defaults, ObservationRegistry.NOOP, Map.of(), false);
        liveThinkingDeltasAndSignedBlocksReachApplicationWithoutToolExecution();
    }

    @Test
    void healthySyncBodyCanOutlastConfiguredReadTimeout() throws Exception {
        server.createContext(
                "/slow-sync",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    try {
                        for (int i = 0; i < bytes.length; i += 50) {
                            exchange.getResponseBody()
                                    .write(bytes, i, Math.min(50, bytes.length - i));
                            exchange.getResponseBody().flush();
                            Thread.sleep(200);
                        }
                    } catch (IOException disconnected) {
                        // The failing baseline closes this healthy body at its overall deadline.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        model =
                AnthropicSdkChatModel.create(
                        defaults.mutate()
                                .baseUrl(
                                        "http://127.0.0.1:"
                                                + server.getAddress().getPort()
                                                + "/slow-sync")
                                .timeout(Duration.ofSeconds(1))
                                .build(),
                        ObservationRegistry.NOOP,
                        Map.of(),
                        false);
        assertNotNull(model.call(new Prompt("Hi")));
    }
}
