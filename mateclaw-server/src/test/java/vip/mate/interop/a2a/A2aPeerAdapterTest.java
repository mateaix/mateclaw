package vip.mate.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aPeerAdapterTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void blocksPrivateNetworkTargetsByDefault() {
        A2aPeerAdapter adapter = new A2aPeerAdapter(new ObjectMapper(), A2aPeerAdapter.Policy.defaults());

        assertThrows(IllegalArgumentException.class, () ->
                adapter.sendBlocking("http://127.0.0.1:8642/api/a2a", "hi", null, null, Map.of()));
    }

    @Test
    void refusesRedirects() throws Exception {
        int port = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.com/api/a2a");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        A2aPeerAdapter adapter = new A2aPeerAdapter(new ObjectMapper(),
                new A2aPeerAdapter.Policy(Duration.ofSeconds(2), 1024 * 1024, true));

        assertThrows(IOException.class, () ->
                adapter.sendBlocking("http://127.0.0.1:" + port + "/api/a2a", "hi", null, null, Map.of()));
    }

    @Test
    void parsesSseFramesUsingEventBoundaries() throws Exception {
        int port = startServer(exchange -> {
            byte[] body = """
                    event: artifact-update
                    data: hello
                    data: world

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        A2aPeerAdapter adapter = new A2aPeerAdapter(new ObjectMapper(),
                new A2aPeerAdapter.Policy(Duration.ofSeconds(2), 1024 * 1024, true));

        A2aPeerAdapter.PeerResult result = adapter.stream(
                "http://127.0.0.1:" + port + "/api/a2a", "hi", null, null, Map.of());

        assertEquals(1, result.frames().size());
        assertEquals("hello\nworld", result.frames().getFirst().data());
    }

    @Test
    void truncatesOversizedResponses() throws Exception {
        int port = startServer(exchange -> {
            byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        A2aPeerAdapter adapter = new A2aPeerAdapter(new ObjectMapper(),
                new A2aPeerAdapter.Policy(Duration.ofSeconds(2), 5, true));

        A2aPeerAdapter.PeerResult result = adapter.sendBlocking(
                "http://127.0.0.1:" + port + "/api/a2a", "hi", null, null, Map.of());

        assertTrue(result.truncated());
        assertEquals(5, result.body().length());
    }

    private int startServer(HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/a2a", handler);
        server.start();
        return server.getAddress().getPort();
    }
}
