package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * On 429, logs the outgoing request headers (sanitized), the request body
 * (first {@link #BODY_LOG_LIMIT} bytes), and the {@code anthropic-ratelimit-*}
 * response headers to distinguish three failure modes:
 *
 * <table>
 *   <caption>How to read the response headers</caption>
 *   <tr><th>Failure mode</th><th>tokens-remaining</th><th>retry-after</th></tr>
 *   <tr><td>5h Pro/Max quota exhausted</td><td>0</td><td>thousands of seconds</td></tr>
 *   <tr><td>Anti-abuse fingerprint gate</td><td>(absent)</td><td>(absent)</td></tr>
 *   <tr><td>Per-minute burst limit</td><td>large</td><td>single-digit seconds</td></tr>
 * </table>
 *
 * <p>Sync (RestClient) variant; the WebFlux equivalent is
 * {@link RateLimitDiagnosticExchangeFilter}.
 */
@Slf4j
class RateLimitDiagnosticInterceptor implements ClientHttpRequestInterceptor {

    static final int BODY_LOG_LIMIT = 16384;

    static final List<String> RATE_LIMIT_HEADERS = List.of(
            "anthropic-ratelimit-requests-limit",
            "anthropic-ratelimit-requests-remaining",
            "anthropic-ratelimit-requests-reset",
            "anthropic-ratelimit-tokens-limit",
            "anthropic-ratelimit-tokens-remaining",
            "anthropic-ratelimit-tokens-reset",
            "anthropic-ratelimit-input-tokens-limit",
            "anthropic-ratelimit-input-tokens-remaining",
            "anthropic-ratelimit-input-tokens-reset",
            "anthropic-ratelimit-output-tokens-limit",
            "anthropic-ratelimit-output-tokens-remaining",
            "anthropic-ratelimit-output-tokens-reset",
            "retry-after");

    static final List<String> REQUEST_HEADERS_TO_LOG = List.of(
            "authorization",
            "user-agent",
            "accept",
            "x-app",
            "anthropic-beta",
            "anthropic-version",
            "anthropic-dangerous-direct-browser-access");

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() == 429) {
            logRequestHeaders(request.getHeaders());
            logRequestBody(body);
            logResponseHeaders(response.getHeaders());
        }
        return response;
    }

    static void logRequestHeaders(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder("[Anthropic 429] outgoing request headers (sanitized): ");
        boolean first = true;
        for (String name : REQUEST_HEADERS_TO_LOG) {
            String value = headers.getFirst(name);
            if (value == null) continue;
            if (!first) sb.append(", ");
            first = false;
            if ("authorization".equalsIgnoreCase(name) && value.startsWith("Bearer ")) {
                sb.append(name).append("=Bearer <redacted>");
            } else {
                sb.append(name).append('=').append(value);
            }
        }
        log.warn(sb.toString());
    }

    static void logRequestBody(byte[] body) {
        if (body == null || body.length == 0) {
            log.warn("[Anthropic 429] request body: (empty)");
            return;
        }
        int len = Math.min(body.length, BODY_LOG_LIMIT);
        log.warn("[Anthropic 429] request body (first {} of {} bytes): {}",
                len, body.length, new String(body, 0, len, StandardCharsets.UTF_8));
    }

    static void logResponseHeaders(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder("[Anthropic 429] rate-limit response headers: ");
        boolean any = false;
        for (String name : RATE_LIMIT_HEADERS) {
            String value = headers.getFirst(name);
            if (value != null) {
                if (any) sb.append(", ");
                sb.append(name).append('=').append(value);
                any = true;
            }
        }
        if (!any) {
            log.warn("[Anthropic 429] no rate-limit response headers — likely anti-abuse gate, not real quota");
        } else {
            log.warn(sb.toString());
        }
    }
}
