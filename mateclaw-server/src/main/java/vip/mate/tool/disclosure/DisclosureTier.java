package vip.mate.tool.disclosure;

/**
 * Progressive tool disclosure tier.
 * <ul>
 *   <li>{@link #CORE} — always advertised to the LLM.</li>
 *   <li>{@link #EXTENSION} — hidden behind the extension-tools catalog until
 *       the model calls {@code enable_tool}, which activates it for the rest of
 *       the conversation.</li>
 * </ul>
 */
public enum DisclosureTier {
    CORE,
    EXTENSION;

    /** Token stored in DB columns / accepted by the PATCH endpoints. */
    public String token() {
        return name().toLowerCase();
    }

    /**
     * Parse a stored tier token. Anything other than a case-insensitive
     * {@code "extension"} maps to {@link #CORE} — including {@code null} / blank,
     * so a row whose column was never set is treated as core.
     */
    public static DisclosureTier fromToken(String token) {
        return token != null && "extension".equalsIgnoreCase(token.trim())
                ? EXTENSION : CORE;
    }

    /** True for the two valid tokens, used to validate PATCH input. */
    public static boolean isValidToken(String token) {
        return token != null
                && ("core".equalsIgnoreCase(token.trim()) || "extension".equalsIgnoreCase(token.trim()));
    }
}
