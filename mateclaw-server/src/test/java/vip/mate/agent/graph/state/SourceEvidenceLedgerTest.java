package vip.mate.agent.graph.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceEvidenceLedgerTest {

    @Test
    @DisplayName("records successful read_file paths and symbols")
    void recordsReadFileEvidence() {
        String response = """
                {
                  "filePath": "/repo/src/main/java/vip/mate/skill/SkillController.java",
                  "totalLines": 120,
                  "startLine": 1,
                  "endLine": 80,
                  "content": "     1\\tpackage vip.mate.skill;\\n     2\\tpublic class SkillController { }\\n"
                }
                """;

        SourceEvidenceLedger ledger = SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "read_file", response)));

        assertTrue(ledger.hasPath("/repo/src/main/java/vip/mate/skill/SkillController.java"));
        assertTrue(ledger.hasSymbol("SkillController"));
        assertFalse(ledger.hasSymbol("SkillServiceImpl"));
    }

    @Test
    @DisplayName("ignores failed read_file responses")
    void ignoresFailedReads() {
        String response = """
                {"filePath": "/repo/Missing.java", "error": true, "message": "not found"}
                """;

        SourceEvidenceLedger ledger = SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "read_file", response)));

        assertFalse(ledger.hasPath("/repo/Missing.java"));
        assertTrue(ledger.failedPaths().contains("/repo/Missing.java"));
    }

    @Test
    @DisplayName("validates Java references in final answers against evidence")
    void detectsUnsupportedAnswerReferences() {
        SourceEvidenceLedger ledger = SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "execute_shell_command",
                        "src/main/java/vip/mate/skill/SkillController.java\n")));

        SourceEvidenceLedger.Validation validation = ledger.validateAnswer("""
                已确认 SkillController.java 负责接口，但 SkillServiceImpl.java 负责业务。
                """);

        assertFalse(validation.valid());
        assertTrue(validation.unsupportedReferences().contains("SkillServiceImpl.java"));
        assertFalse(validation.unsupportedReferences().contains("SkillController.java"));
    }

    // ====== Regression coverage for the "grep output → ledger" path ======
    // Reviewer point: JAVA_PATH already accepts bare file names, so a P2
    // "add JAVA_FILE_REF to plain text scan" would be redundant. These tests
    // pin that contract so the next person doesn't try the same wrong fix.

    @Test
    @DisplayName("bare .java filename in shell stdout is recorded as both path and symbol")
    void recordsBareFilenameFromShellStdout() {
        // Some greps / find -printf outputs emit just the filename — no path
        // prefix, no `:` line marker. JAVA_PATH still matches because [+]
        // demands ≥1 word/dot/slash chars, which "ObservationNode" satisfies.
        SourceEvidenceLedger ledger = SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "execute_shell_command",
                        "ObservationNode.java\n")));

        assertTrue(ledger.hasPath("ObservationNode.java"),
                "bare filename must register under sourcePaths");
        assertTrue(ledger.hasSymbol("ObservationNode"),
                "the .java stem must be auto-promoted into sourceSymbols");
    }

    @Test
    @DisplayName("grep -rn output (`path:line:body`) is parsed and the file goes into ledger")
    void recordsGrepDashRnOutput() {
        // Real-world grep -rn output: `relative/path:lineno:matching line`.
        // JAVA_PATH greedy match consumes through the .java suffix and stops
        // at the colon (\\b boundary), so the path portion lands in sourcePaths.
        String grepStdout = """
                src/main/java/vip/mate/agent/graph/node/ObservationNode.java:42:    public class ObservationNode implements NodeAction {
                src/main/java/vip/mate/agent/graph/node/ObservationNode.java:88:        log.info("[Observation]");
                """;
        SourceEvidenceLedger ledger = SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "execute_shell_command", grepStdout)));

        assertTrue(ledger.hasPath("src/main/java/vip/mate/agent/graph/node/ObservationNode.java"));
        assertTrue(ledger.hasSymbol("ObservationNode"));
        // Critical: an answer citing ObservationNode (no .java suffix) must NOT be
        // flagged as evidence-insufficient on the strength of the grep alone.
        // Use only this one symbol in the answer so the test isolates exactly
        // what we're verifying (other *Node names in the sentence would be
        // counted as separate symbol citations).
        SourceEvidenceLedger.Validation validation = ledger.validateAnswer(
                "ObservationNode 写回观察历史。");
        assertTrue(validation.valid(),
                "Symbol named ObservationNode is supported by the grep evidence; should not be flagged");
    }

    @Test
    @DisplayName("real-task regression: ObservationNode + ToolGuardAuditLogEntity grep evidence supports their citations")
    void regressionForRealTraceUnsupportedRefs() {
        // The exact two unsupported refs from production trace 4b38f04f:
        //   unsupportedReferences=[ObservationNode, ToolGuardAuditLogEntity]
        // If the model had genuinely seen these names in shell results, ledger
        // should have accepted them. This test simulates the grep output that
        // would have appeared in a real run — if it passes, the production
        // miss is NOT a JAVA_PATH parsing bug; root cause must be elsewhere
        // (spill / compact dropping the matching lines before ActionNode
        // builds the ledger).
        String evidence = """
                src/main/java/vip/mate/agent/graph/node/ObservationNode.java
                src/main/java/vip/mate/tool/guard/entity/ToolGuardAuditLogEntity.java
                """;
        SourceEvidenceLedger ledger = SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "execute_shell_command", evidence)));

        SourceEvidenceLedger.Validation validation = ledger.validateAnswer(
                "工具结果由 ObservationNode 写回，并落库到 ToolGuardAuditLogEntity。");
        assertTrue(validation.valid(),
                "Both citations must be considered supported when their .java files appear in shell output. "
                        + "If this fails, fix JAVA_PATH; if it passes, the production miss is in spill/compact, "
                        + "not in ledger parsing.");
    }

    @Test
    @DisplayName("citing a class with NO matching .java in any tool output is correctly flagged unsupported")
    void unrelatedSymbolInAnswerIsStillFlagged() {
        // Negative control for the regression test above: make sure the
        // 'support' check isn't trivially over-broad — symbols that have no
        // backing evidence at all must still trip evidence_insufficient.
        SourceEvidenceLedger ledger = SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "execute_shell_command",
                        "ObservationNode.java\n")));

        SourceEvidenceLedger.Validation validation = ledger.validateAnswer(
                "ObservationNode 协作 RandomMadeUpService 完成处理。");
        assertFalse(validation.valid());
        assertTrue(validation.unsupportedReferences().contains("RandomMadeUpService"));
    }
}
