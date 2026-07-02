package vip.mate.agent.graph.plan.node;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the signature-based stall detection: graduated WARN nudge then HALT
 * for repeated identical results, repeated identical failing calls, and a tool
 * that keeps failing across different arguments.
 */
class StepProgressTrackerTest {

    @Test
    void identicalResults_warnThenHalt_noProgress() {
        StepProgressTracker t = new StepProgressTracker();
        // result is a success payload (not a failure marker) -> pure no-progress
        assertTrue(t.record("search_files", "{\"q\":\"x\"}", "found 0 matches list A").isEmpty(), "1st: no warn");
        assertTrue(t.record("search_files", "{\"q\":\"x\"}", "found 0 matches list A").isPresent(), "2nd: WARN nudge");
        assertFalse(t.isStuck(), "not stuck at WARN");
        assertTrue(t.record("search_files", "{\"q\":\"x\"}", "found 0 matches list A").isEmpty(), "3rd: nudge de-duped");
        t.record("search_files", "{\"q\":\"x\"}", "found 0 matches list A"); // 4th -> HALT
        assertTrue(t.isStuck(), "stuck at HALT threshold");
        assertTrue(t.haltReason().startsWith("no_progress"));
    }

    @Test
    void sameCallFailing_warnThenHalt() {
        StepProgressTracker t = new StepProgressTracker();
        // same args, distinct error texts -> isolates the same-call-failure path
        assertTrue(t.record("read_file", "{\"p\":\"a\"}", "Error: e1").isEmpty());
        assertTrue(t.record("read_file", "{\"p\":\"a\"}", "Error: e2").isPresent(), "2nd failure: WARN");
        assertFalse(t.isStuck());
        t.record("read_file", "{\"p\":\"a\"}", "Error: e3");
        t.record("read_file", "{\"p\":\"a\"}", "Error: e4"); // 4th failure -> HALT
        assertTrue(t.isStuck());
        assertTrue(t.haltReason().startsWith("repeated_failure"));
    }

    @Test
    void sameToolFailingDifferentArgs_warnThenHalt() {
        StepProgressTracker t = new StepProgressTracker();
        boolean anyWarn = false;
        for (int i = 1; i <= 6; i++) {
            Optional<String> n = t.record("terminal", "{\"cmd\":\"c" + i + "\"}", "execution failed: boom" + i);
            anyWarn |= n.isPresent();
        }
        assertTrue(anyWarn, "should emit a same-tool-failure nudge by the 3rd distinct failure");
        assertTrue(t.isStuck(), "6 distinct failures of the same tool -> HALT");
    }

    @Test
    void variedSuccessfulResults_noStall() {
        StepProgressTracker t = new StepProgressTracker();
        for (int i = 0; i < 6; i++) {
            assertTrue(t.record("web_search", "{\"q\":\"q" + i + "\"}", "result payload number " + i).isEmpty());
        }
        assertFalse(t.isStuck(), "distinct successful results never stall");
    }

    @Test
    void looksLikeFailure_classification() {
        assertTrue(StepProgressTracker.looksLikeFailure(""), "empty is no-progress");
        assertTrue(StepProgressTracker.looksLikeFailure("   "), "blank is no-progress");
        assertTrue(StepProgressTracker.looksLikeFailure("Error: ENOENT: no such file or directory"));
        assertTrue(StepProgressTracker.looksLikeFailure("java.util.concurrent.TimeoutException: ..."));
        assertTrue(StepProgressTracker.looksLikeFailure("Authentication Failed: Requires authentication"));
        assertTrue(StepProgressTracker.looksLikeFailure("未找到匹配的文件"));
        assertFalse(StepProgressTracker.looksLikeFailure("Here is the summary of the file: ..."));
    }
}
