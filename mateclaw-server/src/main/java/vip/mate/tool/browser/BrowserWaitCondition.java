package vip.mate.tool.browser;

/**
 * Parsed, bounded wait request for browser_use action=wait_for.
 */
public record BrowserWaitCondition(Kind kind, String target, int timeoutMillis) {

    public enum Kind {
        SELECTOR,
        TEXT,
        URL,
        LOAD_STATE
    }

    public static BrowserWaitCondition parse(String condition, String selector, String text, String value,
                                             Integer timeoutSeconds, int maxTimeoutSeconds) {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("condition is required for action=wait_for");
        }
        Kind kind = switch (condition.trim().toLowerCase()) {
            case "selector" -> Kind.SELECTOR;
            case "text" -> Kind.TEXT;
            case "url" -> Kind.URL;
            case "load_state", "loadstate", "state" -> Kind.LOAD_STATE;
            default -> throw new IllegalArgumentException("Unknown wait_for condition: " + condition
                    + ". Supported: selector, text, url, load_state");
        };

        String target = switch (kind) {
            case SELECTOR -> firstNonBlank(selector);
            case TEXT -> firstNonBlank(text, value);
            case URL -> firstNonBlank(value, text);
            case LOAD_STATE -> firstNonBlank(value, text);
        };
        if (target == null) {
            throw new IllegalArgumentException("Target is required for wait_for condition=" + condition);
        }

        int max = Math.max(1, maxTimeoutSeconds);
        int requested = timeoutSeconds == null ? max : Math.max(1, timeoutSeconds);
        return new BrowserWaitCondition(kind, target, Math.min(requested, max) * 1000);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
