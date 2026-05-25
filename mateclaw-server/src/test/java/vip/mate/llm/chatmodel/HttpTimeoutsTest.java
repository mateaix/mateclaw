package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RFC-03 Lane B1 — covers {@link HttpTimeouts#resolveReadTimeout(Integer)},
 * the central resolver that backs {@code mate_model_config.request_timeout_seconds}.
 *
 * <p>Behavioral contract under test:
 * <ul>
 *   <li>null / non-positive → 180s (the historical hardcoded default; preserves
 *       behavior for every existing row before V75 ran).</li>
 *   <li>positive integer → that many seconds, no clamp (caller decides
 *       reasonable upper bound at the model-config level — we don't want to
 *       silently rewrite a user's deliberate 30-min override).</li>
 *   <li>connect timeout stays at 10s and is never overridable — long-tail
 *       latency manifests on the read path, not on connect.</li>
 * </ul>
 */
class HttpTimeoutsTest {

    @Test
    @DisplayName("null override → default 180s read timeout")
    void nullFallsBack() {
        assertEquals(Duration.ofSeconds(180),
                HttpTimeouts.resolveReadTimeout(null));
    }

    @Test
    @DisplayName("zero → default 180s (treated as unset)")
    void zeroFallsBack() {
        assertEquals(Duration.ofSeconds(180),
                HttpTimeouts.resolveReadTimeout(0));
    }

    @Test
    @DisplayName("negative → default 180s (defensively treats nonsense values as unset)")
    void negativeFallsBack() {
        assertEquals(Duration.ofSeconds(180),
                HttpTimeouts.resolveReadTimeout(-30));
    }

    @Test
    @DisplayName("positive integer → exact seconds, no clamp on either side")
    void positiveHonored() {
        assertEquals(Duration.ofSeconds(30),
                HttpTimeouts.resolveReadTimeout(30));
        assertEquals(Duration.ofSeconds(600),
                HttpTimeouts.resolveReadTimeout(600));
        // o1-pro / claude opus extended-thinking can legitimately need 30 min.
        assertEquals(Duration.ofSeconds(1800),
                HttpTimeouts.resolveReadTimeout(1800));
    }

    @Test
    @DisplayName("connect timeout is the canonical 10s")
    void connectTimeoutIsCanonical() {
        assertEquals(Duration.ofSeconds(10), HttpTimeouts.CONNECT_TIMEOUT);
    }

    @Test
    @DisplayName("default read timeout matches the legacy hardcoded 180s")
    void defaultMatchesLegacy() {
        assertEquals(Duration.ofSeconds(180), HttpTimeouts.DEFAULT_READ_TIMEOUT);
    }
}
