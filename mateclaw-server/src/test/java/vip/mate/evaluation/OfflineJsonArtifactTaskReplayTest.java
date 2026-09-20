package vip.mate.evaluation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OfflineJsonArtifactTaskReplayTest {
    @TempDir Path root;
    private byte[] fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent-evaluation/json-artifact-boundaries-v1.json")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }
    @Test void executeTasksAndWriteReport() throws Exception {
        String input = System.getProperty("json.artifact.eval.suite");
        var report = OfflineJsonArtifactTaskReplay.run(input == null ? fixture() : Files.readAllBytes(Path.of(input)),
                root, System.getProperty("json.artifact.eval.revision", "unrecorded"));
        Path output = Path.of(System.getProperty("json.artifact.eval.report", "target/agent-evaluation/json-artifact-baseline.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        OfflineJsonArtifactTaskReplay.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertEquals(0, report.mismatchedCases(), () -> "JSON artifact mismatches: " + output.toAbsolutePath());
        assertEquals(0, report.onlineModelCalls());
        assertEquals(4, report.productionClassSha256().size());
    }
    @Test void invalidLaterTaskFailsBeforeAnyFileIsCreated() throws Exception {
        ObjectNode suite = (ObjectNode) OfflineJsonArtifactTaskReplay.JSON.readTree(fixture());
        ((ObjectNode) suite.withArray("tasks").get(1).get("expected")).remove("recipeInvoked");
        assertThrows(IllegalArgumentException.class, () -> OfflineJsonArtifactTaskReplay.run(
                suite.toString().getBytes(StandardCharsets.UTF_8), root, "test"));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }
    @Test void wrongExpectationRecordsMismatchAndRunsEveryTask() throws Exception {
        ObjectNode suite = (ObjectNode) OfflineJsonArtifactTaskReplay.JSON.readTree(fixture());
        ((ObjectNode) suite.withArray("tasks").get(0).get("expected")).put("acceptanceEligible", true);
        var report = OfflineJsonArtifactTaskReplay.run(suite.toString().getBytes(StandardCharsets.UTF_8), root, "test");
        assertEquals(1, report.mismatchedCases());
        assertEquals(suite.withArray("tasks").size(), report.cases().size());
    }
}
