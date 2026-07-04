package vip.mate.tool.local;

/**
 * Raised when a {@code local_*} tool cannot reach the user's desktop tunnel.
 * The {@link Code} drives the friendly message surfaced back to the agent.
 *
 * @author MateClaw Team
 */
public class DesktopBridgeException extends RuntimeException {

    public enum Code {
        /** No desktop tunnel is connected for the requesting user. */
        OFFLINE,
        /** The connected desktop is too old to honor the requested capability. */
        UNSUPPORTED,
        /** The desktop did not reply within the call timeout. */
        TIMEOUT,
        /** The requesting user could not be resolved from the tool context. */
        NO_USER
    }

    private final Code code;

    public DesktopBridgeException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
