package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import static org.junit.jupiter.api.Assertions.*;
/** Synthetic envelopes matching pinned 0.2.0-rc.1 SDK shapes; no model compatibility claim. */
class DshSdkTurnTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode frame(String body) throws Exception { return json.readTree(body); }
    @Test void buffersBeforePromptResponseAndRequiresRootReceiptThenTerminalThenIdle() throws Exception {
        var turn = new DshSdkTurn("root", "prompt");
        turn.accept(frame("""
{"method":"session.event","params":{"sessionId":"root","event":{"seq":1,"type":"agent/inbox/spliced","data":{"inserted":[{"id":"m1"}]}}}}"""));
        turn.accept(frame("""
{"method":"session.status","params":{"sessionId":"child","status":"idle"}}"""));
        turn.accept(frame("""
{"id":"prompt","result":{"messageId":"m1"}}"""));
        assertFalse(turn.complete());
        turn.accept(frame("""
{"method":"session.event","params":{"sessionId":"root","event":{"seq":2,"type":"turn/end","data":{"reason":{"kind":"completed"}}}}}"""));
        assertFalse(turn.complete());
        turn.accept(frame("""
{"method":"session.status","params":{"sessionId":"root","status":"idle"}}"""));
        assertTrue(turn.complete());
        assertEquals(RuntimeEventType.COMPLETED, turn.terminalType());
    }
    @Test void idleWithoutTerminalEvidenceFails() throws Exception {
        var turn = new DshSdkTurn("root", "prompt");
        turn.accept(frame("""
{"id":"prompt","result":{"messageId":"m1"}}"""));
        turn.accept(frame("""
{"method":"session.event","params":{"sessionId":"root","event":{"seq":1,"type":"agent/inbox/spliced","data":{"inserted":[{"id":"m1"}]}}}}"""));
        turn.accept(frame("""
{"method":"session.status","params":{"sessionId":"root","status":"idle"}}"""));
        assertEquals(RuntimeEventType.FAILED, turn.terminalType());
    }
    @Test void deduplicatesCommittedUsageAndKeepsLastRootAnswer() throws Exception {
        var turn = new DshSdkTurn("root", "prompt");
        turn.accept(frame("""
{"id":"prompt","result":{"messageId":"m1"}}"""));
        turn.accept(frame("""
{"method":"session.event","params":{"sessionId":"root","event":{"seq":1,"type":"agent/inbox/spliced","data":{"inserted":[{"id":"m1"}]}}}}"""));
        var answer = frame("""
{"method":"session.event","params":{"sessionId":"root","event":{"seq":2,"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"answer"}]},"usage":{"inputTokens":3,"outputTokens":4,"cacheReadTokens":5,"cacheWriteTokens":7}}}}}""");
        turn.accept(answer); turn.accept(answer);
        assertEquals(15, turn.inputTokens());
        assertEquals(5, turn.cacheReadTokens());
        assertEquals(7, turn.cacheWriteTokens());
        assertEquals(4, turn.outputTokens());
        assertEquals("answer", turn.answer());
    }
    @Test void unknownRootEventCannotSilentlyChangeConversationMeaning() throws Exception {
        var turn = new DshSdkTurn("root", "prompt");
        turn.accept(frame("""
                {"id":"prompt","result":{"messageId":"m1"}}"""));
        turn.accept(frame("""
                {"method":"session.event","params":{"sessionId":"root","event":{"seq":1,"type":"agent/inbox/spliced","data":{"inserted":[{"id":"m1"}]}}}}"""));
        assertThrows(IllegalStateException.class, () -> turn.accept(frame("""
                {"method":"session.event","params":{"sessionId":"root","event":{"seq":2,"type":"unknown/required","data":{}}}}""")));
    }


    @Test void candidateMetadataEventsDoNotCompleteTheRootTurn() throws Exception {
        var turn = new DshSdkTurn("root", "prompt");
        turn.accept(frame("{\"id\":\"prompt\",\"result\":{\"messageId\":\"m1\"}}"));
        turn.accept(frame("{\"method\":\"session.event\",\"params\":{\"sessionId\":\"root\",\"event\":{\"seq\":1,\"type\":\"agent/inbox/spliced\",\"data\":{\"inserted\":[{\"id\":\"m1\"}]}}}}"));
        long seq = 2;
        for (String type : java.util.List.of("job/registered", "job/settled", "working-directory/change")) {
            var event = json.createObjectNode(); event.put("method", "session.event");
            var params = event.putObject("params"); params.put("sessionId", "root");
            var data = params.putObject("event"); data.put("seq", seq++); data.put("type", type); data.putObject("data");
            assertDoesNotThrow(() -> turn.accept(event));
            assertFalse(turn.complete());
        }
        assertEquals(0, turn.inputTokens());
    }
}
