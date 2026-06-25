package vip.mate.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter for login + SSO bind endpoints — prevents brute force attacks.
 * Allows max 5 attempts per IP per minute across all password-checking paths.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class LoginRateLimitFilter implements Filter {

    private static final int MAX_ATTEMPTS = 5;
    /**
     * Endpoints that accept a username + password and must be rate-limited
     * against brute force. SSO callback is excluded (no password submitted).
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/sso/bind");

    /** IP → attempt count, auto-expires after 1 minute */
    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;

        if ("POST".equalsIgnoreCase(httpReq.getMethod()) && PROTECTED_PATHS.contains(httpReq.getRequestURI())) {
            String ip = getClientIp(httpReq);
            AtomicInteger count = attempts.get(ip, k -> new AtomicInteger(0));
            int current = count.incrementAndGet();

            if (current > MAX_ATTEMPTS) {
                log.warn("[RateLimit] Login rate limit exceeded for IP: {} (attempts: {})", ip, current);
                HttpServletResponse httpResp = (HttpServletResponse) response;
                httpResp.setStatus(429);
                httpResp.setContentType("application/json;charset=UTF-8");
                httpResp.getWriter().write("{\"code\":429,\"msg\":\"Too many login attempts, please try again later\",\"data\":null}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
