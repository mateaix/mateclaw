package vip.mate.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.i18n.I18nService;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that a non-coercible path variable on a typed route surfaces as a
 * clean HTTP 400 (handled by {@link GlobalExceptionHandler}) instead of leaking
 * a 500 with a full stack trace from the catch-all handler.
 */
class GlobalExceptionHandlerTypeMismatchTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        I18nService i18n = mock(I18nService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(i18n))
                .build();
    }

    @Test
    @DisplayName("Non-numeric segment on a Long {id} route returns 400, not 500.")
    void nonNumericIdReturns400() throws Exception {
        mockMvc.perform(get("/probe/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("Invalid value for parameter 'id': expected Long"));
    }

    @Test
    @DisplayName("A valid numeric id still resolves the handler normally.")
    void numericIdReturns200() throws Exception {
        mockMvc.perform(get("/probe/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(123));
    }

    /** Minimal stand-in for any controller with a {@code Long} path variable. */
    @RestController
    static class ProbeController {
        @GetMapping("/probe/{id}")
        R<Long> probe(@PathVariable Long id) {
            return R.ok(id);
        }
    }
}
