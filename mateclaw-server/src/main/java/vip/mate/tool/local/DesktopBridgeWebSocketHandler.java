package vip.mate.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Server endpoint for the desktop local-tool tunnel ({@code /api/v1/desktop/ws}).
 * <p>
 * Protocol (JSON text frames):
 * <ul>
 *   <li>desktop → server {@code {"type":"hello","protocolVersion":1,"capabilities":[...],"platform":"darwin"}}</li>
 *   <li>server → desktop {@code {"type":"hello-ack","minProtocol":1}}</li>
 *   <li>server → desktop {@code {"type":"call","id":"<uuid>","method":"read_file","params":{...}}}</li>
 *   <li>desktop → server {@code {"type":"result","id":"<uuid>","ok":true,"data":{...}}}
 *       or {@code {"type":"result","id":"<uuid>","ok":false,"error":"...","code":"DENIED"}}</li>
 *   <li>desktop → server {@code {"type":"ping"}} → server replies {@code {"type":"pong"}}</li>
 * </ul>
 * The authenticated username is injected into the session attributes by
 * {@link DesktopBridgeHandshakeInterceptor}; an unauthenticated socket never
 * reaches this handler.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesktopBridgeWebSocketHandler extends AbstractWebSocketHandler {

    /** Lowest desktop protocol version the server still accepts. */
    private static final int MIN_PROTOCOL = 1;

    private final DesktopBridgeRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[DesktopBridge] WebSocket connected: {} (user={})",
                session.getId(), session.getAttributes().get(DesktopBridgeHandshakeInterceptor.USERNAME_ATTR));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode data;
        try {
            data = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("[DesktopBridge] Invalid JSON frame: {}", e.getMessage());
            return;
        }

        String type = data.path("type").asText("");
        switch (type) {
            case "hello" -> handleHello(session, data);
            case "result" -> registry.complete(data.path("id").asText(), data);
            case "ping" -> send(session, "{\"type\":\"pong\"}");
            default -> log.debug("[DesktopBridge] Ignoring frame type='{}'", type);
        }
    }

    private void handleHello(WebSocketSession session, JsonNode data) throws IOException {
        String username = (String) session.getAttributes().get(DesktopBridgeHandshakeInterceptor.USERNAME_ATTR);
        if (username == null) {
            // Defense in depth — interceptor should have rejected already.
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        int protocolVersion = data.path("protocolVersion").asInt(1);
        if (protocolVersion < MIN_PROTOCOL) {
            send(session, "{\"type\":\"hello-ack\",\"ok\":false,\"error\":\"protocol too old\"}");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        Set<String> capabilities = new LinkedHashSet<>();
        JsonNode caps = data.path("capabilities");
        if (caps.isArray()) {
            caps.forEach(c -> capabilities.add(c.asText()));
        }
        String platform = data.path("platform").asText("unknown");

        registry.register(new DesktopBridgeRegistry.DesktopSession(
                session, username, protocolVersion, Set.copyOf(capabilities), platform));
        send(session, "{\"type\":\"hello-ack\",\"ok\":true,\"minProtocol\":" + MIN_PROTOCOL + "}");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
        log.info("[DesktopBridge] WebSocket disconnected: {} (status={})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        registry.unregister(session);
        log.warn("[DesktopBridge] Transport error: {} - {}", session.getId(), exception.getMessage());
    }

    private void send(WebSocketSession session, String json) throws IOException {
        if (session.isOpen()) {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }
}
