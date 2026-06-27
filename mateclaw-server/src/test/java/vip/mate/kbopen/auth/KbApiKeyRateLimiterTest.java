package vip.mate.kbopen.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KbApiKeyRateLimiter} (R2): sliding-window admission control.
 */
class KbApiKeyRateLimiterTest {

    @Test
    @DisplayName("admits up to limit, then rejects")
    void admitsUpToLimitThenRejects() {
        KbApiKeyRateLimiter limiter = new KbApiKeyRateLimiter();
        Instant now = Instant.now();

        assertThat(limiter.tryAcquire(1L, 3, now)).isTrue();
        assertThat(limiter.tryAcquire(1L, 3, now)).isTrue();
        assertThat(limiter.tryAcquire(1L, 3, now)).isTrue();
        assertThat(limiter.tryAcquire(1L, 3, now)).isFalse(); // 4th rejected
    }

    @Test
    @DisplayName("limits are per-key (different keys have independent windows)")
    void perKeyIsolation() {
        KbApiKeyRateLimiter limiter = new KbApiKeyRateLimiter();
        Instant now = Instant.now();

        limiter.tryAcquire(1L, 2, now);
        limiter.tryAcquire(1L, 2, now);

        // Key 1 is full, but key 2 is independent
        assertThat(limiter.tryAcquire(1L, 2, now)).isFalse();
        assertThat(limiter.tryAcquire(2L, 2, now)).isTrue();
    }

    @Test
    @DisplayName("expired entries are purged — window recovers over time")
    void windowRecoversAfterExpiry() {
        KbApiKeyRateLimiter limiter = new KbApiKeyRateLimiter();
        Instant now = Instant.now();

        limiter.tryAcquire(1L, 2, now);
        limiter.tryAcquire(1L, 2, now);
        assertThat(limiter.tryAcquire(1L, 2, now)).isFalse();

        // 61 seconds later, the window entries have expired
        Instant later = now.plusSeconds(61);
        assertThat(limiter.tryAcquire(1L, 2, later)).isTrue();
    }

    @Test
    @DisplayName("limit <= 0 disables rate limiting")
    void zeroLimitDisables() {
        KbApiKeyRateLimiter limiter = new KbApiKeyRateLimiter();
        Instant now = Instant.now();

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire(1L, 0, now)).isTrue();
        }
    }
}
