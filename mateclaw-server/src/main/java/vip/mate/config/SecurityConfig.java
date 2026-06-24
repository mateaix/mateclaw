package vip.mate.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security 配置
 * <p>
 * 注意：BCryptPasswordEncoder 单独定义为静态内部配置，避免与 JwtAuthFilter 产生循环依赖
 *
 * @author MateClaw Team
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * SpringDoc Swagger UI / OpenAPI document paths. These serve the full REST
     * surface (every endpoint plus request/response schemas), so they are gated
     * explicitly instead of relying on the {@code .anyRequest().permitAll()}
     * fallthrough. Whether they are public or admin-only is driven by
     * {@code mateclaw.openapi.expose-ui} (see {@link #filterChain}).
     */
    private static final String[] OPENAPI_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/webjars/**"
    };

    /**
     * 密码编码器独立配置（打破 SecurityConfig → JwtAuthFilter → AuthService → BCryptPasswordEncoder 循环）
     */
    @Configuration
    static class PasswordEncoderConfig {
        @Bean
        public BCryptPasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    /**
     * Configure the security filter chain.
     *
     * @param exposeOpenApiUi when {@code true} the Swagger UI / OpenAPI document
     *        paths are public; when {@code false} they require a global admin
     *        ({@code ROLE_ADMIN}). Defaults to {@code false} (locked down) when the
     *        property is absent — the base {@code application.yml} enables it for
     *        local dev while the production database profiles keep it off.
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            @Value("${mateclaw.openapi.expose-ui:false}") boolean exposeOpenApiUi) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                // 允许同源 frame 嵌入（Electron 桌面应用、H2 Console 均需要）
                .frameOptions(frame -> frame.sameOrigin())
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                // GET /settings/language stays anonymous (first-paint i18n). PUT
                // requires login + admin (see @RequireGlobalAdmin on the controller).
                auth.requestMatchers(HttpMethod.GET, "/api/v1/settings/language").permitAll()
                // 公开 API 接口
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/agents/*/chat/stream",
                    "/api/v1/chat/stream",
                    "/api/v1/chat/*/stop",
                    "/api/v1/setup/**",
                    "/api/v1/channels/webhook/**",
                    "/api/v1/channels/webchat/**",
                    "/api/v1/talk/ws",
                    // RFC-045: tool-generated files served via unguessable UUID; entries
                    // expire after GeneratedFileCache.TTL (7 days) — delayed access (e.g. an
                    // IM-delivered link opened later) is intentional, the UUID is the guard.
                    "/api/v1/files/generated/**"
                ).permitAll();
                // Swagger UI / OpenAPI document — explicit rule rather than the
                // permitAll() fallthrough. Public for local dev, admin-only in
                // production, driven by mateclaw.openapi.expose-ui.
                if (exposeOpenApiUi) {
                    auth.requestMatchers(OPENAPI_PATHS).permitAll();
                } else {
                    auth.requestMatchers(OPENAPI_PATHS).hasRole("ADMIN");
                }
                // 所有其他 API 接口需要认证
                auth.requestMatchers("/api/**").authenticated()
                // 非 API 请求（前端路由、静态资源、H2 Console 等）全部放行
                .anyRequest().permitAll();
            })
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"msg\":\"Token expired or invalid\",\"data\":null}");
                })
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
