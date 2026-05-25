package vip.mate.channel.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-058 PR-1: ensure {@link Utf8SseEmitter} explicitly stamps
 * {@code Content-Type: text/event-stream;charset=UTF-8} on the response.
 *
 * <p>Spring's default {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
 * leaves the charset off, which on Windows / GBK locale Chrome and through
 * certain reverse proxies leads to mojibake for Chinese characters.
 */
class Utf8SseEmitterTest {

    @Test
    @DisplayName("extendResponse stamps charset=UTF-8 when Content-Type is unset")
    void stampsUtf8WhenContentTypeUnset() throws Exception {
        Utf8SseEmitter emitter = new Utf8SseEmitter(10_000L);
        MockHttpServletResponse servlet = new MockHttpServletResponse();
        ServerHttpResponse response = new ServletServerHttpResponse(servlet);

        invokeExtendResponse(emitter, response);

        MediaType contentType = response.getHeaders().getContentType();
        assertNotNull(contentType, "Content-Type must be set");
        assertEquals("text", contentType.getType());
        assertEquals("event-stream", contentType.getSubtype());
        assertEquals(StandardCharsets.UTF_8, contentType.getCharset(),
                "charset must be explicitly UTF-8 (not null)");
    }

    @Test
    @DisplayName("extendResponse stamps charset=UTF-8 when Content-Type lacks charset")
    void stampsUtf8WhenContentTypeMissingCharset() throws Exception {
        Utf8SseEmitter emitter = new Utf8SseEmitter(10_000L);
        MockHttpServletResponse servlet = new MockHttpServletResponse();
        ServerHttpResponse response = new ServletServerHttpResponse(servlet);
        // Simulate Spring default: text/event-stream WITHOUT charset
        response.getHeaders().setContentType(MediaType.parseMediaType("text/event-stream"));

        invokeExtendResponse(emitter, response);

        MediaType contentType = response.getHeaders().getContentType();
        assertNotNull(contentType.getCharset(), "charset must be filled in");
        assertEquals(StandardCharsets.UTF_8, contentType.getCharset());
    }

    @Test
    @DisplayName("extendResponse does NOT override an explicit non-UTF8 charset")
    void doesNotClobberExplicitCharset() throws Exception {
        Utf8SseEmitter emitter = new Utf8SseEmitter(10_000L);
        MockHttpServletResponse servlet = new MockHttpServletResponse();
        ServerHttpResponse response = new ServletServerHttpResponse(servlet);
        // Caller explicitly chose ISO-8859-1 — we must respect it
        MediaType iso = new MediaType("text", "event-stream", StandardCharsets.ISO_8859_1);
        response.getHeaders().setContentType(iso);

        invokeExtendResponse(emitter, response);

        MediaType contentType = response.getHeaders().getContentType();
        assertEquals(StandardCharsets.ISO_8859_1, contentType.getCharset(),
                "Explicit caller-set charset must not be overridden");
    }

    @Test
    @DisplayName("Utf8SseEmitter constructor accepts timeout like SseEmitter")
    void constructorAcceptsTimeout() {
        Utf8SseEmitter emitter = new Utf8SseEmitter(60_000L);
        assertEquals(60_000L, emitter.getTimeout());
    }

    @Test
    @DisplayName("Default constructor works (no timeout)")
    void defaultConstructorWorks() {
        Utf8SseEmitter emitter = new Utf8SseEmitter();
        assertNull(emitter.getTimeout(), "Default constructor leaves timeout null");
    }

    /**
     * {@code extendResponse} is {@code protected} on the framework class.
     * Reflection is the cleanest way to exercise it without spinning up a
     * full DispatcherServlet for a one-line behavioural assertion.
     */
    private static void invokeExtendResponse(Utf8SseEmitter emitter, ServerHttpResponse response)
            throws Exception {
        Method m = findExtendResponseMethod(emitter.getClass());
        m.setAccessible(true);
        m.invoke(emitter, response);
    }

    private static Method findExtendResponseMethod(Class<?> cls) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if ("extendResponse".equals(m.getName())) return m;
            }
        }
        throw new NoSuchMethodException("extendResponse not found on " + cls);
    }
}
