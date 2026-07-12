package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 1 — coverage for {@link ReasoningNode#renderLoadedSkillsHint}.
 *
 * <p>Move 1 splits the previously-monolithic skill catalog rendering into:
 * <ol>
 *   <li>A static catalog segment rendered with {@code Set.of()} for
 *       loadedThisRun, so it stays prompt-cache-friendly across turns.</li>
 *   <li>A per-turn volatile suffix that tells the model which skills it
 *       already pulled in via {@code load_skill} this run.</li>
 * </ol>
 *
 * <p>This test exercises the volatile-suffix helper directly. The helper
 * is package-private static so tests can assert its exact format without
 * duplicating it. The contract being verified:
 * <ul>
 *   <li>{@code null} or empty input → {@code null} (caller skips injection).</li>
 *   <li>Non-empty input → a single-line SystemMessage body.</li>
 *   <li>Each skill name is wrapped in backticks.</li>
 *   <li>The text explicitly says "do not re-load" so the model knows not
 *       to invoke {@code load_skill} again.</li>
 *   <li>Order is preserved (LinkedHashSet semantics) so the most-recently
 *       loaded skill is named first — useful when the model needs to
 *       disambiguate two skills with overlapping tool names.</li>
 * </ul>
 */
class ReasoningNodeLoadedSkillsHintTest {

    @Test
    @DisplayName("null loadedThisRun → null (caller skips injection)")
    void nullSetReturnsNull() {
        assertNull(ReasoningNode.renderLoadedSkillsHint(null),
                "null input must return null so the caller can skip injection");
    }

    @Test
    @DisplayName("empty loadedThisRun → null (caller skips injection)")
    void emptySetReturnsNull() {
        assertNull(ReasoningNode.renderLoadedSkillsHint(Set.of()),
                "empty input must return null so the caller can skip injection");
    }

    @Test
    @DisplayName("single skill → hint contains backtick-wrapped name + 'do not re-load'")
    void singleSkillRendersHint() {
        String hint = ReasoningNode.renderLoadedSkillsHint(Set.of("ckjia-shopping"));

        assertNotNull(hint, "non-empty input must produce a hint");
        assertTrue(hint.contains("`ckjia-shopping`"),
                "skill name must be wrapped in backticks; hint was: " + hint);
        assertTrue(hint.contains("do not re-load"),
                "hint must explicitly say 'do not re-load'; hint was: " + hint);
        assertTrue(hint.contains("loaded this run"),
                "hint must mention 'loaded this run' for context; hint was: " + hint);
    }

    @Test
    @DisplayName("multiple skills → comma-separated backtick-wrapped names")
    void multipleSkillsAreCommaSeparated() {
        // LinkedHashSet so iteration order is deterministic in the assertion
        Set<String> loaded = new LinkedHashSet<>();
        loaded.add("ckjia-shopping");
        loaded.add("browser-cdp");
        loaded.add("pdf-builtin");

        String hint = ReasoningNode.renderLoadedSkillsHint(loaded);

        assertNotNull(hint);
        assertTrue(hint.contains("`ckjia-shopping`"));
        assertTrue(hint.contains("`browser-cdp`"));
        assertTrue(hint.contains("`pdf-builtin`"));
        // All three on one line, comma-separated
        assertTrue(hint.contains("`ckjia-shopping`, `browser-cdp`, `pdf-builtin`"),
                "multiple skills must be comma-separated on one line; hint was: " + hint);
    }

    @Test
    @DisplayName("hint is a single line (no embedded newlines)")
    void hintIsSingleLine() {
        String hint = ReasoningNode.renderLoadedSkillsHint(Set.of("skill-a", "skill-b"));

        assertNotNull(hint);
        assertFalse(hint.contains("\n"),
                "hint must be a single line so it doesn't break SystemMessage formatting; hint was: " + hint);
        assertTrue(hint.endsWith("."),
                "hint must end with a period; hint was: " + hint);
    }

    @Test
    @DisplayName("hint order matches the input iteration order (LinkedHashSet)")
    void hintPreservesIterationOrder() {
        Set<String> loaded = new LinkedHashSet<>();
        loaded.add("third-loaded");
        loaded.add("first-loaded");
        loaded.add("second-loaded");

        String hint = ReasoningNode.renderLoadedSkillsHint(loaded);

        assertNotNull(hint);
        int firstIdx = hint.indexOf("`first-loaded`");
        int secondIdx = hint.indexOf("`second-loaded`");
        int thirdIdx = hint.indexOf("`third-loaded`");
        assertTrue(thirdIdx < firstIdx && firstIdx < secondIdx,
                "iteration order must be preserved (third loaded first); hint was: " + hint);
    }

    @Test
    @DisplayName("Move 1 invariant: hint text is volatile-suffix material, NOT a static-catalog segment")
    void hintTextIsVolatileSuffixMaterial() {
        // The hint must NOT contain language that implies it's part of the
        // static catalog — that would confuse the model about which skills
        // are statically visible vs. loaded this run.
        String hint = ReasoningNode.renderLoadedSkillsHint(Set.of("any-skill"));

        assertNotNull(hint);
        assertFalse(hint.contains("| Skill |"),
                "hint must not look like a catalog table row; hint was: " + hint);
        assertFalse(hint.contains("### "),
                "hint must not look like a catalog section header; hint was: " + hint);
    }

    @Test
    @DisplayName("Move 1 vs Move 2 boundary: hint does not duplicate constraints content")
    void hintDoesNotDuplicateConstraints() {
        // The hint's job is to tell the model "you already loaded this
        // skill, don't load it again" — it must NOT also embed the skill's
        // constraints. Those live in the Constraints column of the static
        // catalog (Move 2) and the ProgressLedger (B-class). Putting them
        // here too would (a) duplicate token spend, (b) break the
        // prompt-cache stability of the static prefix, and (c) violate
        // the "position is semantics" principle.
        String hint = ReasoningNode.renderLoadedSkillsHint(Set.of("ckjia-shopping"));

        assertNotNull(hint);
        // Spot-check common constraint phrases that should NOT appear here
        assertFalse(hint.contains("Constraints"),
                "hint must not duplicate the Constraints column; hint was: " + hint);
        assertFalse(hint.contains("allowed tools"),
                "hint must not duplicate the allowed-tools block; hint was: " + hint);
    }
}
