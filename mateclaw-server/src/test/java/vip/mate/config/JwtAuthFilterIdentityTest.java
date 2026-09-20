package vip.mate.config;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterIdentityTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"matching", "different", "missing", "malformed", "integer", "snowflake"})
    void onlyTheAccountNamedByTheSignedIdentityIsAuthenticated(String kind) throws Exception {
        var service = mock(AuthService.class);
        var user = new UserEntity(); user.setId(kind.equals("snowflake") ? 2099554193585278979L : 42L);
        user.setUsername("alice"); user.setEnabled(true); user.setRole("user");
        var claims = Jwts.claims().subject("alice");
        switch (kind) {
            case "matching", "snowflake" -> claims.add("userId", user.getId());
            case "integer" -> claims.add("userId", 42);
            case "different" -> claims.add("userId", 41L);
            case "malformed" -> claims.add("userId", "invalid");
            default -> { }
        }
        when(service.parseClaims("fixture")).thenReturn(claims.build());
        when(service.findByUsername("alice")).thenReturn(user);
        when(service.isNearExpiry(any())).thenReturn(true);
        when(service.generateToken(user)).thenReturn("renewed-same-identity");
        var request = new MockHttpServletRequest("GET", "/api/v1/goals/1/json-acceptance");
        request.addHeader("Authorization", "Bearer fixture");
        var response = new MockHttpServletResponse();
        new JwtAuthFilter(service, mock(PersonalAccessTokenService.class)).doFilter(request, response, (req, res) -> { });
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (kind.equals("matching") || kind.equals("integer") || kind.equals("snowflake")) {
            assertNotNull(authentication);
            assertEquals(user.getId(), authentication.getDetails());
            assertEquals("renewed-same-identity", response.getHeader("X-New-Token"));
            verify(service).generateToken(user);
        } else {
            assertNull(authentication);
            verify(service, never()).generateToken(any());
        }
    }
}
