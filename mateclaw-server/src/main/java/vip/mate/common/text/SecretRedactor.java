package vip.mate.common.text;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort masking of credential-shaped substrings in free text.
 *
 * <p>Intended for text that is about to be <em>copied</em> out of its original
 * store — persisted into a second table, rendered in an admin screen, or sent
 * to a model. A secret that already sits in a conversation row is exposed
 * exactly once; duplicating it into a new location multiplies the places it
 * can leak from and outlives any later cleanup of the original.
 *
 * <p>Deliberately conservative: it matches shapes that are almost always
 * credentials (provider key prefixes, explicit {@code key=value} assignments,
 * bearer headers) rather than anything high-entropy. Over-matching would
 * quietly destroy the words that make a request recognisable, and this text is
 * used to tell one routine from another. This reduces exposure; it is not a
 * guarantee, and it is not a substitute for keeping secrets out of chat.
 *
 * @author MateClaw Team
 */
public final class SecretRedactor {

    /** Replacement for any matched credential. */
    public static final String MASK = "[redacted]";

    private static final List<Pattern> PATTERNS = List.of(
            // Provider key prefixes: OpenAI (incl. sk-proj-), Anthropic, GitHub,
            // Slack, Google, AWS access key ids.
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{16,}"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            // Authorization headers.
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
            // Explicit assignments — keep the field name, mask only the value,
            // so "api_key = [redacted]" still reads as what it was.
            Pattern.compile("(?i)\\b(api[_-]?key|access[_-]?token|auth[_-]?token|secret[_-]?key"
                    + "|client[_-]?secret|password|passwd|token|secret)\\b\\s*[:=]\\s*"
                    + "[\"']?[^\\s\"',;]{6,}[\"']?")
    );

    /** Index of the field-name group in the assignment pattern above. */
    private static final int ASSIGNMENT_PATTERN_INDEX = PATTERNS.size() - 1;

    private SecretRedactor() {
    }

    /**
     * Mask credential-shaped substrings.
     *
     * @param text input; {@code null} is returned unchanged
     * @return the text with credentials replaced by {@link #MASK}
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (int i = 0; i < PATTERNS.size(); i++) {
            out = i == ASSIGNMENT_PATTERN_INDEX
                    ? PATTERNS.get(i).matcher(out).replaceAll("$1=" + MASK)
                    : PATTERNS.get(i).matcher(out).replaceAll(MASK);
        }
        return out;
    }
}
