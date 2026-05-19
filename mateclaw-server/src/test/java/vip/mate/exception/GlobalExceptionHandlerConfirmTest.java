package vip.mate.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import vip.mate.i18n.I18nService;
import vip.mate.skill.lifecycle.ConfirmRequiredException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the manual-archive confirm contract: a {@link ConfirmRequiredException}
 * maps to a real HTTP 409 with a structured body the client can branch on.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerConfirmTest {

    @Mock
    private I18nService i18nService;

    @Test
    void confirmRequiredMapsToHttp409WithStructuredBody() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(i18nService);
        ConfirmRequiredException ex = new ConfirmRequiredException(
                "BOUND_SKILL_CONFIRM_REQUIRED",
                "Skill is explicitly bound to 2 agent(s); pass force=true to confirm",
                List.of(new ConfirmRequiredException.AgentRow(42L, "DataAnalyst"),
                        new ConfirmRequiredException.AgentRow(71L, "ReportWriter")));

        ResponseEntity<Map<String, Object>> response = handler.handleConfirmRequired(ex);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("BOUND_SKILL_CONFIRM_REQUIRED", response.getBody().get("code"));
        Object boundAgents = response.getBody().get("boundAgents");
        assertEquals(2, ((List<?>) boundAgents).size());
    }
}
