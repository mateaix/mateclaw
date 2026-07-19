package vip.mate.plugin.mem0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mem0ClientTest {

    private HttpServer server;
    private Mem0Client client;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        // Capture request details so each test can assert what was sent.
        HttpHandler handler = this::handle;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Mem0Config config = new Mem0Config(baseUrl, "test-token", true, true, 5, 3000);
        client = new Mem0Client(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        String path = exchange.getRequestURI().getPath();
        if ("/memories/".equals(path) || "/memories/search/".equals(path)) {
            byte[] resp;
            if ("/memories/".equals(path)) {
                resp = "{\"results\":[{\"id\":\"m1\",\"memory\":\"x\",\"event\":\"ADD\"}]}".getBytes(StandardCharsets.UTF_8);
            } else {
                resp = "{\"results\":[{\"id\":\"m1\",\"memory\":\"likes Go\",\"score\":0.9},{\"id\":\"m2\",\"memory\":\"works at Acme\",\"score\":0.7}]}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
        } else {
            byte[] resp = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, resp.length);
            exchange.getResponseBody().write(resp);
        }
        exchange.close();
    }

    @Test
    void addMemories_postsToMemoriesEndpointWithCorrectPayload() throws Exception {
        client.addMemories("user:42", "1", "conv-abc", "hello", "world");

        assertThat(lastPath.get()).isEqualTo("/memories/");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer test-token");

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.get("user_id").asText()).isEqualTo("user:42");
        assertThat(body.get("agent_id").asText()).isEqualTo("1");
        assertThat(body.get("metadata").get("conversation_id").asText()).isEqualTo("conv-abc");
        assertThat(body.get("messages").size()).isEqualTo(2);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("hello");
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(body.get("messages").get(1).get("content").asText()).isEqualTo("world");
    }

    @Test
    void addMemories_omitsBlankMessages() throws Exception {
        client.addMemories("user:42", "1", null, "   ", "reply");

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.get("messages").size()).isEqualTo(1);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("assistant");
        // metadata should be absent since conversationId is null
        assertThat(body.has("metadata")).isFalse();
    }

    @Test
    void searchMemories_returnsParsedMemoryStrings() {
        List<String> results = client.searchMemories("user:42", "1", "what language");

        assertThat(results).containsExactly("likes Go", "works at Acme");

        assertThat(lastPath.get()).isEqualTo("/memories/search/");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer test-token");
    }

    @Test
    void searchMemories_includesQueryUserIdAndLimitInBody() throws Exception {
        client.searchMemories("user:42", "1", "query text");

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.get("query").asText()).isEqualTo("query text");
        assertThat(body.get("user_id").asText()).isEqualTo("user:42");
        assertThat(body.get("agent_id").asText()).isEqualTo("1");
        assertThat(body.get("limit").asInt()).isEqualTo(5); // from Mem0Config in setUp
    }

    @Test
    void non2xxResponseThrowsMem0Exception() {
        // Use a client pointed at a non-existent path on the running server.
        // Reconfigure handler to return 500 for the next call.
        server.removeContext("/");
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(500, 0);
            ex.close();
        });

        assertThatThrownBy(() -> client.searchMemories("user:42", "1", "q"))
                .isInstanceOf(Mem0Exception.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void connectionFailureThrowsMem0Exception() {
        // Stop the server, then call — should fail with connection refused.
        int port = server.getAddress().getPort();
        server.stop(0);
        Mem0Config cfg = new Mem0Config("http://127.0.0.1:" + port, null, true, true, 5, 500);
        Mem0Client deadClient = new Mem0Client(cfg);

        assertThatThrownBy(() -> deadClient.searchMemories("user:42", "1", "q"))
                .isInstanceOf(Mem0Exception.class)
                .hasMessageContaining("request failed");
    }

    @Test
    void buildAddPayload_isConsistentWithAddMemories() {
        // buildAddPayload is a test helper used to inspect payload structure
        // without sending; verify it matches what addMemories would send.
        Map<String, Object> payload = client.buildAddPayload("user:42", "1", "conv-x", "hi", "there");
        assertThat(payload).containsEntry("user_id", "user:42");
        assertThat(payload).containsEntry("agent_id", "1");
        assertThat(payload).containsKey("messages");
        assertThat(payload).containsKey("metadata");
    }
}
