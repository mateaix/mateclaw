package vip.mate.tool.local;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.util.Map;
import java.util.Optional;

/**
 * Authenticates the desktop tunnel WebSocket handshake.
 * <p>
 * The desktop cannot send custom headers on a browser-style WebSocket open, so
 * the token is passed as a {@code ?token=} query parameter (same convention the
 * SSE endpoints use). Both JWT and Personal Access Token forms are accepted.
 * On success the resolved username is stashed in the session attributes under
 * {@link #USERNAME_ATTR} for the handler to read; on failure the handshake is
 * rejected so an unauthenticated socket never reaches the tool bridge.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesktopBridgeHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USERNAME_ATTR = "mateclaw.desktopUser";

    private final AuthService authService;
    private final PersonalAccessTokenService patService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            log.warn("[DesktopBridge] Handshake rejected: missing token");
            return false;
        }

        String username = resolveUsername(token);
        if (username == null) {
            log.warn("[DesktopBridge] Handshake rejected: invalid token");
            return false;
        }

        attributes.put(USERNAME_ATTR, username);
        log.info("[DesktopBridge] Handshake accepted for user={}", username);
        return true;
    }

    private String resolveUsername(String token) {
        try {
            if (token.startsWith(PersonalAccessTokenService.PAT_PREFIX)) {
                Optional<PersonalAccessTokenEntity> maybe = patService.findActiveByPlaintext(token);
                if (maybe.isEmpty()) return null;
                UserEntity user = authService.findById(maybe.get().getUserId());
                return (user != null && Boolean.TRUE.equals(user.getEnabled())) ? user.getUsername() : null;
            }
            Claims claims = authService.parseClaims(token);
            if (claims == null) return null;
            String username = claims.getSubject();
            UserEntity user = authService.findByUsername(username);
            return (user != null && Boolean.TRUE.equals(user.getEnabled())) ? username : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
