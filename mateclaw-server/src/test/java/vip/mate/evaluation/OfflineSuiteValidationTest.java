package vip.mate.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OfflineSuiteValidationTest {
    @ParameterizedTest
    @CsvSource({"goal,goal-boundaries-v1.json", "artifact,artifact-boundaries-v1.json",
            "json,json-artifact-boundaries-v1.json", "service,goal-service-boundaries-v1.json"})
    void duplicateRootAndExpectedKeysAreRejectedBeforeReplay(String mode, String fixture) throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/agent-evaluation/" + fixture)) {
            assertNotNull(stream);
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String duplicateRoot = original.replaceFirst("\\{", "{\"schemaVersion\":2,");
        assertThrows(JsonProcessingException.class, () -> parse(mode, duplicateRoot));

        var expected = OfflineGoalTaskReplay.JSON.readTree(original).path("tasks").get(0).path("expected");
        String field = expected.fieldNames().next();
        int start = original.indexOf('{', original.indexOf("\"expected\"")) + 1;
        String duplicateExpected = original.substring(0, start) + "\"" + field + "\":" + expected.get(field)
                + "," + original.substring(start);
        assertThrows(JsonProcessingException.class, () -> parse(mode, duplicateExpected));
    }

    private void parse(String mode, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        switch (mode) {
            case "goal" -> OfflineGoalTaskReplay.parse(bytes);
            case "artifact" -> OfflineArtifactTaskReplay.parse(bytes);
            case "json" -> OfflineJsonArtifactTaskReplay.parse(bytes);
            case "service" -> OfflineGoalServiceTaskReplay.parse(bytes);
            default -> throw new IllegalArgumentException(mode);
        }
    }
}
