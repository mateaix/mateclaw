package vip.mate.agent.graph.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for the tool-call loop guard: signature canonicalization,
 * the failure heuristic, and the three detectors' threshold boundaries.
 */
class ToolLoopGuardTest {

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    private static ToolResponseMessage.ToolResponse result(String id, String name, String data) {
        return new ToolResponseMessage.ToolResponse(id, name, data);
    }

    /** Run N consecutive rounds of the same single call/result pair, chaining stats. */
    private static ToolLoopGuard.Evaluation runRounds(int rounds, String name, String args, String data) {
        Map<String, Object> stats = Map.of();
        ToolLoopGuard.Evaluation eval = null;
        for (int i = 0; i < rounds; i++) {
            eval = ToolLoopGuard.evaluate(stats,
                    List.of(call("c1", name, args)),
                    List.of(result("c1", name, data)));
            stats = eval.stats();
        }
        return eval;
    }

    // ==================== failure heuristic ====================

    @Test
    @DisplayName("失败判定：执行器异常前缀 / 安全拦截 / 工具自身错误前缀 / JSON error 字段")
    void failureHeuristic() {
        assertTrue(ToolLoopGuard.isFailure("Tool execution failed: boom"));
        assertTrue(ToolLoopGuard.isFailure("[安全拦截] rm -rf 被拒绝。请使用更安全的替代方案。"));
        assertTrue(ToolLoopGuard.isFailure("Error: file not found"));
        assertTrue(ToolLoopGuard.isFailure("错误：路径不存在"));
        assertTrue(ToolLoopGuard.isFailure("{\"error\":\"path outside workspace\"}"));
        assertTrue(ToolLoopGuard.isFailure("{\"success\": false, \"message\":\"denied\"}"));

        assertFalse(ToolLoopGuard.isFailure("{\"filePath\":\"a.txt\",\"bytesWritten\":42}"));
        assertFalse(ToolLoopGuard.isFailure("{\"error\": null, \"rows\": 3}"));
        assertFalse(ToolLoopGuard.isFailure("{\"error\": \"\", \"rows\": 3}"));
        assertFalse(ToolLoopGuard.isFailure("plain successful output"));
        assertFalse(ToolLoopGuard.isFailure(null));
        assertFalse(ToolLoopGuard.isFailure("  "));
    }

    // ==================== signature canonicalization ====================

    @Test
    @DisplayName("参数规范化：键序与空白差异命中同一签名")
    void canonicalization_keyOrderAndWhitespace() {
        String a = ToolLoopGuard.canonicalizeArguments("{\"b\":1,\"a\":2}");
        String b = ToolLoopGuard.canonicalizeArguments("{ \"a\" : 2, \"b\" : 1 }");
        assertEquals(a, b);

        // Non-JSON falls back to the trimmed raw string.
        assertEquals("not-json", ToolLoopGuard.canonicalizeArguments("  not-json "));
        assertEquals("", ToolLoopGuard.canonicalizeArguments(null));
    }

    @Test
    @DisplayName("同参失败：不同键序也累计到同一计数器")
    void exactFailure_keyOrderInsensitive() {
        Map<String, Object> stats = Map.of();
        ToolLoopGuard.Evaluation e1 = ToolLoopGuard.evaluate(stats,
                List.of(call("c1", "read_file", "{\"b\":1,\"a\":2}")),
                List.of(result("c1", "read_file", "Error: nope")));
        ToolLoopGuard.Evaluation e2 = ToolLoopGuard.evaluate(e1.stats(),
                List.of(call("c1", "read_file", "{\"a\":2,\"b\":1}")),
                List.of(result("c1", "read_file", "Error: nope")));
        // 2nd identical-arg failure crosses the warn threshold.
        assertEquals(1, e2.warnings().size());
        assertTrue(e2.warnings().get(0).contains("相同参数"));
    }

    // ==================== detector 1: exact failure ====================

    @Test
    @DisplayName("同参失败：1 次不警告，2 次警告，5 次熔断")
    void exactFailure_thresholds() {
        assertTrue(runRounds(1, "web_search", "{\"q\":\"x\"}", "Error: rate limited").warnings().isEmpty());

        ToolLoopGuard.Evaluation warn = runRounds(2, "web_search", "{\"q\":\"x\"}", "Error: rate limited");
        assertEquals(1, warn.warnings().size());
        assertFalse(warn.shouldHalt());

        ToolLoopGuard.Evaluation halt = runRounds(5, "web_search", "{\"q\":\"x\"}", "Error: rate limited");
        assertTrue(halt.shouldHalt());
        assertTrue(halt.haltReason().contains("web_search"));
    }

    @Test
    @DisplayName("同参失败：中途成功清零计数")
    void exactFailure_successResets() {
        Map<String, Object> stats = runRounds(4, "web_search", "{\"q\":\"x\"}", "Error: rate limited").stats();
        // One success on the same signature clears the streak.
        ToolLoopGuard.Evaluation ok = ToolLoopGuard.evaluate(stats,
                List.of(call("c1", "web_search", "{\"q\":\"x\"}")),
                List.of(result("c1", "web_search", "10 results found")));
        assertFalse(ok.shouldHalt());
        // Next failure starts from 1 again — no warning.
        ToolLoopGuard.Evaluation after = ToolLoopGuard.evaluate(ok.stats(),
                List.of(call("c1", "web_search", "{\"q\":\"x\"}")),
                List.of(result("c1", "web_search", "Error: rate limited")));
        assertTrue(after.warnings().isEmpty());
    }

    // ==================== detector 2: per-tool failure ====================

    @Test
    @DisplayName("同工具换参失败：3 次警告，8 次熔断")
    void sameToolFailure_thresholds() {
        Map<String, Object> stats = Map.of();
        ToolLoopGuard.Evaluation eval = null;
        for (int i = 0; i < 8; i++) {
            eval = ToolLoopGuard.evaluate(stats,
                    List.of(call("c1", "read_file", "{\"path\":\"/guess/" + i + "\"}")),
                    List.of(result("c1", "read_file", "Error: no such file")));
            stats = eval.stats();
            if (i == 1) {
                assertTrue(eval.warnings().isEmpty(), "2 failures with different args: below warn threshold");
            }
            if (i == 2) {
                assertEquals(1, eval.warnings().size(), "3rd failure warns");
                assertTrue(eval.warnings().get(0).contains("已失败 3 次"));
            }
        }
        assertTrue(eval.shouldHalt(), "8th failure halts");
    }

    // ==================== detector 3: idempotent no-progress ====================

    @Test
    @DisplayName("只读工具无进展：第 2 次相同结果警告，第 5 次熔断，结果变化清零")
    void noProgress_thresholds() {
        assertTrue(runRounds(1, "read_file", "{\"path\":\"a\"}", "same content").warnings().isEmpty());

        ToolLoopGuard.Evaluation warn = runRounds(2, "read_file", "{\"path\":\"a\"}", "same content");
        assertEquals(1, warn.warnings().size());
        assertTrue(warn.warnings().get(0).contains("完全相同的结果"));

        ToolLoopGuard.Evaluation halt = runRounds(5, "read_file", "{\"path\":\"a\"}", "same content");
        assertTrue(halt.shouldHalt());

        // A changed result resets the streak.
        Map<String, Object> stats = runRounds(4, "read_file", "{\"path\":\"a\"}", "same content").stats();
        ToolLoopGuard.Evaluation changed = ToolLoopGuard.evaluate(stats,
                List.of(call("c1", "read_file", "{\"path\":\"a\"}")),
                List.of(result("c1", "read_file", "different content")));
        assertFalse(changed.shouldHalt());
        assertTrue(changed.warnings().isEmpty());
    }

    @Test
    @DisplayName("变更类工具不参与无进展检测")
    void noProgress_mutatingToolsExempt() {
        // Same successful write repeated 6 times — legitimate, never flagged.
        ToolLoopGuard.Evaluation eval = runRounds(6, "write_file",
                "{\"filePath\":\"a.txt\",\"content\":\"x\"}",
                "{\"filePath\":\"a.txt\",\"bytesWritten\":1}");
        assertTrue(eval.warnings().isEmpty());
        assertFalse(eval.shouldHalt());
    }

    @Test
    @DisplayName("空批次与空历史安全返回")
    void emptyInputsAreSafe() {
        ToolLoopGuard.Evaluation eval = ToolLoopGuard.evaluate(null, List.of(), List.of());
        assertTrue(eval.warnings().isEmpty());
        assertFalse(eval.shouldHalt());
        assertTrue(eval.stats().isEmpty());
    }
}
