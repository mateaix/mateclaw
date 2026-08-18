package vip.mate.agent.graph;

/**
 * Incremental extractor that routes inline {@code <think>...</think>} spans
 * out of a streamed content channel and into a thinking channel, chunk by
 * chunk. Models without structured reasoning support emit their reasoning
 * inline in the content stream; without live extraction the raw tags reach
 * the user during streaming and only disappear after the persisted (cleaned)
 * message is reloaded.
 * <p>
 * A tag may be split across chunk boundaries ({@code "abc<thi"} +
 * {@code "nk>xyz"}). The extractor holds back a chunk tail that is a proper
 * prefix of the next expected tag (at most {@code </think>.length() - 1}
 * characters) and re-examines it with the following chunk, so the hold-back
 * buffer is O(1). Call {@link #flush()} once the stream ends to drain that
 * tail: in text mode it is returned as content, inside an unclosed
 * {@code <think>} it is returned as thinking — matching the post-stream
 * fallback parser's semantics for unterminated tags.
 * <p>
 * Not thread-safe. One instance per streamed LLM call; Reactor serializes
 * {@code doOnNext} so no synchronization is needed.
 */
final class ThinkTagStreamExtractor {

    /** Split result of one {@link #feed} / {@link #flush} call; fields are never null. */
    record Extracted(String content, String thinking) {
        static final Extracted EMPTY = new Extracted("", "");
    }

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    /** Carry-over between chunks: a chunk tail that may still become a tag. */
    private final StringBuilder pending = new StringBuilder();
    private boolean insideThink;
    private boolean disabled;

    /**
     * Turn extraction off for the rest of the stream. Called when structured
     * reasoning content shows up — such a model never tag-wraps its thinking,
     * so any literal tag text in the answer is real content. Thinking already
     * extracted stays extracted; a held-back tail is returned as content on
     * the next {@link #feed} / {@link #flush}.
     */
    void disable() {
        disabled = true;
    }

    /** Split one content chunk into its content and thinking parts. */
    Extracted feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return Extracted.EMPTY;
        }
        if (disabled) {
            if (pending.isEmpty()) {
                return new Extracted(chunk, "");
            }
            String held = pending.toString();
            pending.setLength(0);
            return new Extracted(held + chunk, "");
        }
        pending.append(chunk);
        String buf = pending.toString();
        pending.setLength(0);

        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int i = 0;
        while (i < buf.length()) {
            String tag = insideThink ? CLOSE_TAG : OPEN_TAG;
            StringBuilder out = insideThink ? thinking : content;
            int idx = buf.indexOf(tag, i);
            if (idx >= 0) {
                out.append(buf, i, idx);
                i = idx + tag.length();
                insideThink = !insideThink;
            } else {
                int hold = holdbackStart(buf, i, tag);
                out.append(buf, i, hold);
                pending.append(buf, hold, buf.length());
                break;
            }
        }
        return new Extracted(content.toString(), thinking.toString());
    }

    /**
     * Drain the held-back tail once the stream is over. Inside an unclosed
     * {@code <think>} the remainder counts as thinking, otherwise as content.
     */
    Extracted flush() {
        if (pending.isEmpty()) {
            return Extracted.EMPTY;
        }
        String rest = pending.toString();
        pending.setLength(0);
        return insideThink ? new Extracted("", rest) : new Extracted(rest, "");
    }

    /**
     * Smallest index {@code s >= from} such that {@code buf[s..)} is a
     * non-empty proper prefix of {@code tag}; {@code buf.length()} when the
     * tail cannot start a tag. Only the last {@code tag.length() - 1} chars
     * can qualify — a full tag would have been found by {@code indexOf}.
     */
    private static int holdbackStart(String buf, int from, String tag) {
        int len = buf.length();
        int earliest = Math.max(from, len - tag.length() + 1);
        for (int s = earliest; s < len; s++) {
            if (isProperPrefixOfTag(buf, s, tag)) {
                return s;
            }
        }
        return len;
    }

    private static boolean isProperPrefixOfTag(String buf, int start, String tag) {
        int n = buf.length() - start;
        if (n <= 0 || n >= tag.length()) {
            return false;
        }
        for (int k = 0; k < n; k++) {
            if (buf.charAt(start + k) != tag.charAt(k)) {
                return false;
            }
        }
        return true;
    }
}
