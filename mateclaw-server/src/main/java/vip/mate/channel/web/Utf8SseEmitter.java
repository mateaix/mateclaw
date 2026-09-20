package vip.mate.channel.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;

/**
 * RFC-058 PR-1: SSE emitter that explicitly advertises {@code charset=UTF-8}
 * on its {@code Content-Type} header.
 *
 * <p>Solves Chinese character mojibake on:
 * <ul>
 *   <li>Windows / GBK locales whose Chrome / Edge defaults to system codepage
 *       when no explicit charset is given on {@code text/event-stream}</li>
 *   <li>Reverse proxies (Nginx + ICAP, certain corporate gateways) that
 *       transcode according to system locale when no explicit charset is on
 *       the wire</li>
 * </ul>
 *
 * <p>Inspired by joyagent-jdgenie's {@code SseEmitterUTF8} — same one-line
 * idea applied throughout MateClaw's SSE endpoints.
 *
 * <p>Drop-in replacement: every {@code new SseEmitter(timeout)} should become
 * {@code new Utf8SseEmitter(timeout)}.
 *
 * @author MateClaw Team
 */
public class Utf8SseEmitter extends SseEmitter {

    private static final MediaType TEXT_EVENT_STREAM_UTF8 =
            new MediaType("text", "event-stream", StandardCharsets.UTF_8);

    public Utf8SseEmitter() {
        super();
    }

    public Utf8SseEmitter(Long timeoutMillis) {
        super(timeoutMillis);
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
        super.extendResponse(response);
        HttpHeaders headers = response.getHeaders();
        // Streaming frames must reach the client as they are emitted. Nginx
        // honors this response header unless explicitly configured to ignore it.
        headers.set("X-Accel-Buffering", "no");
        headers.setCacheControl("no-store, no-transform");
        // Spring's default sets Content-Type=text/event-stream without charset.
        // Only override when no charset is already specified, so callers that
        // want to roll their own (rare) keep working.
        MediaType contentType = headers.getContentType();
        if (contentType == null || contentType.getCharset() == null) {
            headers.setContentType(TEXT_EVENT_STREAM_UTF8);
        }
    }
}
