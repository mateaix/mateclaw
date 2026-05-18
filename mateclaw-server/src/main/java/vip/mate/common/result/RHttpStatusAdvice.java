package vip.mate.common.result;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Align HTTP status codes with the project-wide {@link R} response envelope.
 */
@ControllerAdvice
public class RHttpStatusAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof R<?> envelope && envelope.getCode() != ResultCode.SUCCESS.getCode()) {
            HttpStatus status = HttpStatus.resolve(envelope.getCode());
            if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
            if (response instanceof ServletServerHttpResponse servletResponse
                    && !servletResponse.getServletResponse().isCommitted()) {
                servletResponse.getServletResponse().setStatus(status.value());
            } else {
                response.setStatusCode(status);
            }
        }
        return body;
    }
}
