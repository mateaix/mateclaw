package vip.mate.kbopen.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import vip.mate.kbopen.auth.KbApiKeyService.AuthResult;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KbOpenApiAuthFilter} — focuses on the R7 SSE token
 * fallback and R5 scope limitation added in #446:
 * <ul>
 *   <li>{@code ?token=} is accepted on SSE stream paths (EventSource can't set
 *       an Authorization header).</li>
 *   <li>{@code ?token=} is rejected on non-SSE paths so the key doesn't leak
 *       into access / proxy logs.</li>
 *   <li>The SSE stream path bypasses the per-minute rate limiter (reconnects /
 *       heartbeats must not burn the key's start quota).</li>
 * </ul>
 */
class KbOpenApiAuthFilterTest {

    private static final String KEY = "mck_abcd1234";
    private static final KbApiKeyContext CTX =
            new KbApiKeyContext(7L, 1L, Set.of(10L), Set.of("kb:search"), 60);

    private KbApiKeyService keyService;
    private KbApiKeyRateLimiter rateLimiter;
    private KbOpenApiAuthFilter filter;

    @BeforeEach
    void setUp() {
        keyService = mock(KbApiKeyService.class);
        rateLimiter = mock(KbApiKeyRateLimiter.class);
        filter = new KbOpenApiAuthFilter(keyService, rateLimiter);
        when(keyService.authenticate(KEY)).thenReturn(Optional.of(new AuthResult(CTX, LocalDateTime.now())));
        when(rateLimiter.tryAcquire(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    private MockHttpServletRequest startRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/v1/open/kb/10/research");
        req.setMethod("POST");
        req.addHeader("Authorization", "Bearer " + KEY);
        return req;
    }

    private MockHttpServletRequest sseRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/v1/open/kb/10/research/open-research-x/stream");
        req.setMethod("GET");
        req.setQueryString("token=" + KEY);
        req.addParameter("token", KEY);
        return req;
    }

    private int run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    @DisplayName("non-SSE path: header auth passes")
    void nonSseHeaderAuth() throws Exception {
        assertThat(run(startRequest())).isEqualTo(200);
    }

    @Test
    @DisplayName("non-SSE path: ?token= is rejected even with a valid key (R5 — no log leak)")
    void nonSseQueryTokenRejected() throws Exception {
        MockHttpServletRequest req = startRequest();
        req.removeHeader("Authorization");
        req.addParameter("token", KEY);
        req.setQueryString("token=" + KEY);

        assertThat(run(req)).isEqualTo(401);
        verify(keyService, never()).authenticate(KEY);
    }

    @Test
    @DisplayName("SSE path: ?token= authenticates (R7 — EventSource fallback)")
    void sseQueryTokenAccepted() throws Exception {
        assertThat(run(sseRequest())).isEqualTo(200);
        verify(keyService).authenticate(KEY);
    }

    @Test
    @DisplayName("SSE path: missing token → 401")
    void sseMissingToken() throws Exception {
        MockHttpServletRequest req = sseRequest();
        req.removeParameter("token");
        req.setQueryString(null);
        assertThat(run(req)).isEqualTo(401);
    }

    @Test
    @DisplayName("SSE path: bypasses the per-minute rate limiter (reconnects must not burn quota)")
    void sseSkipsRateLimit() throws Exception {
        run(sseRequest());
        verify(rateLimiter, never())
                .tryAcquire(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("non-SSE path: still goes through the rate limiter")
    void nonSseHitsRateLimit() throws Exception {
        run(startRequest());
        verify(rateLimiter)
                .tryAcquire(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any());
    }
}
