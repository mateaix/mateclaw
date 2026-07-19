package vip.mate.plugin.mem0;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0ProviderTest {

    private HttpServer server;
    private Mem0Provider provider;
    private final AtomicInteger addCount = new AtomicInteger();
    private final AtomicInteger searchCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        addCount.set(0);
        searchCount.set(0);
        HttpHandler handler = this::handle;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Mem0Config config = new Mem0Config(baseUrl, null, true, true, 3, 3000);
        Mem0Client client = new Mem0Client(config);
        provider = new Mem0Provider(config, client, LoggerFactory.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes(); // drain
        }
        String path = exchange.getRequestURI().getPath();
        byte[] resp;
        if ("/memories/".equals(path)) {
            addCount.incrementAndGet();
            resp = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
        } else if ("/memories/search/".equals(path)) {
            searchCount.incrementAndGet();
            resp = "{\"results\":[{\"id\":\"m1\",\"memory\":\"likes PostgreSQL\",\"score\":0.9}]}".getBytes(StandardCharsets.UTF_8);
        } else {
            resp = "{}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    @Test
    void id_isMem0() {
        assertThat(provider.id()).isEqualTo("mem0");
    }

    @Test
    void isAvailable_true_whenConfigUsableAndAtLeastOneFeatureEnabled() {
        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_false_whenBaseUrlMissing() {
        Mem0Config cfg = new Mem0Config(null, null, true, true, 5, 1000);
        Mem0Provider p = new Mem0Provider(cfg, new Mem0Client(cfg), LoggerFactory.getLogger("test"));
        assertThat(p.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_false_whenBothFeaturesDisabled() {
        Mem0Config cfg = new Mem0Config("http://localhost:8080", null, false, false, 5, 1000);
        Mem0Provider p = new Mem0Provider(cfg, new Mem0Client(cfg), LoggerFactory.getLogger("test"));
        assertThat(p.isAvailable()).isFalse();
    }

    @Test
    void systemPromptBlock_isEmpty() {
        assertThat(provider.systemPromptBlock(1L)).isEmpty();
    }

    @Test
    void twoArgPrefetch_returnsEmptyBecauseNoOwnerKey() {
        // Without ownerKey, Mem0 cannot isolate per-user; provider skips.
        assertThat(provider.prefetch(1L, "hello")).isEmpty();
        assertThat(searchCount.get()).isZero();
    }

    @Test
    void threeArgPrefetch_returnsRecallBlock() {
        String result = provider.prefetch(1L, "what database", "user:42");

        assertThat(result).startsWith("[Mem0 Recall");
        assertThat(result).contains("likes PostgreSQL");
        assertThat(searchCount.get()).isEqualTo(1);
    }

    @Test
    void threeArgPrefetch_returnsEmptyWhenOwnerKeyBlank() {
        assertThat(provider.prefetch(1L, "query", "")).isEmpty();
        assertThat(provider.prefetch(1L, "query", null)).isEmpty();
        assertThat(searchCount.get()).isZero();
    }

    @Test
    void threeArgPrefetch_returnsEmptyWhenQueryBlank() {
        assertThat(provider.prefetch(1L, "", "user:42")).isEmpty();
        assertThat(provider.prefetch(1L, null, "user:42")).isEmpty();
        assertThat(searchCount.get()).isZero();
    }

    @Test
    void threeArgPrefetch_returnsEmptyOnServerError() {
        // Replace handler to fail; the provider should swallow and return "".
        server.removeContext("/");
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(500, 0);
            ex.close();
        });

        String result = provider.prefetch(1L, "q", "user:42");
        assertThat(result).isEmpty();
    }

    @Test
    void syncTurn_pushesAsynchronouslyWithoutBlocking() throws Exception {
        provider.syncTurn(1L, "conv-1", "hello", "world");

        // Wait briefly for the virtual thread to fire the POST.
        long deadline = System.currentTimeMillis() + 2000;
        while (addCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(addCount.get()).isEqualTo(1);
    }

    @Test
    void syncTurn_skipsWhenBothMessagesBlank() throws Exception {
        provider.syncTurn(1L, "conv-1", "   ", "");
        Thread.sleep(200); // give async a chance to (not) fire
        assertThat(addCount.get()).isZero();
    }

    @Test
    void syncTurn_failureIsSwallowedAndDoesNotThrow() throws Exception {
        // Stop the server so the async POST fails; provider must not propagate.
        server.stop(0);
        // Re-create a stub server just so tearDown doesn't NPE; not listening
        // on the original port anymore — the client will get connection refused.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { ex.sendResponseHeaders(200, 0); ex.close(); });
        // Note: client still points at the old port → connection refused.

        provider.syncTurn(1L, "conv-1", "hi", "there");
        Thread.sleep(500);
        // No exception thrown; nothing to assert beyond "test didn't blow up".
    }

    @Test
    void syncTurn_skippedWhenSyncDisabled() throws Exception {
        // Build a provider with sync disabled.
        Mem0Config cfg = new Mem0Config(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                null, true, false, 3, 3000);
        Mem0Provider p = new Mem0Provider(cfg, new Mem0Client(cfg), LoggerFactory.getLogger("test"));
        p.syncTurn(1L, "conv-1", "hi", "there");
        Thread.sleep(200);
        assertThat(addCount.get()).isZero();
    }

    @Test
    void prefetch_skippedWhenSearchDisabled() {
        Mem0Config cfg = new Mem0Config(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                null, false, true, 3, 3000);
        Mem0Provider p = new Mem0Provider(cfg, new Mem0Client(cfg), LoggerFactory.getLogger("test"));
        assertThat(p.prefetch(1L, "q", "user:42")).isEmpty();
        assertThat(searchCount.get()).isZero();
    }
}
