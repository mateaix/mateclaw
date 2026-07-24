package vip.mate.plugin.mem0;

/**
 * Raised when a Mem0 REST call fails (non-2xx response, IO error, timeout).
 * <p>
 * Caught and logged by {@link Mem0Provider} so that Mem0 outages degrade
 * gracefully (empty recall / dropped sync) without affecting the agent's
 * response path.
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
