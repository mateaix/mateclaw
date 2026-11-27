package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import vip.mate.agent.runtime.RuntimeEventStreamAdapter;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfigService;
import vip.mate.agent.runtime.dsh.management.DshRuntimeConfiguration;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SDK/tools with a deterministic local model endpoint, without credentials or user data. */
@EnabledIfEnvironmentVariable(named = "MATECLAW_DSH_WORK_ACCEPTANCE_ROOT", matches = ".+")
class DshWorkAcceptanceTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"0.2.2-rc.5", "0.2.3-alpha.2"})
    void realFileAndShellWorkReachesChatCardsWithFailureAndUsage(String version) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path target = Files.writeString(workspace.resolve("work.txt"), "before");
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> respond(exchange, requests, target));
        server.start();
        try {
            var config = mock(DshRuntimeConfigService.class);
            String executable = Path.of(System.getenv("MATECLAW_DSH_WORK_ACCEPTANCE_ROOT"))
                    .resolve(version).resolve("bin/dsh").toString();
            var configuration = new DshRuntimeConfiguration(executable, "", workspace.toString(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "work-test", "local-test-only",
                    "sdk", List.of(), temp.resolve("homes").toString());
            when(config.resolve()).thenReturn(configuration);
            var models = mock(ModelConfigService.class);
            var model = new ModelConfigEntity();
            model.setModelName("work-test");
            model.setMaxTokens(1000);
            model.setMaxInputTokens(128000);
            when(models.resolveModel(any())).thenReturn(model);
            var service = new DshRuntimeService(json, models, mock(ModelProviderService.class), config,
                    new ConversationWindowProperties());
            String instruction = "You are the MateClaw workspace build engineer. Verify every file change.";
            var session = new RuntimeSession("work", "work", 11L, 12L, "work-test", workspace,
                    Map.of("hostSystemPrompt", instruction));
            var events = service.start(session).prompt("Complete the work and report the observed result.")
                    .collectList().block(Duration.ofSeconds(120));
            assertNotNull(events);
            assertEquals("after", Files.readString(target));
            assertEquals(1, events.stream().filter(e -> e.type() == RuntimeEventType.COMPLETED).count());
            assertEquals("WORK_DONE", events.stream().filter(e -> e.type() == RuntimeEventType.ASSISTANT_DELTA)
                    .map(e -> e.text()).reduce("", String::concat));
            var cards = RuntimeEventStreamAdapter.adapt(Flux.fromIterable(events))
                    .collectList().block();
            assertNotNull(cards);
            var starts = cards.stream().filter(e -> "tool_call_started".equals(e.eventType())).toList();
            var ends = cards.stream().filter(e -> "tool_call_completed".equals(e.eventType())).toList();
            assertEquals(5, starts.size());
            assertEquals(5, ends.size());
            for (int i = 0; i < 5; i++) {
                assertEquals(starts.get(i).eventData().get("toolCallId"), ends.get(i).eventData().get("toolCallId"));
                assertFalse(((String) starts.get(i).eventData().get("arguments")).isBlank());
            }
            assertEquals("DSH_WORK_OK", ends.get(2).eventData().get("result"));
            assertEquals(false, ends.get(3).eventData().get("success"));
            assertFalse(((String) ends.get(3).eventData().get("result")).isBlank());
            assertTrue(events.stream().anyMatch(e -> e.type() == RuntimeEventType.CONTEXT_USAGE
                    && ((Number) e.data().get("inputTokens")).longValue() > 0));
            assertTrue(requests.size() >= 7);
            assertEquals(1, events.stream().filter(e -> e.type() == RuntimeEventType.SUBAGENT_STARTED).count());
            assertEquals(1, events.stream().filter(e -> e.type() == RuntimeEventType.SUBAGENT_FINISHED
                    && Boolean.TRUE.equals(e.data().get("success"))
                    && "CHILD_DONE".equals(e.data().get("resultPreview"))).count());
            assertTrue(requests.stream().allMatch(r -> r.path("max_tokens").asInt() == 1000));
            assertTrue(requests.getFirst().path("system").toString().contains(instruction),
                    "Employee instructions must reach the model as system content");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private void respond(HttpExchange exchange, List<JsonNode> requests, Path target) throws IOException {
        JsonNode request = json.readTree(exchange.getRequestBody());
        requests.add(request);
        boolean child = isChild(request);
        int step = (int) requests.stream().filter(item -> !isChild(item)).count();
        if (child) {
            try { Thread.sleep(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        var script = List.of(
                Map.of("name", "read", "input", Map.of("file_path", target.toString())),
                Map.of("name", "edit", "input", Map.of("file_path", target.toString(), "old_string", "before", "new_string", "after")),
                Map.of("name", "bash", "input", Map.of("command", "printf DSH_WORK_OK", "description", "Print the work marker")),
                Map.of("name", "read", "input", Map.of("file_path", target.resolveSibling("missing.txt").toString())),
                Map.of("name", "subagent", "input", Map.of("description", "Verify child work", "prompt", "CHILD_TASK_MARKER")));
        var events = new ArrayList<Map<String, Object>>();
        events.add(Map.of("type", "message_start", "message", Map.of("id", "m" + step, "type", "message",
                "role", "assistant", "model", "work-test", "content", List.of(), "usage", Map.of("input_tokens", 5, "output_tokens", 0))));
        if (!child && step <= script.size()) {
            var tool = script.get(step - 1);
            events.add(Map.of("type", "content_block_start", "index", 0, "content_block",
                    Map.of("type", "tool_use", "id", "call" + step, "name", tool.get("name"), "input", Map.of())));
            events.add(Map.of("type", "content_block_delta", "index", 0, "delta",
                    Map.of("type", "input_json_delta", "partial_json", json.writeValueAsString(tool.get("input")))));
        } else {
            events.add(Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text", "text", "")));
            events.add(Map.of("type", "content_block_delta", "index", 0, "delta", Map.of("type", "text_delta", "text", child ? "CHILD_DONE" : "WORK_DONE")));
        }
        events.add(Map.of("type", "content_block_stop", "index", 0));
        events.add(Map.of("type", "message_delta", "delta", Map.of("stop_reason", !child && step <= script.size() ? "tool_use" : "end_turn"),
                "usage", Map.of("output_tokens", 10)));
        events.add(Map.of("type", "message_stop"));
        StringBuilder body = new StringBuilder();
        for (var event : events) body.append("event: ").append(event.get("type")).append("\ndata: ")
                .append(json.writeValueAsString(event)).append("\n\n");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private boolean isChild(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            if ("user".equals(message.path("role").asText()) && message.toString().contains("CHILD_TASK_MARKER")) return true;
        }
        return false;
    }
}
