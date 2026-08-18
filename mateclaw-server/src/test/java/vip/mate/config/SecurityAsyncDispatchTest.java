package vip.mate.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityAsyncDispatchTest {

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Test
    void asyncSseRedispatchDoesNotRequireAuthenticationAfterResponseCommit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/teams/20/events");
        request.setServletPath("/api/v1/teams/20/events");
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        springSecurityFilterChain.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isTrue();
    }
}
