package vip.mate.agent.runtime.dsh.management;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DshManagementStateTest {

    @Test
    void runtimeCannotBeEnabledBeforeVerificationIsReady() {
        assertFalse(DshManagementState.CONFIG_INVALID.canEnable());
        assertFalse(DshManagementState.CHECK_FAILED.canEnable());
        assertTrue(DshManagementState.READY.canEnable());
    }

    @Test
    void installationAndVerificationStatesAreNotConfusedWithEnabled() {
        assertFalse(DshManagementState.NOT_INSTALLED.isOperational());
        assertFalse(DshManagementState.INSTALLED_UNCONFIGURED.isOperational());
        assertFalse(DshManagementState.READY.isOperational());
        assertTrue(DshManagementState.ENABLED.isOperational());
    }
}
