package vip.mate.plugin.yousearch;

/**
 * Thrown when a You.com Search API call fails (non-2xx or transport error).
 * <p>
 * The platform's provider chain treats a thrown provider as failed and falls
 * through to the next provider, so this exception is the failure signal —
 * callers must not swallow it.
 *
 * @author Mouse Parker
 */
class YouSearchException extends RuntimeException {

    YouSearchException(String message) {
        super(message);
    }

    YouSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
