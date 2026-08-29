package vip.mate.tool;

/**
 * Signals an LLM-correctable tool argument error whose message is safe to
 * return to the model, including for {@code returnDirect} tools.
 *
 * <p>Ordinary exceptions from direct tools remain redacted because they can
 * contain credentials, connection strings, or other sensitive internals.
 */
public class ToolInputValidationException extends RuntimeException {

    public ToolInputValidationException(String message) {
        super(message);
    }

    public ToolInputValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
