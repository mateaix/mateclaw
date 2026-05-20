package vip.mate.channel.media;

/**
 * Thrown by {@link MediaUploader#upload(MediaUploadRequest)} when the
 * upload cannot complete — credential issues, oversize rejection,
 * platform API failure, or local I/O while staging the payload.
 *
 * <p>Distinct from {@link IllegalArgumentException} (caller's fault)
 * and unchecked runtime errors (programmer bugs). Catching code is
 * expected to log and fall back to a textual notice to the user
 * rather than crash the adapter's send loop.
 */
public class MediaUploadException extends Exception {

    public MediaUploadException(String message) {
        super(message);
    }

    public MediaUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
