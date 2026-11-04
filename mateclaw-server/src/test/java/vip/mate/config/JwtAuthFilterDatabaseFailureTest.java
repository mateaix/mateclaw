package vip.mate.config;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterDatabaseFailureTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = {"jwt", "pat"})
    void databaseOutageIsUnavailableRatherThanInvalidCredentials(String kind) throws Exception {
        var auth = mock(AuthService.class);
        var pats = mock(PersonalAccessTokenService.class);
        var failure = new DataAccessResourceFailureException("database has been closed: private database path");
        String token;
        if (kind.equals("jwt")) {
            token = "signed-token";
            when(auth.parseClaims(token)).thenReturn(Jwts.claims().subject("alice").build());
            when(auth.findByUsername("alice")).thenThrow(failure);
        } else {
            token = PersonalAccessTokenService.PAT_PREFIX + "fixture";
            when(pats.findActiveByPlaintext(token)).thenThrow(failure);
        }
        var request = new MockHttpServletRequest("GET", "/api/v1/settings/language");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();

        new JwtAuthFilter(auth, pats).doFilter(request, response, (req, res) -> continued.set(true));

        assertEquals(503, response.getStatus());
        assertFalse(continued.get(), "Database outage must not fall through to the 401 entry point");
        assertTrue(response.getContentAsString().contains("503"));
        assertFalse(response.getContentAsString().contains("private database path"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
