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
 *
 * <p><strong>R5 / R7: SSE token fallback is scope-limited.</strong> The
 * {@code ?token=} query param is accepted <em>only</em> on SSE stream paths
 * ({@link #isSseStreamPath}), where browser EventSource cannot set an
 * Authorization header (R7). It is rejected on every other path so the API
 * key never leaks into access / proxy logs for normal calls (R5). Application
 * logging here uses {@code getRequestURI()} (no query string), so the key does
 * not reach app logs — but a reverse proxy may still log the query string, so
 * keep the fallback as narrow as possible.
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

        boolean sse = isSseStreamPath(request);
        String token = extractToken(request, sse);
        if (!StringUtils.hasText(token)) {
            sendUnauthorized(response, sse
                    ? "Missing API key (Authorization header or ?token= for EventSource)"
                    : "Missing API key");
            return;
        }

        Optional<AuthResult> authResult = keyService.authenticate(token);
        if (authResult.isEmpty()) {
            sendUnauthorized(response, "Invalid or expired API key");
            return;
        }

        KbApiKeyContext context = authResult.get().context();

        // R2: rate limit check — but NOT on the SSE stream path. EventSource
        // reconnects/heartbeats would otherwise burn the per-minute window and
        // can 429 the key's own POST /research start. Rate limiting belongs on
        // the cost-producing endpoints (start/status/cancel), not the progress
        // subscription. Per-key concurrency is still enforced upstream.
        if (!sse && !rateLimiter.tryAcquire(context.keyId(), context.rateLimitPerMin(), Instant.now())) {
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

    /**
     * SSE progress stream paths — the only place {@code ?token=} is accepted,
     * because browser EventSource cannot set an Authorization header (R7).
     * Matched on URI suffix + content type so the fallback tracks whichever
     * endpoints expose SSE, without hard-coding a single kbId/sessionId.
     */
    private boolean isSseStreamPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/api/v1/open/kb/") && uri.endsWith("/stream");
    }

    /**
     * Extract the API key. Header is always accepted; the {@code ?token=}
     * query param is accepted <em>only</em> on SSE stream paths ({@code sse}),
     * to keep the key out of access/proxy logs on every other request (R5).
     */
    private String extractToken(HttpServletRequest request, boolean sse) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7).trim();
        }
        if (sse) {
            String queryToken = request.getParameter("token");
            if (StringUtils.hasText(queryToken)) {
                return queryToken.trim();
            }
        }
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
