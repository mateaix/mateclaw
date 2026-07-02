package vip.mate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Production posture: with {@code mateclaw.openapi.expose-ui=false} the Swagger
 * UI / OpenAPI document paths must NOT be anonymously reachable. They fall under
 * an explicit {@code hasRole('ADMIN')} rule in {@link SecurityConfig}, so an
 * unauthenticated request is rejected by the authentication entry point (401)
 * instead of leaking the full API surface.
 *
 * <p>Uses a real embedded servlet container ({@code RANDOM_PORT}) because the app
 * registers a WebSocket endpoint that requires a servlet {@code ServerContainer},
 * which the MockMvc-only environment does not provide.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.openapi.expose-ui=false"
        }
)
class OpenApiLockedDownAccessTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("Anonymous OpenAPI JSON is blocked (401) when expose-ui=false")
    void anonymousApiDocsBlocked() {
        ResponseEntity<String> resp = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("Anonymous Swagger UI is blocked (401) when expose-ui=false")
    void anonymousSwaggerUiBlocked() {
        ResponseEntity<String> resp = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("A genuinely public endpoint stays reachable when Swagger is locked")
    void publicEndpointStillReachable() {
        // GET /api/v1/settings/language is permitAll (first-paint i18n); proves
        // the lockdown is scoped to the OpenAPI paths, not a blanket denial.
        ResponseEntity<String> resp = rest.getForEntity("/api/v1/settings/language", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}
