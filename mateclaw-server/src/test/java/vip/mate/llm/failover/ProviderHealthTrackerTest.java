package vip.mate.llm.failover;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-009 P3.3: per-provider failure-count + cooldown logic.
 */
class ProviderHealthTrackerTest {

    private ProviderHealthProperties props;
    private ProviderHealthTracker tracker;

    @BeforeEach
    void setUp() {
        props = new ProviderHealthProperties();
        props.setFailureThreshold(3);
        props.setCooldownMs(60_000L);
        tracker = new ProviderHealthTracker(props);
    }

    @Test
    @DisplayName("New provider is not in cooldown")
    void newProviderNotInCooldown() {
        assertFalse(tracker.isInCooldown("openai"));
    }

    @Test
    @DisplayName("Failures below threshold do not trigger cooldown")
    void belowThresholdNoCooldown() {
        tracker.recordFailure("openai");
        tracker.recordFailure("openai");
        assertFalse(tracker.isInCooldown("openai"),
                "two failures < threshold of 3 must not enter cooldown");
    }

    @Test
    @DisplayName("Failures hitting threshold enter cooldown")
    void thresholdReachedTriggersCooldown() {
        tracker.recordFailure("openai");
        tracker.recordFailure("openai");
        tracker.recordFailure("openai");
        assertTrue(tracker.isInCooldown("openai"),
                "third failure must enter cooldown");
    }

    @Test
    @DisplayName("Success resets failure counter and clears cooldown")
    void successResetsCounterAndCooldown() {
        tracker.recordFailure("openai");
        tracker.recordFailure("openai");
        tracker.recordFailure("openai");
        assertTrue(tracker.isInCooldown("openai"));

        tracker.recordSuccess("openai");
        assertFalse(tracker.isInCooldown("openai"),
                "success must clear cooldown so the provider becomes eligible again");
    }

    @Test
    @DisplayName("After cooldown expires, provider becomes eligible again")
    void cooldownExpires() throws Exception {
        // Bypass the min-1000ms clamp in setCooldownMs via reflection — the
        // clamp is there to prevent prod misconfiguration, but for this test
        // we want a fast-expiring window to avoid sleeping 1+ seconds.
        java.lang.reflect.Field f = ProviderHealthProperties.class.getDeclaredField("cooldownMs");
        f.setAccessible(true);
        f.setLong(props, 50L);

        for (int i = 0; i < 3; i++) tracker.recordFailure("openai");
        assertTrue(tracker.isInCooldown("openai"), "sanity: still in cooldown right after trigger");
        Thread.sleep(120);
        assertFalse(tracker.isInCooldown("openai"),
                "cooldown should expire once the window has passed");
    }

    @Test
    @DisplayName("Disabled tracker never reports cooldown")
    void disabledTrackerInert() {
        props.setEnabled(false);
        for (int i = 0; i < 10; i++) tracker.recordFailure("openai");
        assertFalse(tracker.isInCooldown("openai"),
                "disabled tracker must report no cooldown regardless of failures");
    }

    @Test
    @DisplayName("Null providerId is a safe no-op")
    void nullProviderIdSafe() {
        tracker.recordFailure(null);
        tracker.recordSuccess(null);
        assertFalse(tracker.isInCooldown(null),
                "null providerId must not crash and must report no cooldown");
    }

    @Test
    @DisplayName("Per-provider isolation: cooldown on A does not affect B")
    void perProviderIsolation() {
        for (int i = 0; i < 3; i++) tracker.recordFailure("openai");
        assertTrue(tracker.isInCooldown("openai"));
        assertFalse(tracker.isInCooldown("dashscope"),
                "cooldown must be scoped per provider id");
    }

    @Test
    @DisplayName("Snapshot reports both failure count and remaining cooldown")
    void snapshotReportsState() {
        for (int i = 0; i < 3; i++) tracker.recordFailure("openai");
        var snap = tracker.snapshot();
        assertNotNull(snap.get("openai"));
        assertEquals(3L, snap.get("openai").consecutiveFailures());
        assertTrue(snap.get("openai").cooldownRemainingMs() > 0,
                "cooldown remaining ms must be positive while active");
    }

    // ===== Provider-stated cooldown override (Retry-After feedback) =====

    @Test
    @DisplayName("Cooldown override fires immediately, without waiting for the threshold")
    void overrideBypassesThreshold() {
        tracker.recordFailure("openai", 60_000);
        assertTrue(tracker.isInCooldown("openai"),
                "one failure with a stated retry window must start cooldown right away");
        long remaining = tracker.snapshot().get("openai").cooldownRemainingMs();
        assertTrue(remaining > 55_000 && remaining <= 60_000,
                "cooldown must honor the stated window, got " + remaining);
    }

    @Test
    @DisplayName("Override clamps to the 2h ceiling")
    void overrideClampedToCeiling() {
        tracker.recordFailure("openai", 24L * 3600 * 1000);
        long remaining = tracker.snapshot().get("openai").cooldownRemainingMs();
        assertTrue(remaining <= ProviderHealthTracker.MAX_COOLDOWN_OVERRIDE_MS,
                "override must clamp at 2h, got " + remaining);
    }

    @Test
    @DisplayName("Override never shortens a longer active cooldown")
    void overrideNeverShortens() {
        tracker.recordFailure("openai", 3600_000);
        tracker.recordFailure("openai", 5_000);
        long remaining = tracker.snapshot().get("openai").cooldownRemainingMs();
        assertTrue(remaining > 5_000,
                "a later, shorter window must not truncate the active cooldown, got " + remaining);
    }

    @Test
    @DisplayName("Success clears an override cooldown like any other")
    void successClearsOverride() {
        tracker.recordFailure("openai", 3600_000);
        assertTrue(tracker.isInCooldown("openai"));
        tracker.recordSuccess("openai");
        assertFalse(tracker.isInCooldown("openai"));
    }
}
