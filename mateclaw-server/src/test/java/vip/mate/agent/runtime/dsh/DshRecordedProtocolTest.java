package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Captured npm release SDK calls on macOS arm64/Node 22.20, Messages API, 2026-09-29 and 2026-10-20.
 * Replay state signatures removed. These recordings do not validate Linux/WSL or tools. */
class DshRecordedProtocolTest {
    @ParameterizedTest @ValueSource(strings = {"0.1.7-rc.2", "0.2.0-rc.1", "0.2.1-rc.2", "0.2.2-rc.1"})
    void recordedModelReplyCorrelatesPreResponseReceiptAndIncludesCachedInput(String version) throws Exception {
        var mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/dsh/" + version + "-sdk.jsonl")) {
            assertNotNull(input);
            var frames = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().map(line -> {
                try { return mapper.readTree(line); } catch (Exception error) { throw new IllegalStateException(error); }
            }).toList();
            String root = frames.stream().filter(frame -> frame.path("params").has("sessionId")).findFirst().orElseThrow().path("params").path("sessionId").asText();
            var turn = new DshSdkTurn(root, version.equals("0.2.1-rc.2") || version.equals("0.2.2-rc.1") ? "probe" : "2");
            for (var frame : frames) turn.accept(frame);
            assertTrue(turn.complete());
            assertEquals(RuntimeEventType.COMPLETED, turn.terminalType());
            assertEquals("SDK_OK", turn.answer());
            assertEquals("0.2.2-rc.1".equals(version) ? 8456 : "0.2.1-rc.2".equals(version) ? 5699 : 5421, turn.inputTokens());
            assertEquals("0.2.2-rc.1".equals(version) ? 34 : 4, turn.outputTokens());
        }
    }

    @ParameterizedTest @ValueSource(strings={"0.2.1-rc.2", "0.2.2-rc.1"})
    void candidateRecordedShellTaskKeepsToolReceiptsAndJobMetadataCorrelated(String version) throws Exception {
        var mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/dsh/" + version + "-tool-sdk.jsonl")) {
            assertNotNull(input);
            var frames = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(line -> { try { return mapper.readTree(line); } catch (Exception e) { throw new IllegalStateException(e); } }).toList();
            String root = frames.stream().filter(frame -> frame.path("params").has("sessionId")).findFirst().orElseThrow().path("params").path("sessionId").asText();
            var turn = new DshSdkTurn(root, "probe");
            var events = new java.util.ArrayList<JsonNode>();
            for (var frame : frames) events.addAll(turn.accept(frame));
            var calls = events.stream().filter(e -> "tool/call".equals(e.path("type").asText())).toList();
            var results = events.stream().filter(e -> "tool/result".equals(e.path("type").asText())).toList();
            assertEquals(1, calls.size()); assertEquals(1, results.size());
            assertEquals("bash", calls.getFirst().path("data").path("name").asText());
            assertEquals(calls.getFirst().path("data").path("callId").asText(), results.getFirst().path("data").path("message").path("toolCallId").asText());
            assertFalse(results.getFirst().path("data").path("message").path("isError").asBoolean());
            assertTrue(turn.complete()); assertEquals(RuntimeEventType.COMPLETED, turn.terminalType());
            assertEquals("DSH_TOOL_OK", turn.answer()); assertTrue(turn.inputTokens() > 0);
        }
    }
}
