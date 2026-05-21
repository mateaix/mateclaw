package vip.mate.agent.context;

/**
 * Boundary-aware text truncation.
 *
 * <p>Character-count truncation that lands inside a JSON value or string literal
 * leaves the model a fragment like {@code {"name":"serv} — a shape that invites it
 * to "repair" the structure by fabricating the omitted fields. When the input
 * looks like JSON, this utility snaps each head/tail cut point to the nearest
 * complete structural boundary (immediately after a {@code ,}, {@code &#125;} or
 * {@code ]} that is not inside a string), so a retained fragment always ends and
 * begins between elements rather than in the middle of one.
 *
 * <p>Non-JSON input falls back to a plain character cut, and boundary snapping is
 * only applied when it costs less than half the requested budget — so callers can
 * use this unconditionally without ever losing more than a plain cut would.
 */
public final class StructuredTruncator {

    private StructuredTruncator() {
    }

    private static final int[] NO_BOUNDARIES = new int[0];

    /**
     * Standard fidelity directive appended to truncation markers so the model
     * treats omitted content as unknown rather than reconstructable.
     */
    public static final String FIDELITY_NOTE =
            "Do NOT infer or fabricate omitted content; retrieve the full data (e.g. read_file) "
                    + "or tell the user the result is incomplete.";

    /**
     * Head-only slice: the first {@code maxHeadChars} characters, snapped back to
     * a JSON boundary when one sits within the kept region. Returns the input
     * unchanged when it is already short enough.
     */
    public static String headSlice(String text, int maxHeadChars) {
        if (text == null || maxHeadChars <= 0 || text.length() <= maxHeadChars) {
            return text;
        }
        int[] bounds = boundaries(text);
        int end = snapDown(bounds, maxHeadChars);
        // Reject a boundary that throws away more than half the budget.
        if (end < maxHeadChars / 2) {
            end = maxHeadChars;
        }
        return text.substring(0, end);
    }

    /**
     * Head + marker + tail truncation. {@code headBudget} / {@code tailBudget} are
     * upper bounds on each retained side; {@code marker} is inserted between them.
     * The cut points snap to JSON boundaries when the input is JSON-like and the
     * snap is cheap; otherwise plain character cuts are used. The result never
     * exceeds {@code headBudget + marker.length() + tailBudget}.
     *
     * @return the input unchanged when it already fits both budgets
     */
    public static String truncate(String text, int headBudget, int tailBudget, String marker) {
        if (text == null) {
            return null;
        }
        if (headBudget < 0) {
            headBudget = 0;
        }
        if (tailBudget < 0) {
            tailBudget = 0;
        }
        int len = text.length();
        if (len <= headBudget + tailBudget) {
            return text;
        }
        String mk = marker == null ? "" : marker;
        int[] bounds = boundaries(text);

        int headEnd = snapDown(bounds, headBudget);
        if (headEnd < headBudget / 2) {
            // No usable boundary near the head budget → plain cut.
            headEnd = headBudget;
        }

        int floor = len - tailBudget;
        int tailStart = snapUp(bounds, floor);
        if (tailStart > floor + tailBudget / 2) {
            // Nearest boundary is so far forward the tail would shrink by half → plain cut.
            tailStart = floor;
        }

        if (tailStart <= headEnd) {
            // Snapping collapsed the two regions into each other → plain, non-overlapping cut.
            headEnd = Math.min(headBudget, len);
            tailStart = Math.max(len - tailBudget, headEnd);
        }
        return text.substring(0, headEnd) + mk + text.substring(tailStart);
    }

    /**
     * Indices (in ascending order) at which the text may be split without
     * severing a JSON token. A boundary index {@code i} marks the position
     * immediately after a {@code ,}, {@code &#125;} or {@code ]} that is not
     * inside a string literal. Returns an empty array when the input does not
     * look like JSON, which makes both snap helpers fall back to plain cuts.
     */
    private static int[] boundaries(String text) {
        int len = text.length();
        int start = 0;
        while (start < len && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= len) {
            return NO_BOUNDARIES;
        }
        char first = text.charAt(start);
        if (first != '{' && first != '[') {
            return NO_BOUNDARIES;
        }

        int[] buf = new int[16];
        int n = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < len; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == ',' || c == '}' || c == ']') {
                if (n == buf.length) {
                    int[] grown = new int[buf.length * 2];
                    System.arraycopy(buf, 0, grown, 0, n);
                    buf = grown;
                }
                buf[n++] = i + 1;
            }
        }
        if (n == buf.length) {
            return buf;
        }
        int[] out = new int[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    /** Largest boundary {@code <= limit}, or 0 when none exists. */
    private static int snapDown(int[] bounds, int limit) {
        int best = 0;
        for (int b : bounds) {
            if (b > limit) {
                break;
            }
            best = b;
        }
        return best;
    }

    /** Smallest boundary {@code >= floor}, or {@link Integer#MAX_VALUE} when none exists. */
    private static int snapUp(int[] bounds, int floor) {
        for (int b : bounds) {
            if (b >= floor) {
                return b;
            }
        }
        return Integer.MAX_VALUE;
    }
}
