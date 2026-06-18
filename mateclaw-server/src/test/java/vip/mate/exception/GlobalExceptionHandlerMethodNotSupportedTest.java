package vip.mate.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.i18n.I18nService;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that a path matched but the HTTP method did not surfaces as a clean
 * HTTP 405 (handled by {@link GlobalExceptionHandler}) instead of leaking a 500
 * with a full stack trace from the catch-all handler.
 *
 * <p>This is the second line of defence for malformed path segments that make
 * a reverse proxy strip the trailing path — e.g. a conversationId ending in
 * ":" landing a GET on a @DeleteMapping route (upstream issue #369).
 */
class GlobalExceptionHandlerMethodNotSupportedTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        I18nService i18n = mock(I18nService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(i18n))
                .build();
    }

    @Test
    @DisplayName("GET on a @DeleteMapping-only route returns 405, not 500.")
    void getOnDeleteOnlyRouteReturns405() throws Exception {
        mockMvc.perform(get("/probe/abc"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.msg").value("Method not allowed"));
    }

    @Test
    @DisplayName("DELETE on the same route still resolves the handler normally.")
    void deleteOnDeleteRouteReturns200() throws Exception {
        mockMvc.perform(delete("/probe/abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("abc"));
    }

    /** Minimal stand-in for a controller whose path is mapped to DELETE only. */
    @RestController
    static class ProbeController {
        @DeleteMapping("/probe/{id}")
        R<String> probe(@PathVariable String id) {
            return R.ok(id);
        }
    }
}
