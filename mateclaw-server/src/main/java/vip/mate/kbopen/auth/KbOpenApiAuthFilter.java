package vip.mate.kbopen.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import vip.mate.kbopen.auth.KbApiKeyService.AuthResult;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Authentication filter for {@code /api/v1/open/kb/**} — the sole gatekeeper
 * for the permitAll open API path.
 *
 * <p><strong>R1: this filter must reject, never pass-through.</strong> Because
 * the path is in the SecurityConfig {@code permitAll} whitelist, there is no
 * downstream Spring Security chain to catch an unauthenticated request. A
 * missing, malformed, or invalid key <em>must</em> result in an immediate 401
 * — it cannot fall through to the Controller (which would run without a
 * {@link KbApiKeyContext}).
 *
 * <p><strong>R2: per-key rate limiting.</strong> After successful auth, the
 * filter checks the sliding-window limiter. Exceeding
 * {@code rateLimitPerMin} returns 429.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbOpenApiAuthFilter extends OncePerRequestFilter {

    private final KbApiKeyService keyService;
    private final KbApiKeyRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Only guard the open API path. Other paths fall through to normal security.
        if (!isOpenApiPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractBearerToken(request);
        if (!StringUtils.hasText(token)) {
            sendUnauthorized(response, "Missing API key");
            return;
        }

        Optional<AuthResult> authResult = keyService.authenticate(token);
        if (authResult.isEmpty()) {
            sendUnauthorized(response, "Invalid or expired API key");
            return;
        }

        KbApiKeyContext context = authResult.get().context();

        // R2: rate limit check
        if (!rateLimiter.tryAcquire(context.keyId(), context.rateLimitPerMin(), Instant.now())) {
            sendTooManyRequests(response, context.rateLimitPerMin());
            return;
        }

        // Inject context for downstream @RequireKbScope authorization
        request.setAttribute(KbApiKeyContext.ATTR, context);

        // Debounced last-used recording
        keyService.recordUse(context.keyId(), authResult.get().lastUsedAt());

        filterChain.doFilter(request, response);
    }

    private boolean isOpenApiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/api/v1/open/kb/");
    }

    private String extractBearerToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7).trim();
        }
        // TODO: add ?token= SSE fallback once Deep Research SSE endpoint is live.
        //  EventSource can't set custom headers; for now P0-A has no SSE path so
        //  query param would leak the key into access / proxy logs (R5).
        return null;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + message + "\",\"data\":null}");
    }

    private void sendTooManyRequests(HttpServletResponse response, int limit) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"msg\":\"Rate limit exceeded (" + limit + "/min)\",\"data\":null}");
    }
}
