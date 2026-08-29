package vip.mate.agent.runtime.dsh.management;

/** Lifecycle states exposed by the DSH runtime management screen. */
public enum DshManagementState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED_UNCONFIGURED,
    CONFIG_INVALID,
    CHECKING,
    CHECK_FAILED,
    READY,
    ENABLED;

    public boolean canEnable() {
        return this == READY;
    }

    public boolean isOperational() {
        return this == ENABLED;
    }
}
