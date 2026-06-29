package vip.mate.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of connected desktop tunnels, keyed by the authenticated username.
 * <p>
 * A desktop client opens a WebSocket to the server and registers itself here.
 * When a cloud agent invokes a {@code local_*} tool, the tool resolves the
 * requesting user, looks up that user's live desktop session, and forwards an
 * RPC call. The desktop executes the file/shell operation locally and replies,
 * which completes the pending future the caller is blocked on.
 * <p>
 * Concurrency: a single {@link WebSocketSession} is not safe for concurrent
 * sends, so every frame written to a session is guarded by a monitor on that
 * session. Pending RPC futures live in a flat map keyed by request id; the
 * handler completes them when the matching {@code result} frame arrives.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesktopBridgeRegistry {

    private final ObjectMapper objectMapper;

    /**
     * A live desktop tunnel. {@code protocolVersion} and {@code capabilities}
     * are negotiated in the {@code hello} handshake so {@code local_*} tools
     * can degrade gracefully against older clients (older desktops advertise a
     * smaller capability set — e.g. read/list only — and never receive
     * write/edit/shell calls).
     */
    public record DesktopSession(
            WebSocketSession session,
            String username,
            int protocolVersion,
            Set<String> capabilities,
            String platform) {

        public boolean supports(String capability) {
            return capabilities != null && capabilities.contains(capability);
        }
    }

    /** username -> live desktop session (latest connection wins). */
    private final ConcurrentHashMap<String, DesktopSession> sessionsByUser = new ConcurrentHashMap<>();

    /** wsSessionId -> username, for cleanup on disconnect. */
    private final ConcurrentHashMap<String, String> userByWsSession = new ConcurrentHashMap<>();

    /** requestId -> caller awaiting the desktop's reply. */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** requestId -> id of the ws session it was routed to, so a disconnect only fails its own calls. */
    private final ConcurrentHashMap<String, String> pendingOwner = new ConcurrentHashMap<>();

    /** Register a freshly handshaken desktop session, replacing any prior one for the user. */
    public void register(DesktopSession desktop) {
        DesktopSession prior = sessionsByUser.put(desktop.username(), desktop);
        userByWsSession.put(desktop.session().getId(), desktop.username());
        if (prior != null && !prior.session().getId().equals(desktop.session().getId())) {
            // Same user reconnected from another window — drop the stale one.
            userByWsSession.remove(prior.session().getId());
            closeQuietly(prior.session());
        }
        log.info("[DesktopBridge] Registered desktop for user={}, protocol={}, caps={}, platform={}",
                desktop.username(), desktop.protocolVersion(), desktop.capabilities(), desktop.platform());
    }

    /** Remove a session on disconnect/error and fail any of its in-flight calls. */
    public void unregister(WebSocketSession session) {
        String wsId = session.getId();
        String username = userByWsSession.remove(wsId);
        if (username != null) {
            DesktopSession current = sessionsByUser.get(username);
            if (current != null && current.session().getId().equals(wsId)) {
                sessionsByUser.remove(username);
            }
            log.info("[DesktopBridge] Unregistered desktop for user={}", username);
        }
        // Fail only the pending calls that were routed to this socket — other
        // users' desktops keep their in-flight calls.
        pendingOwner.forEach((id, ownerWsId) -> {
            if (ownerWsId.equals(wsId)) {
                CompletableFuture<JsonNode> future = pending.remove(id);
                pendingOwner.remove(id);
                if (future != null && !future.isDone()) {
                    future.completeExceptionally(new DesktopBridgeException(
                            DesktopBridgeException.Code.OFFLINE, "Desktop disconnected before replying"));
                }
            }
        });
    }

    public boolean isOnline(String username) {
        if (username == null) return false;
        DesktopSession s = sessionsByUser.get(username);
        return s != null && s.session().isOpen();
    }

    public DesktopSession getSession(String username) {
        return username == null ? null : sessionsByUser.get(username);
    }

    /** Complete a pending call with the desktop's {@code result} payload. */
    public void complete(String requestId, JsonNode resultEnvelope) {
        CompletableFuture<JsonNode> future = pending.remove(requestId);
        pendingOwner.remove(requestId);
        if (future != null) {
            future.complete(resultEnvelope);
        } else {
            log.debug("[DesktopBridge] No pending call for id={} (timed out or duplicate)", requestId);
        }
    }

    /**
     * Send a {@code call} frame to the user's desktop and return a future that
     * completes when the matching {@code result} frame arrives. Throws
     * {@link DesktopBridgeException} with {@code OFFLINE} when the user has no
     * live tunnel, or {@code UNSUPPORTED} when the desktop is too old to honor
     * the requested capability.
     */
    public CompletableFuture<JsonNode> call(String username, String method, String capability, ObjectNode params) {
        DesktopSession desktop = sessionsByUser.get(username);
        if (desktop == null || !desktop.session().isOpen()) {
            throw new DesktopBridgeException(DesktopBridgeException.Code.OFFLINE,
                    "No desktop is connected for this user");
        }
        if (capability != null && !desktop.supports(capability)) {
            throw new DesktopBridgeException(DesktopBridgeException.Code.UNSUPPORTED,
                    "The connected desktop does not support '" + capability
                            + "' (upgrade the MateClaw desktop app)");
        }

        String requestId = UUID.randomUUID().toString();
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "call");
        frame.put("id", requestId);
        frame.put("method", method);
        frame.set("params", params != null ? params : objectMapper.createObjectNode());

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(requestId, future);
        pendingOwner.put(requestId, desktop.session().getId());
        try {
            WebSocketSession session = desktop.session();
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
            }
        } catch (IOException e) {
            pending.remove(requestId);
            pendingOwner.remove(requestId);
            throw new DesktopBridgeException(DesktopBridgeException.Code.OFFLINE,
                    "Failed to reach desktop: " + e.getMessage());
        }
        return future;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) session.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
