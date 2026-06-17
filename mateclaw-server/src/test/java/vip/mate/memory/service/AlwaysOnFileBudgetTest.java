package vip.mate.memory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the deterministic always-on file budget: content under budget is left
 * untouched, over-budget content is bounded and cut at a section boundary, and
 * disabling (0) or null short-circuits.
 */
class AlwaysOnFileBudgetTest {

    private static String md(int sections, String body) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= sections; i++) {
            sb.append("## section_").append(i).append("\n").append(body).append("\n\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("content within budget is returned unchanged")
    void underBudgetUnchanged() {
        String c = md(3, "short body");
        assertSame(c, AlwaysOnFileBudget.enforce(c, 10_000));
    }

    @Test
    @DisplayName("maxChars<=0 and null short-circuit (unlimited)")
    void disabledOrNull() {
        String c = md(50, "filler");
        assertSame(c, AlwaysOnFileBudget.enforce(c, 0));
        assertNull(AlwaysOnFileBudget.enforce(null, 4000));
    }

    @Test
    @DisplayName("over-budget content is bounded, marked, and cut on a section boundary")
    void overBudgetTruncated() {
        // 40 sections of ~60 chars each ≈ 2400+ chars; cap at 800.
        String c = md(40, "这是一段用于撑大文件体积的内容，重复多次以超过预算阈值。");
        int budget = 800;
        String out = AlwaysOnFileBudget.enforce(c, budget);

        assertTrue(out.length() <= budget, "result must not exceed the budget, was " + out.length());
        assertTrue(out.endsWith(AlwaysOnFileBudget.MARKER.strip())
                        || out.contains("截断"), "truncation marker must be present");
        // The kept head ends at a clean section boundary — no half-section dangling.
        String head = out.substring(0, out.indexOf(AlwaysOnFileBudget.MARKER.strip()));
        assertTrue(head.contains("## section_1"), "earliest (head) sections are kept");
        assertFalse(head.contains("## section_40"), "latest sections are dropped under budget");
    }
}
