package vip.mate.stt;

/**
 * Shared sanity checks for STT HTTP response bodies.
 *
 * <p>STT endpoints normally answer JSON, but two real-world failure modes
 * deliver something else with a 200 status: an intercepting proxy / gateway
 * (corporate proxy, captive portal, local traffic tool) substituting an HTML
 * page, or a misconfigured base URL that points at a web UI instead of the
 * transcription API. Feeding such a body straight into Jackson surfaces a raw
 * {@code JsonParseException: Unexpected character ('<'...)} to the end user —
 * with no provider, endpoint, status, or body context, it is undiagnosable
 * (see issue #580 retest reports). Providers use these helpers to detect the
 * situation up front and build an actionable error message instead.
 */
public final class SttResponseDiagnostics {

    /** Cap for the response-body excerpt embedded in error messages. */
    static final int MAX_SNIPPET_CHARS = 200;

    private SttResponseDiagnostics() {
    }

    /**
     * Cheap structural check: does the body plausibly parse as JSON?
     * Tolerates leading whitespace and a UTF-8 BOM. Intentionally does not
     * attempt a full parse — the caller parses right after when this passes.
     */
    public static boolean looksLikeJson(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.trim();
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * Compact single-line excerpt of a response body for logs and error
     * messages: whitespace collapsed, truncated to {@link #MAX_SNIPPET_CHARS}.
     */
    public static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应体)";
        }
        String collapsed = body.trim().replaceAll("\\s+", " ");
        if (collapsed.length() <= MAX_SNIPPET_CHARS) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_SNIPPET_CHARS) + "…";
    }
}
