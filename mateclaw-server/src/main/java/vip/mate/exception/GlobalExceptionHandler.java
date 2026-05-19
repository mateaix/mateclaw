package vip.mate.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vip.mate.common.result.R;
import vip.mate.i18n.I18nService;
import vip.mate.skill.lifecycle.ConfirmRequiredException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler.
 * <p>
 * MateClawException messages are translated through I18nService when the
 * exception provides a message key. The JSON body keeps the project-wide
 * R envelope while the HTTP status mirrors the failure class.
 *
 * @author MateClaw Team
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final I18nService i18nService;

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<R<Void>> handleAsyncTimeout(AsyncRequestTimeoutException e,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        if (isSseRequest(request) || response.isCommitted()) {
            log.debug("SSE async timeout (normal lifecycle): {} {}", request.getMethod(), request.getRequestURI());
            // Return no body for SSE so the framework can end the async request.
            return null;
        }
        log.warn("Async request timeout: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(R.fail(503, "Request timeout, please try again"));
    }

    @ExceptionHandler(MateClawException.class)
    public ResponseEntity<R<Void>> handleMateClawException(MateClawException e) {
        log.warn("Business exception: [{}] {}", e.getCode(), e.getMessage());
        String msg = translateExceptionMsg(e);
        return ResponseEntity.status(httpStatusForCode(e.getCode())).body(R.fail(e.getCode(), msg));
    }

    /**
     * Translate exception message via i18n.
     * If the exception has a msgKey, use it to look up the translated message.
     * Otherwise return the original message as-is.
     */
    private String translateExceptionMsg(MateClawException e) {
        String msgKey = e.getMsgKey();
        if (msgKey != null && !msgKey.isEmpty()) {
            String translated = i18nService.msg(msgKey);
            if (!translated.equals(msgKey)) {
                return translated;
            }
        }
        return e.getMessage();
    }

    /**
     * A mutation that needs a second, explicit confirmation. Returns a real
     * HTTP 409 with a structured body so the client can branch on the status
     * code and render a confirm dialog from {@code boundAgents}.
     */
    @ExceptionHandler(ConfirmRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleConfirmRequired(ConfirmRequiredException e) {
        log.info("Confirm required: [{}] {}", e.getCode(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        body.put("boundAgents", e.getBoundAgents());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation failed: {}", msg);
        return ResponseEntity.badRequest().body(R.fail(400, msg));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFound(NoResourceFoundException e,
                                                         HttpServletRequest request) {
        log.warn("Resource not found: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "Resource not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        if (response.isCommitted() || isSseRequest(request)) {
            log.warn("Exception after response committed or during SSE (suppressed): {} {} - {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
            return null;
        }
        log.error("Unexpected error: {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail("Internal server error"));
    }

    /**
     * Identify SSE requests from the Accept header, Content-Type, or known stream path.
     */
    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        // Fallback path match for stream endpoints that omit explicit headers.
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/chat/stream");
    }

    private HttpStatus httpStatusForCode(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
