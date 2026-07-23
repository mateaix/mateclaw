package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * End-to-end HTTP coverage of {@code POST /api/v1/channels/webchat/stream} (epic #355 PR 5).
 *
 * <p>Unlike the rest of the webchat suite, which drives the controller directly
 * with {@code webEnvironment = NONE}, this test boots a real servlet container
 * on a random port and issues HTTP POSTs against {@code /stream}. The response
 * body is the actual SSE wire format produced by Spring MVC's SseEmitter — the
 * parser here is the one any third-party SDK would have to write.
 *
 * <p>{@link AgentService} is replaced with a Mockito {@code @MockBean} so the
 * agent / model layer is short-circuited: tests stub
 * {@link AgentService#chatStructuredStream} to return canned {@link AgentService.StreamDelta}s
 * and assert on the resulting SSE event sequence. This keeps the test fast,
 * deterministic, and independent of the real LLM provider registry.
 *
 * <p>Scope:
 * <ul>
 *   <li>happy path — {@code meta} → {@code content_delta}* → {@code done},
 *       concatenated assistant reply persisted as one row</li>
 *   <li>multi-chunk reply (thinking + content + usage event)</li>
 *   <li>bad API key — SSE {@code error} event with "Invalid API Key"</li>
 *   <li>blank message — SSE {@code error} event with "Message is required"</li>
 *   <li>unknown agent (channel misconfigured) — SSE {@code error} event</li>
 * </ul>
 *
 * <p>Not covered here: mid-stream stop (covered by {@link WebChatStopStreamTest}
 * at the controller level — the wiring it asserts on is shared with /stream),
 * and attachment ingestion (requires POST /upload first; out of scope for this
 * PR's wire-format focus).
 *
 * @author MateClaw Team
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_stream_e2e_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789",
        "mateclaw.feature-flag.refresh-ms=999999"
})
class WebChatStreamE2ETest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1e2etest01"; // key8 = "testkey1"
    private static final long CHANNEL_ID = 9_148_001L;
    private static final long AGENT_ID = 9_148_0011L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    /** Replaced with a Mockito mock; tests stub the two methods /stream calls. */
    @MockBean private AgentService agentService;

    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-e2e-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");

        // Default stubs — individual tests override chatStructuredStream as needed.
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setWorkspaceId(1L);
        org.mockito.Mockito.when(agentService.getAgent(AGENT_ID)).thenReturn(agent);
    }

    // ==================== helpers ====================

    private URI streamUri() {
        return URI.create("http://localhost:" + port + "/api/v1/channels/webchat/stream");
    }

    private HttpRequest streamPost(String apiKey, String bodyJson) {
        return HttpRequest.newBuilder()
                .uri(streamUri())
                .timeout(HTTP_TIMEOUT)
                .header("X-MC-Key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Reads the SSE response until either a {@code done} event lands (the
     * controller does not auto-complete the emitter after {@code done}, so we
     * must close ourselves), or the connection closes on its own (error paths
     * call {@code emitter.complete()} via {@code sendErrorAndComplete}).
     */
    private List<SseEvent> sendAndDrain(HttpRequest req) throws Exception {
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream is = resp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return drain(reader);
        }
    }

    private static List<SseEvent> drain(BufferedReader reader) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        SseEvent cur = null;
        boolean seenDone = false;
        String line;
        while (!seenDone && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (cur != null) {
                    events.add(cur);
                    if ("done".equals(cur.name)) {
                        seenDone = true;
                    }
                    cur = null;
                }
                continue;
            }
            if (line.startsWith("event:")) {
                if (cur == null) cur = new SseEvent();
                cur.name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (cur == null) cur = new SseEvent();
                // Multiple data: lines within one event are concatenated by SSE
                // spec with a \n; this server always emits single-line JSON,
                // so we just keep the last one.
                cur.data = line.substring("data:".length()).trim();
            } else if (line.startsWith("id:")) {
                if (cur == null) cur = new SseEvent();
                cur.id = line.substring("id:".length()).trim();
            }
            // Comment lines (":") and unknown prefixes are ignored.
        }
        if (cur != null) {
            events.add(cur);
        }
        return events;
    }

    static final class SseEvent {
        String name;
        String data;
        String id;

        @Override public String toString() {
            return "SseEvent{name='" + name + '\'' + ", data='" + data + '\'' + "}";
        }
    }

    private long countAssistantMessages(String conversationId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_message WHERE conversation_id = ? AND role = 'assistant'",
                Integer.class, conversationId);
        return c != null ? c : 0;
    }

    private long countUserMessages(String conversationId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_message WHERE conversation_id = ? AND role = 'user'",
                Integer.class, conversationId);
        return c != null ? c : 0;
    }

    private String lastAssistantContent(String conversationId) {
        List<String> rows = jdbc.queryForList(
                "SELECT content FROM mate_message WHERE conversation_id = ? AND role = 'assistant' " +
                        "ORDER BY create_time DESC, id DESC LIMIT 1",
                String.class, conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== tests ====================

    @Test
    @DisplayName("happy path: meta → content_delta* → done; assistant reply persisted")
    void happyPath() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(
                        new AgentService.StreamDelta("Hello ", null),
                        new AgentService.StreamDelta("world!", null)));

        String visitorId = "vE2E-happy";
        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"" + visitorId + "\"}"));

        List<String> names = events.stream().map(e -> e.name).toList();
        assertThat(names).containsSequence("meta", "content_delta", "content_delta", "done");

        SseEvent meta = events.stream().filter(e -> "meta".equals(e.name)).findFirst().orElseThrow();
        assertThat(meta.data)
                .contains("\"visitorToken\":")
                .contains("\"conversationId\":")
                .contains("\"sessionId\":null");

        // Concatenated assistant reply persisted exactly once.
        String cid = WebChatController.deriveConversationId(CHANNEL_ID, visitorId, null);
        assertThat(countUserMessages(cid)).isEqualTo(1);
        assertThat(countAssistantMessages(cid)).isEqualTo(1);
        assertThat(lastAssistantContent(cid)).isEqualTo("Hello world!");
    }

    @Test
    @DisplayName("multi-chunk reply: thinking + content + usage event all broadcast; persisted content is content-only")
    void multiChunkReply() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(
                        new AgentService.StreamDelta(null, "Let me think..."),
                        new AgentService.StreamDelta("Final ", null),
                        new AgentService.StreamDelta(null, null, "_usage_final",
                                Map.of("promptTokens", 10, "completionTokens", 5,
                                        "runtimeModelName", "mock-model", "runtimeProviderId", "mock-provider"),
                                false),
                        new AgentService.StreamDelta("answer.", null)));

        String visitorId = "vE2E-multi";
        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"" + visitorId + "\"}"));

        Map<String, List<SseEvent>> byName = new LinkedHashMap<>();
        for (SseEvent e : events) {
            byName.computeIfAbsent(e.name, k -> new ArrayList<>()).add(e);
        }
        assertThat(byName).containsKeys("meta", "thinking_delta", "content_delta", "done");
        // 2 content_delta events, 1 thinking_delta, exactly one done.
        assertThat(byName.get("content_delta")).hasSize(2);
        assertThat(byName.get("thinking_delta")).hasSize(1);
        assertThat(byName.get("done")).hasSize(1);
        assertThat(byName.get("meta")).hasSize(1);

        // Persisted assistant message: only the concatenated content (no thinking).
        String cid = WebChatController.deriveConversationId(CHANNEL_ID, visitorId, null);
        assertThat(lastAssistantContent(cid)).isEqualTo("Final answer.");

        // Usage attribution lands on the row.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT prompt_tokens, completion_tokens, runtime_model, runtime_provider " +
                        "FROM mate_message WHERE conversation_id = ? AND role = 'assistant'", cid);
        assertThat(row.get("prompt_tokens")).isEqualTo(10);
        assertThat(row.get("completion_tokens")).isEqualTo(5);
        assertThat(row.get("runtime_model")).isEqualTo("mock-model");
        assertThat(row.get("runtime_provider")).isEqualTo("mock-provider");
    }

    @Test
    @DisplayName("bad API key → SSE error event 'Invalid API Key', connection closed")
    void badApiKey() throws Exception {
        List<SseEvent> events = sendAndDrain(
                streamPost("bogus-key-not-registered", "{\"message\":\"hi\",\"visitorId\":\"vBad\"}"));

        // Only one event: error. No meta, no content, no done.
        assertThat(events).hasSize(1);
        SseEvent err = events.get(0);
        assertThat(err.name).isEqualTo("error");
        assertThat(err.data).contains("Invalid API Key");

        // No conversation was created → no rows anywhere. The bogus key never
        // resolves a channel, so the channelId passed here is irrelevant; we just
        // need a conversationId that maps to no persisted row.
        assertThat(countAssistantMessages(
                WebChatController.deriveConversationId(-1L, "vBad", null))).isZero();
    }

    @Test
    @DisplayName("blank message → SSE error event 'Message is required'")
    void blankMessage() throws Exception {
        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"   \",\"visitorId\":\"vBlank\"}"));

        assertThat(events).hasSize(1);
        SseEvent err = events.get(0);
        assertThat(err.name).isEqualTo("error");
        assertThat(err.data).contains("Message is required");
    }

    @Test
    @DisplayName("channel with no bound agent → SSE error event 'No agent configured'")
    void channelHasNoAgent() throws Exception {
        // Insert a second channel with no agent_id, point it at a different key.
        long channelNoAgent = 9_148_002L;
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", channelNoAgent);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc-no-agent', 'webchat', NULL, ?, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                channelNoAgent, "{\"api_key\":\"testkey1noagent00\"}");

        List<SseEvent> events = sendAndDrain(
                streamPost("testkey1noagent00", "{\"message\":\"hi\",\"visitorId\":\"vNoAgent\"}"));

        assertThat(events).hasSize(1);
        SseEvent err = events.get(0);
        assertThat(err.name).isEqualTo("error");
        assertThat(err.data).contains("No agent configured");
    }

    @Test
    @DisplayName("sessionId round-trips through meta and seeds the conversation namespace")
    void explicitSessionId() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(new AgentService.StreamDelta("ack", null)));

        String visitorId = "vE2E-sid";
        String sessionId = "thread-42";
        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"" + visitorId + "\"," +
                        "\"sessionId\":\"" + sessionId + "\"}"));

        SseEvent meta = events.stream().filter(e -> "meta".equals(e.name)).findFirst().orElseThrow();
        assertThat(meta.data)
                .contains("\"sessionId\":\"" + sessionId + "\"")
                .contains("\"conversationId\":\"" +
                        WebChatController.deriveConversationId(CHANNEL_ID, visitorId, sessionId) + "\"");
    }

    @Test
    @DisplayName("invalid visitorId charset → SSE error event with the validator message")
    void rejectsInvalidVisitorId() throws Exception {
        // Space is not in the visitorId whitelist.
        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"has space\"}"));

        assertThat(events).hasSize(1);
        SseEvent err = events.get(0);
        assertThat(err.name).isEqualTo("error");
        assertThat(err.data).contains("Invalid visitorId");
    }

    // ------------------------------------------------------------------
    // Visitor-facing lifecycle events (phase / tool_start / tool_end / plan)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("phase event from agent → forwarded as SSE phase event (typing indicator)")
    void forwardsPhaseEvent() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(
                        new AgentService.StreamDelta(null, null, "phase",
                                Map.of("phase", "planning", "timestamp", 1L), false),
                        new AgentService.StreamDelta(null, null, "phase",
                                Map.of("phase", "generating", "timestamp", 2L), false),
                        new AgentService.StreamDelta("ok", null)));

        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"vPhase\"}"));

        List<SseEvent> phases = events.stream().filter(e -> "phase".equals(e.name)).toList();
        assertThat(phases).hasSize(2);
        assertThat(phases.get(0).data).contains("\"phase\":\"planning\"");
        assertThat(phases.get(1).data).contains("\"phase\":\"generating\"");
        // Each carries a timestamp for client-side timeline rendering.
        assertThat(phases.get(0).data).contains("\"timestamp\":");
    }

    @Test
    @DisplayName("tool_call_started → tool_start SSE event; args are NOT leaked to the visitor")
    void forwardsToolStartWithoutArgs() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(
                        new AgentService.StreamDelta(null, null, "tool_call_started",
                                Map.of("toolCallId", "call_1",
                                        "toolName", "web_search",
                                        "arguments", "secret query with PII",
                                        "timestamp", 1L), false),
                        new AgentService.StreamDelta("done", null)));

        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"vTool\"}"));

        SseEvent toolStart = events.stream().filter(e -> "tool_start".equals(e.name)).findFirst().orElseThrow();
        assertThat(toolStart.data).contains("\"tool\":\"web_search\"");
        // Critical: arguments must NOT be forwarded.
        assertThat(toolStart.data).doesNotContain("secret query");
        assertThat(toolStart.data).doesNotContain("PII");
        assertThat(toolStart.data).doesNotContain("arguments");
    }

    @Test
    @DisplayName("tool_call_completed → tool_end SSE event; result content is NOT leaked")
    void forwardsToolEndWithoutResult() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(
                        new AgentService.StreamDelta(null, null, "tool_call_completed",
                                Map.of("toolCallId", "call_1",
                                        "toolName", "web_search",
                                        "result", "<huge internal result payload>",
                                        "success", true,
                                        "timestamp", 1L), false),
                        new AgentService.StreamDelta("ack", null)));

        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"vToolEnd\"}"));

        SseEvent toolEnd = events.stream().filter(e -> "tool_end".equals(e.name)).findFirst().orElseThrow();
        assertThat(toolEnd.data).contains("\"tool\":\"web_search\"");
        assertThat(toolEnd.data).contains("\"success\":true");
        // Result content is dropped.
        assertThat(toolEnd.data).doesNotContain("huge internal result payload");
        assertThat(toolEnd.data).doesNotContain("\"result\"");
    }

    @Test
    @DisplayName("plan_created → plan SSE event with the step list")
    void forwardsPlanEvent() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(
                        new AgentService.StreamDelta(null, null, "plan_created",
                                Map.of("planId", 42L,
                                        "steps", List.of("search the web", "summarize"),
                                        "timestamp", 1L), false),
                        new AgentService.StreamDelta("done", null)));

        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"vPlan\"}"));

        SseEvent plan = events.stream().filter(e -> "plan".equals(e.name)).findFirst().orElseThrow();
        assertThat(plan.data).contains("\"steps\":[");
        assertThat(plan.data).contains("search the web");
        assertThat(plan.data).contains("summarize");
    }

    @Test
    @DisplayName("internal event types (_routing_decision / perf_summary / iteration_* / ...) are silently dropped")
    void dropsInternalEvents() throws Exception {
        org.mockito.Mockito.when(agentService.chatStructuredStream(
                        eq(AGENT_ID), anyString(), anyString(), anyString(), isNull(), any()))
                .thenReturn(Flux.just(
                        // Internal-only — must not produce an SSE event.
                        new AgentService.StreamDelta(null, null, "_routing_decision",
                                Map.of("sidecar", "vision"), false),
                        new AgentService.StreamDelta(null, null, "perf_summary",
                                Map.of("phase", "generate", "tokensPerSec", 42.0), false),
                        new AgentService.StreamDelta(null, null, "iteration_start",
                                Map.of("index", 0, "reason", "tool_call"), false),
                        new AgentService.StreamDelta(null, null, "finish_reason",
                                Map.of("reason", "STOP"), false),
                        new AgentService.StreamDelta("done", null)));

        List<SseEvent> events = sendAndDrain(
                streamPost(API_KEY, "{\"message\":\"hi\",\"visitorId\":\"vQuiet\"}"));

        // Only meta, content_delta (for "done"), and the terminal done event —
        // none of the internal event types leaked.
        List<String> names = events.stream().map(e -> e.name).toList();
        assertThat(names).isNotEmpty();
        assertThat(names).doesNotContain("_routing_decision", "perf_summary", "iteration_start", "finish_reason");
    }
}
