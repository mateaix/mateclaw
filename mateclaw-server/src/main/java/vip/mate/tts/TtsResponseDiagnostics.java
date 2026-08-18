package vip.mate.tts;

/**
 * Compact diagnostics for HTTP-based TTS provider failures.
 */
public final class TtsResponseDiagnostics {

    static final int MAX_SNIPPET_CHARS = 200;

    private TtsResponseDiagnostics() {
    }

    public static String failureMessage(String provider, String endpoint, int status, String body) {
        return provider + " 失败: endpoint=" + endpoint
                + ", status=" + status
                + ", body=" + snippet(body);
    }

    public static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应体)";
        }
        String collapsed = body.trim()
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("\\s+", " ");
        if (collapsed.length() <= MAX_SNIPPET_CHARS) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_SNIPPET_CHARS) + "...";
    }
}
