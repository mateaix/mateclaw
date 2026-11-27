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

    @Test void treeWaitKeepsLaterParentTurnsAndWaitsForCorrelatedSettlement() throws Exception {
        var turn = new DshSdkTurn("root", "prompt");
        turn.waitForTree("wait");
        turn.accept(frame("{\"id\":\"prompt\",\"result\":{\"messageId\":\"m\"}}"));
        turn.accept(frame("""
                {"method":"session.event","params":{"sessionId":"root","event":{"seq":1,"type":"agent/inbox/spliced","data":{"inserted":[{"id":"m"}]}}}}"""));
        turn.accept(frame("""
                {"method":"session.event","params":{"sessionId":"root","event":{"seq":2,"type":"turn/end","data":{"reason":{"kind":"completed"}}}}}"""));
        turn.accept(frame("{\"method\":\"session.status\",\"params\":{\"sessionId\":\"root\",\"status\":\"idle\"}}"));
        assertFalse(turn.complete(), "Root idle is not task-tree completion");
        turn.accept(frame("""
                {"method":"session.event","params":{"sessionId":"root","event":{"seq":3,"type":"turn/start","data":{}}}}"""));
        turn.accept(frame("""
                {"method":"session.event","params":{"sessionId":"root","event":{"seq":4,"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"child result verified"}]}}}}}"""));
        turn.accept(frame("""
                {"method":"session.event","params":{"sessionId":"root","event":{"seq":5,"type":"turn/end","data":{"reason":{"kind":"completed"}}}}}"""));
        turn.accept(frame("{\"method\":\"session.status\",\"params\":{\"sessionId\":\"root\",\"status\":\"idle\"}}"));
        turn.accept(frame("{\"id\":\"unrelated\",\"result\":{}}"));
        assertFalse(turn.complete());
        turn.accept(frame("{\"id\":\"wait\",\"result\":{}}"));
        assertTrue(turn.complete());
        assertEquals("child result verified", turn.answer());
    }

    @Test void unsupportedTreeWaitRetainsOldIdleContractButOtherErrorsFail() throws Exception {
        var turn = new DshSdkTurn("root", "prompt");
        turn.waitForTree("wait");
        assertDoesNotThrow(() -> turn.accept(frame("{\"id\":\"wait\",\"error\":{\"code\":-32601}}")));
        var failed = new DshSdkTurn("root", "prompt");
        failed.waitForTree("wait");
        assertThrows(IllegalStateException.class,
                () -> failed.accept(frame("{\"id\":\"wait\",\"error\":{\"code\":-32603}}")));
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
        for (String type : java.util.List.of("job/registered", "job/settled", "working-directory/change",
                "action-cards/decision", "team/mode", "workspace-root/change")) {
            var event = json.createObjectNode(); event.put("method", "session.event");
            var params = event.putObject("params"); params.put("sessionId", "root");
            var data = params.putObject("event"); data.put("seq", seq++); data.put("type", type); data.putObject("data");
            assertDoesNotThrow(() -> turn.accept(event));
            assertFalse(turn.complete());
        }
        assertEquals(0, turn.inputTokens());
    }
}
