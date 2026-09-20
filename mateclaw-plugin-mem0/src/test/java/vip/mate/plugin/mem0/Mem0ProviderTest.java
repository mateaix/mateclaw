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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mem0ProviderTest {

    private HttpServer server;
    private Mem0Provider provider;
    private final AtomicInteger addCount = new AtomicInteger();
    private final AtomicInteger searchCount = new AtomicInteger();
    private final AtomicReference<String> lastAddBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        addCount.set(0);
        searchCount.set(0);
        lastAddBody.set(null);
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
        if (provider != null) provider.close();
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String path = exchange.getRequestURI().getPath();
        byte[] resp;
        if ("/memories/".equals(path)) {
            addCount.incrementAndGet();
            lastAddBody.set(body);
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
    void threeArgPrefetch_propagatesServerErrorToPlatformCircuitBreaker() {
        server.removeContext("/");
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(500, 0);
            ex.close();
        });

        assertThatThrownBy(() -> provider.prefetch(1L, "q", "user:42"))
                .isInstanceOf(Mem0Exception.class);
    }

    @Test
    void syncTurn_pushesAsynchronouslyWithOwnerKeyAsUserId() throws Exception {
        provider.syncTurn(1L, "conv-1", "hello", "world", "user:42");

        // Wait briefly for the async executor to fire the POST.
        long deadline = System.currentTimeMillis() + 2000;
        while (addCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(addCount.get()).isEqualTo(1);
        // The write must land under the same user_id that prefetch recalls by.
        assertThat(lastAddBody.get()).contains("\"user_id\":\"user:42\"");
        assertThat(lastAddBody.get()).contains("\"agent_id\":\"1\"");
    }

    @Test
    void fourArgSyncTurn_skipsBecauseNoOwnerKey() throws Exception {
        // Without ownerKey, a write would be keyed by an identifier that
        // owner-scoped prefetch never queries; the provider must skip.
        provider.syncTurn(1L, "conv-1", "hello", "world");
        Thread.sleep(200); // give async a chance to (not) fire
        assertThat(addCount.get()).isZero();
    }

    @Test
    void syncTurn_skipsWhenOwnerKeyBlank() throws Exception {
        provider.syncTurn(1L, "conv-1", "hello", "world", "");
        provider.syncTurn(1L, "conv-1", "hello", "world", null);
        Thread.sleep(200);
        assertThat(addCount.get()).isZero();
    }

    @Test
    void syncTurn_skipsWhenBothMessagesBlank() throws Exception {
        provider.syncTurn(1L, "conv-1", "   ", "", "user:42");
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

        provider.syncTurn(1L, "conv-1", "hi", "there", "user:42");
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
        p.syncTurn(1L, "conv-1", "hi", "there", "user:42");
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
        p.close();
    }

    @Test
    void syncQueueIsBoundedAndCloseReleasesExecutor() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        Mem0Config cfg = new Mem0Config("http://localhost:8080", null,
                false, true, 3, 3000, 1);
        Mem0Client blockingClient = new Mem0Client(cfg) {
            @Override
            void addMemories(String userId, String agentId, String conversationId,
                             String userMessage, String assistantReply) {
                writes.incrementAndGet();
                firstStarted.countDown();
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Mem0Provider bounded = new Mem0Provider(cfg, blockingClient, LoggerFactory.getLogger("test"));
        try {
            bounded.syncTurn(1L, "one", "u", "a", "user:1");
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            bounded.syncTurn(1L, "two", "u", "a", "user:1");
            bounded.syncTurn(1L, "three", "u", "a", "user:1");

            assertThat(bounded.queuedSyncCount()).isEqualTo(1);
            assertThat(bounded.droppedSyncCount()).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            bounded.close();
        }
        assertThat(bounded.isClosed()).isTrue();
    }
}
