package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of provider-stated retry windows out of 429/529
 * response headers, straight from the exception chain (both WebFlux and
 * RestClient exception shapes carry their response headers).
 */
class RetryAfterExtractionTest {

    private static Throwable ex429(HttpHeaders headers) {
        return WebClientResponseException.create(
                HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests",
                headers, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Retry-After: 7 (delta-seconds) → 7000ms")
    void deltaSeconds() {
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", "7");
        assertEquals(7_000, NodeStreamingChatHelper.extractRetryAfterMs(ex429(h)));
    }

    @Test
    @DisplayName("Retry-After: HTTP-date → positive delta")
    void httpDate() {
        HttpHeaders h = new HttpHeaders();
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30);
        h.add("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(future));
        long ms = NodeStreamingChatHelper.extractRetryAfterMs(ex429(h));
        assertTrue(ms > 25_000 && ms <= 31_000, "expected ≈30s, got " + ms);
    }

    @Test
    @DisplayName("Anthropic RFC-3339 reset instants → earliest future delta wins")
    void anthropicResetInstant() {
        HttpHeaders h = new HttpHeaders();
        h.add("anthropic-ratelimit-requests-reset", Instant.now().plusSeconds(600).toString());
        h.add("anthropic-ratelimit-tokens-reset", Instant.now().plusSeconds(60).toString());
        long ms = NodeStreamingChatHelper.extractRetryAfterMs(ex429(h));
        assertTrue(ms > 55_000 && ms <= 61_000, "earliest bucket (≈60s) must win, got " + ms);
    }

    @Test
    @DisplayName("OpenAI duration-style x-ratelimit-reset ('6m0s') → 360000ms")
    void openaiDurationStyle() {
        HttpHeaders h = new HttpHeaders();
        h.add("x-ratelimit-reset-requests", "6m0s");
        assertEquals(360_000, NodeStreamingChatHelper.extractRetryAfterMs(ex429(h)));
    }

    @Test
    @DisplayName("Sub-second values clamp up to the 1s floor")
    void subSecondClampsToFloor() {
        HttpHeaders h = new HttpHeaders();
        h.add("x-ratelimit-reset-tokens", "120ms");
        assertEquals(1_000, NodeStreamingChatHelper.extractRetryAfterMs(ex429(h)));
    }

    @Test
    @DisplayName("Absurdly long Retry-After clamps to the 2h ceiling")
    void hugeValueClampsToCeiling() {
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", String.valueOf(7 * 24 * 3600)); // one week
        assertEquals(2 * 60 * 60 * 1000L, NodeStreamingChatHelper.extractRetryAfterMs(ex429(h)));
    }

    @Test
    @DisplayName("No usable headers → 0 (no hint)")
    void noHeadersNoHint() {
        assertEquals(0, NodeStreamingChatHelper.extractRetryAfterMs(ex429(new HttpHeaders())));
        assertEquals(0, NodeStreamingChatHelper.extractRetryAfterMs(new RuntimeException("429 plain")));
    }

    @Test
    @DisplayName("Hint is found through a wrapping cause chain")
    void foundThroughCauseChain() {
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", "5");
        RuntimeException wrapped = new RuntimeException("provider call failed", ex429(h));
        assertEquals(5_000, NodeStreamingChatHelper.extractRetryAfterMs(wrapped));
    }
}
