package vip.mate.goal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void duplicateVerdictIdsAreRejectedInsteadOfLastWriteWinning() {
        var existing = List.of(new GoalCriterion("C1", "report", false, ""));
        var failed = new GoalChecklistVerdict.CriterionVerdict("C1", false, "missing");
        var passed = new GoalChecklistVerdict.CriterionVerdict("C1", true, "claimed written");
        assertThrows(IllegalArgumentException.class, () -> GoalCriteriaCodec.merge(existing, List.of(failed, passed)));
        assertThrows(IllegalArgumentException.class, () -> GoalCriteriaCodec.merge(existing, List.of(passed, failed)));
        assertThrows(IllegalArgumentException.class, () -> GoalCriteriaCodec.merge(existing, List.of(passed, passed)));
        assertFalse(existing.getFirst().passed());
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void blankEvidenceCannotPassNewOrPersistedCriteria(String evidence) {
        var existing = List.of(new GoalCriterion("C1", "deliver report", false, ""));
        var merged = GoalCriteriaCodec.merge(existing, List.of(
                new GoalChecklistVerdict.CriterionVerdict("C1", true, evidence)));
        assertFalse(merged.getFirst().passed());
        assertFalse(GoalCriteriaCodec.allPassed(merged));
        assertEquals(1, GoalCriteriaCodec.remaining(merged).size());

        var persisted = List.of(new GoalCriterion("C1", "deliver report", true, evidence));
        assertFalse(GoalCriteriaCodec.allPassed(persisted));
        assertEquals(1, GoalCriteriaCodec.remaining(persisted).size());
        assertFalse(GoalCriteriaCodec.merge(persisted, List.of()).getFirst().passed());
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
