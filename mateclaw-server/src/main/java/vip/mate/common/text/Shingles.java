package vip.mate.common.text;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language-agnostic near-duplicate text comparison without a word segmenter.
 *
 * <p>A shingle set mixes Latin word tokens with CJK character bigrams, so the
 * same routine works on space-delimited English and on space-free Chinese.
 * Bigrams are the reason a segmenter is unnecessary: two Chinese sentences
 * that share most of their characters in the same order share most of their
 * bigrams, while unrelated sentences of similar length do not.
 *
 * <p>Extracted so relevance scoring (memory recall) and recurrence detection
 * (routine mining) agree on what "these two texts say the same thing" means.
 * Callers should lowercase the input first when case should be ignored — the
 * Latin token pattern only matches lowercase.
 *
 * @author MateClaw Team
 */
public final class Shingles {

    /** Latin word tokens; two chars minimum so single letters do not dominate. */
    private static final Pattern WORD_RE = Pattern.compile("[a-z0-9]{2,}");

    private Shingles() {
    }

    /**
     * Produce the shingle set: Latin word tokens (length &gt;= 2) plus CJK
     * character bigrams (a single CJK character when isolated).
     *
     * @param text input; {@code null} yields an empty set
     */
    public static Set<String> of(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return out;
        }

        Matcher m = WORD_RE.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }

        for (String run : text.replaceAll("[^\\p{IsHan}]", " ").split("\\s+")) {
            if (run.isEmpty()) continue;
            if (run.length() == 1) {
                out.add(run);
            } else {
                for (int i = 0; i + 2 <= run.length(); i++) {
                    out.add(run.substring(i, i + 2));
                }
            }
        }

        return out;
    }

    /**
     * Jaccard similarity of two shingle sets: {@code |A ∩ B| / |A ∪ B|}.
     *
     * @return {@code 0.0} when either set is empty, otherwise a value in
     *         {@code [0.0, 1.0]} where 1.0 means identical shingle sets
     */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        // Intersect against the smaller set so the scan is bounded by it.
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int intersection = 0;
        for (String s : smaller) {
            if (larger.contains(s)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }
}
