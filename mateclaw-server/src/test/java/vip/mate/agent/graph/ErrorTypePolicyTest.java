package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.NodeStreamingChatHelper.ErrorType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven assertions over every {@link ErrorType}'s
 * recovery-policy attributes. The retry loop, fallback router, pool eviction
 * and health tracker all consume these attributes, so a mis-configured new
 * constant would silently change failover behavior — this test makes any
 * change to the policy table an explicit, reviewed diff.
 */
class ErrorTypePolicyTest {

    /** One row per constant: {type, retryBudget, failsOver, evictsProvider, countsHealth}. */
    private static final Object[][] POLICY_TABLE = {
            {ErrorType.NONE,                 0,  false, false, false},
            {ErrorType.RATE_LIMIT,           2,  true,  false, true },
            {ErrorType.OVERLOADED,           5,  true,  false, false},
            {ErrorType.SERVER_ERROR,         10, true,  false, true },
            {ErrorType.PROMPT_TOO_LONG,      0,  false, false, false},
            {ErrorType.AUTH_ERROR,           0,  true,  true,  true },
            {ErrorType.CLIENT_ERROR,         0,  false, false, false},
            {ErrorType.THINKING_BLOCK_ERROR, 1,  false, false, false},
            {ErrorType.EMPTY_RESPONSE,       3,  true,  false, true },
            {ErrorType.BILLING,              0,  true,  true,  true },
            {ErrorType.MODEL_NOT_FOUND,      0,  true,  false, false},
            {ErrorType.UNKNOWN,              5,  true,  false, true },
    };

    @Test
    @DisplayName("Every ErrorType constant appears in the policy table exactly once")
    void tableCoversEveryConstant() {
        assertEquals(ErrorType.values().length, POLICY_TABLE.length,
                "A new ErrorType constant was added without a policy-table row — "
                        + "add it here so its recovery policy is an explicit, reviewed decision");
    }

    @Test
    @DisplayName("Policy attributes match the table")
    void attributesMatchTable() {
        for (Object[] row : POLICY_TABLE) {
            ErrorType t = (ErrorType) row[0];
            assertEquals((int) row[1], t.retryBudget(), t + ".retryBudget");
            assertEquals(row[2], t.failsOver(), t + ".failsOver");
            assertEquals(row[3], t.evictsProvider(), t + ".evictsProvider");
            assertEquals(row[4], t.countsHealth(), t + ".countsHealth");
        }
    }

    @Test
    @DisplayName("Evicting types always count toward provider health")
    void evictingImpliesHealth() {
        // A HARD-evicting failure is by definition a provider-level failure;
        // an evicting type that skips health tracking would TTL-readmit into
        // a tracker that never saw the incident.
        for (ErrorType t : ErrorType.values()) {
            if (t.evictsProvider()) {
                assertTrue(t.countsHealth(), t + " evicts the provider but does not count health");
            }
        }
    }

    @Test
    @DisplayName("OVERLOADED never dents provider health or pool membership")
    void overloadedIsHealthNeutral() {
        // A saturated provider is busy, not broken: eviction or cooldown would
        // take a healthy provider out of the chain exactly when every other
        // conversation needs it most.
        assertFalse(ErrorType.OVERLOADED.evictsProvider());
        assertFalse(ErrorType.OVERLOADED.countsHealth());
        assertTrue(ErrorType.OVERLOADED.failsOver());
    }
}
