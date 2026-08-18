package vip.mate.common.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared shingling util that memory relevance scoring and
 * routine recurrence detection both depend on.
 */
class ShinglesTest {

    @Test
    @DisplayName("null and empty input yield an empty set")
    void handlesEmptyInput() {
        assertTrue(Shingles.of(null).isEmpty());
        assertTrue(Shingles.of("").isEmpty());
    }

    @Test
    @DisplayName("Latin tokens shorter than two characters are dropped")
    void dropsSingleLatinCharacters() {
        Set<String> s = Shingles.of("a bc def");
        assertTrue(s.contains("bc"));
        assertTrue(s.contains("def"));
        assertTrue(!s.contains("a"));
    }

    @Test
    @DisplayName("CJK runs become character bigrams")
    void producesCjkBigrams() {
        Set<String> s = Shingles.of("运维日报");
        assertEquals(Set.of("运维", "维日", "日报"), s);
    }

    @Test
    @DisplayName("an isolated CJK character is kept whole")
    void keepsIsolatedCjkCharacter() {
        assertTrue(Shingles.of("查 a").contains("查"));
    }

    @Test
    @DisplayName("mixed-script text yields both token kinds")
    void mixesLatinAndCjk() {
        Set<String> s = Shingles.of("生成 report");
        assertTrue(s.contains("生成"));
        assertTrue(s.contains("report"));
    }

    @Test
    @DisplayName("jaccard is 1.0 for identical sets and 0.0 when disjoint")
    void jaccardBounds() {
        Set<String> a = Shingles.of("运维日报");
        assertEquals(1.0, Shingles.jaccard(a, Shingles.of("运维日报")), 1e-9);
        assertEquals(0.0, Shingles.jaccard(a, Shingles.of("营收数字")), 1e-9);
    }

    @Test
    @DisplayName("jaccard is 0.0 when either side is empty")
    void jaccardHandlesEmpty() {
        assertEquals(0.0, Shingles.jaccard(Shingles.of("abc"), Set.of()), 1e-9);
        assertEquals(0.0, Shingles.jaccard(null, Shingles.of("abc")), 1e-9);
    }

    @Test
    @DisplayName("jaccard is symmetric regardless of argument order")
    void jaccardIsSymmetric() {
        Set<String> a = Shingles.of("生成今天的运维日报");
        Set<String> b = Shingles.of("生成今天的运维日报，谢谢");
        assertEquals(Shingles.jaccard(a, b), Shingles.jaccard(b, a), 1e-9);
    }
}
