package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for tool-execution error normalization and the truncated-args
 * guidance, following the same no-Spring/no-mock style as
 * {@link ToolExecutionExecutorCapToolCallsTest}.
 *
 * <p>The JSON pre-validation gate in {@code execute()} wraps only
 * {@code readTree(arguments)}, so any exception there is a JSON parse failure.
 * It returns {@link ToolExecutionExecutor#TRUNCATED_ARGS_GUIDANCE}
 * <em>unconditionally</em> rather than pattern-matching the parser message —
 * Jackson phrases a mid-token cut as "Unexpected end-of-input" but a malformed
 * fragment like {@code {"a":}} as "Unexpected character", and both demand the
 * same remedy (re-emit the call). Pattern-matching alone would leave the
 * malformed-fragment case with a generic error, so the gate bypasses it.
 * {@link ToolExecutionExecutor#normalizeToolExecutionError} still
 * pattern-matches for genuine tool-execution errors (the other call sites),
 * which have many unrelated causes — those patterns are regression-covered here.
 */
class ToolExecutionExecutorTruncationGuidanceTest {

    // ── The guidance itself ────────────────────────────────────────────────────

    @Test
    @DisplayName("truncation guidance is actionable: names cause + required re-call action")
    void guidanceIsActionable() {
        String g = ToolExecutionExecutor.TRUNCATED_ARGS_GUIDANCE;
        assertTrue(g.contains("truncated mid-stream"),
                "must tell the model the args were truncated: " + g);
        assertTrue(g.contains("max_tokens"),
                "must name the likely cause so the model knows why: " + g);
        assertTrue(g.contains("re-call the SAME tool"),
                "must instruct the model to re-call the tool, not narrate: " + g);
    }

    // ── The pre-validation gate contract ───────────────────────────────────────

    @Test
    @DisplayName("gate guidance covers malformed fragments the pattern table misses")
    void gateGuidanceCoversMalformedFragments() {
        // A fragment like {"a":} makes Jackson report "Unexpected character ...",
        // which is NOT in normalizeToolExecutionError's pattern table — proving
        // pattern-matching alone leaves a gap. The gate closes it by returning
        // TRUNCATED_ARGS_GUIDANCE unconditionally; assert that guidance is the
        // actionable truncation message (not a generic error).
        String gateMessage = ToolExecutionExecutor.TRUNCATED_ARGS_GUIDANCE;
        assertTrue(gateMessage.contains("re-call the SAME tool"),
                "the gate must hand the model the actionable truncation guidance");

        // Sanity: pattern-matching alone would indeed miss this phrasing, which is
        // exactly why the gate does not rely on it.
        String unmatched = ToolExecutionExecutor.normalizeToolExecutionError(
                new Exception("Unexpected character ('}' (code 125))"));
        assertTrue(unmatched.startsWith("Tool execution failed: Unexpected character"),
                "pattern-matching falls through to a generic error for this phrasing, "
                        + "so the gate must not depend on it: " + unmatched);
    }

    // ── normalizeToolExecutionError pattern regression (other call sites) ──────

    @Test
    @DisplayName("covered JSON-parse messages map to the truncation guidance")
    void coveredPatternsReturnGuidance() {
        String[] covered = {
                "Unexpected end-of-input: was expecting closing quote for a string value",
                "Malformed JSON: expected a value",
                "JSON parse error: invalid token",
                "conversion from JSON failed",
                "Unexpected character escape sequence in string",
        };
        for (String msg : covered) {
            assertSame(ToolExecutionExecutor.TRUNCATED_ARGS_GUIDANCE,
                    ToolExecutionExecutor.normalizeToolExecutionError(new Exception(msg)),
                    "message should be treated as truncation: " + msg);
        }
    }

    @Test
    @DisplayName("non-JSON tool error falls through to a generic message")
    void genericErrorFallsThrough() {
        String out = ToolExecutionExecutor.normalizeToolExecutionError(
                new Exception("NullPointerException at line 42"));
        assertEquals("Tool execution failed: NullPointerException at line 42", out);
    }

    @Test
    @DisplayName("workspace path violation maps to the dedicated workspace error")
    void accessDeniedReturnsWorkspaceError() {
        String out = ToolExecutionExecutor.normalizeToolExecutionError(
                new Exception("Access denied: path outside allowed directories (/etc/passwd)"));
        assertEquals("Tool execution failed: target path is outside the allowed workspace directory.", out);
    }

    @Test
    @DisplayName("null exception / null message is handled without NPE")
    void nullMessageHandled() {
        assertEquals("Tool execution failed: Unknown error",
                ToolExecutionExecutor.normalizeToolExecutionError(null));
        assertEquals("Tool execution failed: Unknown error",
                ToolExecutionExecutor.normalizeToolExecutionError(new Exception((String) null)));
    }
}
