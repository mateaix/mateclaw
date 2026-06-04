package vip.mate.goal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the checklist (de)serialization + merge helpers.
 */
class GoalCriteriaCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static GoalCriterion c(String id, String text, boolean passed) {
        return new GoalCriterion(id, text, passed, passed ? "ok" : "");
    }

    // ---------- parse ----------

    @Test
    void parse_nullOrBlankOrCorrupt_returnsEmptyMutableList() {
        assertTrue(GoalCriteriaCodec.parse(null, mapper).isEmpty());
        assertTrue(GoalCriteriaCodec.parse("", mapper).isEmpty());
        assertTrue(GoalCriteriaCodec.parse("   ", mapper).isEmpty());
        assertTrue(GoalCriteriaCodec.parse("{not valid json", mapper).isEmpty());
        // mutable: callers append during bootstrap/append paths
        GoalCriteriaCodec.parse(null, mapper).add(c("C1", "x", false));
    }

    @Test
    void parse_roundTrip() {
        String json = GoalCriteriaCodec.serialize(List.of(c("C1", "tests pass", true)), mapper);
        List<GoalCriterion> back = GoalCriteriaCodec.parse(json, mapper);
        assertEquals(1, back.size());
        assertEquals("C1", back.get(0).id());
        assertEquals("tests pass", back.get(0).text());
        assertTrue(back.get(0).passed());
    }

    @Test
    void serialize_null_returnsNull() {
        assertNull(GoalCriteriaCodec.serialize(null, mapper));
    }

    // ---------- merge ----------

    @Test
    void merge_appliesVerdictById_preservesTextAndUntouched() {
        List<GoalCriterion> existing = List.of(
                c("C1", "first", false),
                c("C2", "second", false));
        List<GoalChecklistVerdict.CriterionVerdict> delta = List.of(
                new GoalChecklistVerdict.CriterionVerdict("C1", true, "did it"));

        List<GoalCriterion> merged = GoalCriteriaCodec.merge(existing, delta);

        assertEquals(2, merged.size());
        assertTrue(merged.get(0).passed());
        assertEquals("did it", merged.get(0).evidence());
        assertEquals("first", merged.get(0).text());          // text preserved
        assertFalse(merged.get(1).passed());                  // untouched stays
        assertEquals("second", merged.get(1).text());
    }

    @Test
    void merge_unknownVerdictId_isIgnored() {
        List<GoalCriterion> existing = List.of(c("C1", "first", false));
        List<GoalChecklistVerdict.CriterionVerdict> delta = List.of(
                new GoalChecklistVerdict.CriterionVerdict("C9", true, "nope"));
        List<GoalCriterion> merged = GoalCriteriaCodec.merge(existing, delta);
        assertFalse(merged.get(0).passed());
    }

    // ---------- allPassed / remaining ----------

    @Test
    void allPassed_emptyIsFalse() {
        assertFalse(GoalCriteriaCodec.allPassed(List.of()));
    }

    @Test
    void allPassed_trueOnlyWhenEveryPassed() {
        assertTrue(GoalCriteriaCodec.allPassed(List.of(c("C1", "a", true), c("C2", "b", true))));
        assertFalse(GoalCriteriaCodec.allPassed(List.of(c("C1", "a", true), c("C2", "b", false))));
    }

    @Test
    void remaining_returnsOnlyUnpassed() {
        List<GoalCriterion> rem = GoalCriteriaCodec.remaining(
                List.of(c("C1", "a", true), c("C2", "b", false), c("C3", "c", false)));
        assertEquals(2, rem.size());
        assertEquals("C2", rem.get(0).id());
        assertEquals("C3", rem.get(1).id());
    }

    // ---------- reindex ----------

    @Test
    void reindex_assignsSequentialIds() {
        List<GoalCriterion> out = GoalCriteriaCodec.reindex(List.of(
                c("", "a", false), c("zzz", "b", false), c("C99", "c", false)));
        assertEquals("C1", out.get(0).id());
        assertEquals("C2", out.get(1).id());
        assertEquals("C3", out.get(2).id());
        assertEquals("a", out.get(0).text());
    }
}
