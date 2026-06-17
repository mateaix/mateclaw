package vip.mate.memory.service;

/**
 * Deterministic character-budget backstop for always-on memory files
 * (PROFILE.md / MEMORY.md) that are injected into every system prompt.
 * <p>
 * These files are rewritten wholesale by the summarization and emergence passes,
 * which are instructed to stay concise but have no hard ceiling — so on their own
 * they can grow the per-turn context without bound. This enforces a deterministic
 * cap: when content exceeds the budget it is truncated at a Markdown section
 * boundary (keeping the head, where the core/principle sections live) and a marker
 * is appended. The LLM rewrite remains the primary, content-aware compressor; this
 * is the last-resort guarantee that the file stays bounded.
 *
 * @author MateClaw Team
 */
final class AlwaysOnFileBudget {

    /** Appended when content is truncated; user-facing note kept in the file. */
    static final String MARKER = "\n\n> ⚠️ 后续内容已截断以控制注入体积。";

    private AlwaysOnFileBudget() {
    }

    /**
     * Truncate {@code content} to at most {@code maxChars} characters, cutting at
     * the last {@code "## "} section boundary that fits so a section is never split
     * mid-way. Returns the input unchanged when it already fits or when budgeting
     * is disabled ({@code maxChars <= 0}).
     */
    static String enforce(String content, int maxChars) {
        if (content == null || maxChars <= 0 || content.length() <= maxChars) {
            return content;
        }
        int limit = Math.max(0, maxChars - MARKER.length());
        // Prefer cutting at a section boundary within the budget; "\n## " keeps the
        // leading section header intact. Fall back to a hard cut when none fits.
        int boundary = content.lastIndexOf("\n## ", limit);
        String head = boundary > 0 ? content.substring(0, boundary) : content.substring(0, limit);
        return head.stripTrailing() + MARKER;
    }
}
