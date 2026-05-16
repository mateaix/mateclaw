package vip.mate.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vip.mate.common.result.R;
import vip.mate.i18n.I18nService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(I18nService.class));

    @Test
    void mateClawExceptionUsesMatchingHttpStatus() {
        ResponseEntity<R<Void>> response = handler.handleMateClawException(
                new MateClawException(404, "Not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().getCode());
    }

    @Test
    void genericExceptionUsesInternalServerErrorStatus() {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        jakarta.servlet.http.HttpServletResponse servletResponse = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/missing");

        ResponseEntity<R<Void>> response = handler.handleException(
                new RuntimeException("boom"), request, servletResponse);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().getCode());
    }
}
