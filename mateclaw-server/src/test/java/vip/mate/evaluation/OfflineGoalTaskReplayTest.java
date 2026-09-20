package vip.mate.evaluation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OfflineGoalTaskReplayTest {
    private byte[] fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent-evaluation/goal-boundaries-v1.json")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    @Test
    void replayTasksAndWriteReportEvenWhenExpectationsMismatch() throws Exception {
        String input = System.getProperty("agent.eval.suite");
        byte[] bytes = input == null ? fixture() : Files.readAllBytes(Path.of(input));
        var report = OfflineGoalTaskReplay.run(bytes, System.getProperty("agent.eval.revision", "unrecorded"));
        Path output = Path.of(System.getProperty("agent.eval.report", "target/agent-evaluation/goal-baseline.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        OfflineGoalTaskReplay.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertEquals(0, report.mismatchedCases(), () -> "Replay mismatches: " + output.toAbsolutePath());
        assertEquals(0, report.onlineModelCalls());
        assertEquals("not_measured", report.agentTaskSuccessRate());
        var serialized = OfflineGoalTaskReplay.JSON.valueToTree(report);
        assertEquals("caller_supplied_label", serialized.path("revisionSource").asText());
        var classes = serialized.path("executedClassSha256");
        assertEquals(4, classes.size());
        for (Class<?> type : java.util.List.of(vip.mate.goal.service.GoalEvaluationService.class,
                vip.mate.goal.model.GoalCriteriaCodec.class, vip.mate.goal.model.GoalCriterion.class,
                vip.mate.goal.model.GoalEvaluationResult.class)) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertNotNull(stream);
                String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(stream.readAllBytes()));
                assertEquals(actual, classes.path(type.getName()).asText());
            }
        }
    }

    @Test
    void invalidSuiteIsRejectedBeforeReplay() throws Exception {
        ObjectNode root = (ObjectNode) OfflineGoalTaskReplay.JSON.readTree(fixture());
        var empty = root.deepCopy();
        empty.putArray("tasks");
        assertThrows(IllegalArgumentException.class, () -> OfflineGoalTaskReplay.parse(empty.toString().getBytes(StandardCharsets.UTF_8)));
        var duplicate = root.deepCopy();
        ((ObjectNode) duplicate.withArray("tasks").get(1)).put("id", root.withArray("tasks").get(0).get("id").asText());
        assertThrows(IllegalArgumentException.class, () -> OfflineGoalTaskReplay.parse(duplicate.toString().getBytes(StandardCharsets.UTF_8)));
        var invalid = root.deepCopy();
        ((ObjectNode) invalid.withArray("tasks").get(0).get("expected")).remove("completed");
        assertThrows(IllegalArgumentException.class, () -> OfflineGoalTaskReplay.parse(invalid.toString().getBytes(StandardCharsets.UTF_8)));
        var version = root.deepCopy();
        version.put("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> OfflineGoalTaskReplay.parse(version.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void wrongExpectationIsReportedAndDoesNotStopRemainingCases() throws Exception {
        ObjectNode root = (ObjectNode) OfflineGoalTaskReplay.JSON.readTree(fixture());
        var tasks = root.withArray("tasks");
        ObjectNode expected = (ObjectNode) tasks.get(0).get("expected");
        expected.put("completed", !expected.get("completed").asBoolean());
        var report = OfflineGoalTaskReplay.run(root.toString().getBytes(StandardCharsets.UTF_8), "test-revision");
        assertEquals(1, report.mismatchedCases());
        assertEquals(tasks.size(), report.cases().size());
        assertFalse(report.cases().getFirst().matched());
        assertNotNull(report.cases().getFirst().actual());
    }
}
