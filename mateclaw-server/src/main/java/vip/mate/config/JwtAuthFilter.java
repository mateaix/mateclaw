package vip.mate.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * JWT 认证过滤器
 * 支持两种 Token 传递方式：
 * 1. Authorization: Bearer <token>  （标准方式）
 * 2. ?token=<token>                 （SSE/EventSource 不支持自定义 Header，通过 query param 传递）
 *
 * @author MateClaw Team
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final AuthService authService;
    /**
     * RFC-03 Lane I1 — Personal Access Token service for the headless /
     * CI / SDK auth path. Optional in the constructor sense but Spring
     * always injects since the bean is auto-discovered; declared as a
     * required dependency so unit tests of this filter must wire it.
     */
    private final PersonalAccessTokenService patService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            // RFC-03 Lane I1: prefix-based dispatch — PAT plaintext is observably
            // "mc_*", JWTs always start with "eyJ" (header b64). Cheap O(1) check
            // before the heavier work of parsing the token.
            if (token.startsWith(PersonalAccessTokenService.PAT_PREFIX)) {
                authenticateWithPat(token);
            } else {
                authenticateWithJwt(token, response);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * RFC-03 Lane I1 — PAT auth path. Looks up the token by SHA-256 hash,
     * loads the owning user, and stamps the SecurityContext identically to
     * the JWT path so downstream {@code @PreAuthorize} / {@code Authentication}
     * usages don't need to special-case PAT vs JWT auth.
     */
    private void authenticateWithPat(String plaintext) {
        try {
            Optional<PersonalAccessTokenEntity> maybe = patService.findActiveByPlaintext(plaintext);
            if (maybe.isEmpty()) return;
            PersonalAccessTokenEntity pat = maybe.get();
            UserEntity user = authService.findById(pat.getUserId());
            if (user == null || !Boolean.TRUE.equals(user.getEnabled())) return;

            var auth = new UsernamePasswordAuthenticationToken(
                    user.getUsername(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase()))
            );
            auth.setDetails(user.getId());   // immutable user id for on-behalf-of forwarding
            SecurityContextHolder.getContext().setAuthentication(auth);
            patService.recordUse(pat); // debounced inside the service
        } catch (Exception ignored) {
            // Anonymous fall-through — same behavior as JWT parse failure.
        }
    }

    /** Original JWT auth path, factored out to keep doFilterInternal flat. */
    private void authenticateWithJwt(String token, HttpServletResponse response) {
        try {
            Claims claims = authService.parseClaims(token);
            if (claims == null) return;
            String username = claims.getSubject();
            UserEntity user = authService.findByUsername(username);
            if (user == null || !Boolean.TRUE.equals(user.getEnabled())) return;

            var auth = new UsernamePasswordAuthenticationToken(
                    username, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase()))
            );
            auth.setDetails(user.getId());   // immutable user id for on-behalf-of forwarding
            SecurityContextHolder.getContext().setAuthentication(auth);

            // 滑动窗口续期：Token 接近过期时自动签发新 Token
            if (authService.isNearExpiry(claims)) {
                String newToken = authService.renewToken(username);
                if (newToken != null) {
                    response.setHeader("X-New-Token", newToken);
                    response.setHeader("Access-Control-Expose-Headers", "X-New-Token");
                }
            }
        } catch (Exception ignored) {
            // Token 解析失败，继续匿名访问
        }
    }

    /**
     * 从请求中提取 Token
     * 优先从 Authorization Header 读取，其次从 query param 读取（用于 SSE）
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Authorization Header
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        // 2. Query parameter（SSE 专用）
        String queryToken = request.getParameter("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }
}
