package vip.mate.evaluation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OfflineArtifactTaskReplayTest {
    @TempDir Path root;

    private byte[] fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/agent-evaluation/artifact-boundaries-v1.json")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    @Test
    void executePlatformTasksAndWriteReport() throws Exception {
        String input = System.getProperty("artifact.eval.suite");
        var report = OfflineArtifactTaskReplay.run(input == null ? fixture() : Files.readAllBytes(Path.of(input)),
                root, System.getProperty("artifact.eval.revision", "unrecorded"));
        Path output = Path.of(System.getProperty("artifact.eval.report", "target/agent-evaluation/artifact-baseline.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        OfflineArtifactTaskReplay.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertEquals(0, report.mismatchedCases(), () -> "Artifact task mismatches: " + output.toAbsolutePath());
        assertEquals(0, report.onlineModelCalls());
        assertEquals(3, report.productionClassSha256().size());
    }

    @Test
    void invalidInputIsRejectedBeforeCreatingFiles() throws Exception {
        ObjectNode suite = (ObjectNode) OfflineArtifactTaskReplay.JSON.readTree(fixture());
        ((ObjectNode) suite.withArray("tasks").get(0).get("expected")).remove("hotMatchesSnapshot");
        assertThrows(IllegalArgumentException.class, () -> OfflineArtifactTaskReplay.run(
                suite.toString().getBytes(StandardCharsets.UTF_8), root, "test"));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test
    void wrongExpectationReportsMismatchAndContinues() throws Exception {
        ObjectNode suite = (ObjectNode) OfflineArtifactTaskReplay.JSON.readTree(fixture());
        ((ObjectNode) suite.withArray("tasks").get(0).get("expected")).put("hotContent", "wrong");
        var report = OfflineArtifactTaskReplay.run(suite.toString().getBytes(StandardCharsets.UTF_8), root, "test");
        assertEquals(1, report.mismatchedCases());
        assertEquals(suite.withArray("tasks").size(), report.cases().size());
    }
}
