package vip.mate.plugin.mem0;

/**
 * Raised when a Mem0 REST call fails (non-2xx response, IO error, timeout).
 * <p>
 * Sync failures are caught by {@link Mem0Provider}; recall failures propagate
 * to the platform provider boundary for timeout/circuit-breaker accounting.
 *
 * @author MateClaw Team
 */
class Mem0Exception extends RuntimeException {

    Mem0Exception(String message) {
        super(message);
    }

    Mem0Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
