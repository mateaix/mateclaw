package vip.mate.plugin.yousearch;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YouSearchClientTest {

    private HttpServer server;
    private YouSearchClient client;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKeyHeader = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        // Capture request details so each test can assert what was sent.
        HttpHandler handler = this::handle;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        YouSearchConfig config = new YouSearchConfig("test-key", baseUrl, null, null, 5000);
        client = new YouSearchClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastBody.set(readBody(exchange));
        lastApiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        byte[] resp = ("{\"results\":{\"web\":["
                + "{\"url\":\"https://example.com/a\",\"title\":\"First\","
                + "\"description\":\"desc one\",\"snippets\":[\"snippet one\",\"snippet two\"],"
                + "\"page_age\":\"2026-09-01T00:00:00Z\"},"
                + "{\"url\":\"https://example.com/b\",\"title\":\"Second\","
                + "\"description\":\"desc two\"}"
                + "],\"news\":["
                + "{\"url\":\"https://news.example.com/c\",\"title\":\"News item\","
                + "\"snippets\":[\"news snippet\"],\"page_age\":\"2026-09-02T00:00:00Z\"}"
                + "]},\"metadata\":{}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void search_sendsQueryCountFreshnessAndLanguageInBody() throws Exception {
        client.search("spring boot release", 5, "Month", "en-US");

        assertThat(lastApiKeyHeader.get()).isEqualTo("test-key");

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.get("query").asText()).isEqualTo("spring boot release");
        assertThat(body.get("count").asInt()).isEqualTo(5);
        assertThat(body.get("freshness").asText()).isEqualTo("month"); // lowercased
        assertThat(body.get("language").asText()).isEqualTo("en-US");
    }

    @Test
    void search_omitsAbsentOptionalFields() throws Exception {
        client.search("plain query", null, null, null);

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.has("count")).isFalse();
        assertThat(body.has("freshness")).isFalse();
        assertThat(body.has("language")).isFalse();
    }

    @Test
    void search_sendsConfiguredCountryAndSafesearch() throws Exception {
        YouSearchConfig cfg = new YouSearchConfig(
                "test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "US", "Strict", 5000);
        YouSearchClient withOptions = new YouSearchClient(cfg);

        withOptions.search("query", 3, null, null);

        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.get("country").asText()).isEqualTo("us");
        assertThat(body.get("safesearch").asText()).isEqualTo("strict");
    }

    @Test
    void search_parsesWebThenNewsResults() {
        List<YouSearchHit> hits = client.search("anything", 10, null, null);

        assertThat(hits).hasSize(3); // 2 web + 1 news

        YouSearchHit first = hits.get(0);
        assertThat(first.title()).isEqualTo("First");
        assertThat(first.url()).isEqualTo("https://example.com/a");
        // snippets[0] wins over description
        assertThat(first.snippet()).isEqualTo("snippet one");
        assertThat(first.source()).isEqualTo("example.com");
        assertThat(first.date()).isEqualTo("2026-09-01T00:00:00Z");

        // no snippets → description fallback; no page_age → null date
        YouSearchHit second = hits.get(1);
        assertThat(second.snippet()).isEqualTo("desc two");
        assertThat(second.date()).isNull();

        // news section is collected too
        YouSearchHit news = hits.get(2);
        assertThat(news.title()).isEqualTo("News item");
        assertThat(news.source()).isEqualTo("news.example.com");
    }

    @Test
    void non2xxResponseThrowsYouSearchException() {
        server.removeContext("/");
        server.createContext("/", ex -> {
            byte[] resp = "{\"message\":\"Forbidden\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(403, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });

        assertThatThrownBy(() -> client.search("q", 5, null, null))
                .isInstanceOf(YouSearchException.class)
                .hasMessageContaining("HTTP 403");
    }

    @Test
    void connectionFailureThrowsYouSearchException() {
        // Stop the server, then call — should fail with connection refused.
        int port = server.getAddress().getPort();
        server.stop(0);
        YouSearchConfig cfg = new YouSearchConfig("test-key", "http://127.0.0.1:" + port, null, null, 500);
        YouSearchClient deadClient = new YouSearchClient(cfg);

        assertThatThrownBy(() -> deadClient.search("q", 5, null, null))
                .isInstanceOf(YouSearchException.class)
                .hasMessageContaining("request failed");
    }
}
