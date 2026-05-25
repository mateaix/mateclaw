package vip.mate.stt.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.stt.provider.DashScopeSttProvider.DashScopeSession;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the message-handling state machine of
 * {@link DashScopeSttProvider}. The end-to-end WebSocket flow can't be
 * exercised without a mock WS server, but the JSON parsing + transcript
 * aggregation + latch transitions are fully testable in isolation by
 * driving {@link DashScopeSession#handleMessage(String)} directly.
 *
 * <p>What these tests guard against:
 * <ul>
 *   <li>"Two events for the same begin_time" — the second event must
 *       <b>overwrite</b> the first (interim → final), not append.
 *       Otherwise you get duplicated text in the final transcript.</li>
 *   <li>Sentence ordering — multi-sentence speech must come out in
 *       arrival order regardless of begin_time int values.</li>
 *   <li>task-failed must surface the error message on both latches so
 *       the caller doesn't time out for the full 60s budget.</li>
 * </ul>
 */
class DashScopeSttProviderTest {

    private DashScopeSession session;
    private DashScopeSttProvider provider;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        session = new DashScopeSession("test-task-id", mapper);
        provider = new DashScopeSttProvider(null, mapper);
    }

    @Test
    @DisplayName("task-started event releases the start latch")
    void taskStarted_releasesLatch() throws Exception {
        session.handleMessage("""
                {"header":{"task_id":"test-task-id","event":"task-started"},"payload":{}}
                """);
        assertTrue(session.awaitTaskStarted(100, TimeUnit.MILLISECONDS));
        assertFalse(session.failed());
    }

    @Test
    @DisplayName("result-generated builds transcript text")
    void resultGenerated_appendsToTranscript() {
        session.handleMessage("""
                {"header":{"task_id":"test-task-id","event":"result-generated"},
                 "payload":{"output":{"sentence":{"begin_time":0,"end_time":1500,"text":"你好"}}}}
                """);
        assertEquals("你好", session.aggregatedText());
    }

    @Test
    @DisplayName("interim updates for the same begin_time overwrite (not append)")
    void resultGenerated_overwritesSameBeginTime() {
        // Real DashScope behaviour: each sentence starts as a partial
        // transcript and gets refined on subsequent events. Both events
        // share the same begin_time. If we appended instead of overwriting
        // we'd produce "你你好" instead of "你好".
        session.handleMessage("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"begin_time":0,"end_time":500,"text":"你"}}}}
                """);
        session.handleMessage("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"begin_time":0,"end_time":1500,"text":"你好"}}}}
                """);
        assertEquals("你好", session.aggregatedText());
    }

    @Test
    @DisplayName("multiple sentences concatenate in arrival order")
    void resultGenerated_concatenatesSentencesInOrder() {
        // Different begin_time → different sentences. Final transcript is
        // the concat of all sentences in arrival order (LinkedHashMap).
        session.handleMessage("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"begin_time":0,"end_time":1500,"text":"你好"}}}}
                """);
        session.handleMessage("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"begin_time":1500,"end_time":3000,"text":"世界"}}}}
                """);
        assertEquals("你好世界", session.aggregatedText());
    }

    @Test
    @DisplayName("task-finished releases the finish latch")
    void taskFinished_releasesLatch() throws Exception {
        session.handleMessage("""
                {"header":{"event":"task-finished"},"payload":{}}
                """);
        assertTrue(session.awaitTaskFinished(100, TimeUnit.MILLISECONDS));
        assertFalse(session.failed());
    }

    @Test
    @DisplayName("task-failed surfaces error message and unblocks both latches")
    void taskFailed_surfacesErrorAndUnblocks() throws Exception {
        // Critical for fail-fast behaviour: without this the caller would
        // time out after the full 60s OVERALL_TIMEOUT_MS instead of seeing
        // the typed error within milliseconds.
        session.handleMessage("""
                {"header":{"event":"task-failed",
                           "error_code":"InvalidParameter.SampleRate",
                           "error_message":"sample rate not supported"},
                 "payload":{}}
                """);
        assertTrue(session.awaitTaskStarted(100, TimeUnit.MILLISECONDS));
        assertTrue(session.awaitTaskFinished(100, TimeUnit.MILLISECONDS));
        assertTrue(session.failed());
        assertTrue(session.errorMessage().contains("InvalidParameter.SampleRate"));
        assertTrue(session.errorMessage().contains("sample rate not supported"));
    }

    @Test
    @DisplayName("resultEventCount tracks every result-generated event (regardless of text)")
    void resultEventCount_isIncrementedPerEvent() {
        // Distinguishing "server got our audio but didn't recognise anything"
        // (>0 events with empty text) from "server saw 0 audio frames"
        // (0 events) is the diagnostic that fingered the chunk-pacing bug.
        // Pin the counter behaviour so it doesn't regress.
        assertEquals(0, session.resultEventCount());
        session.handleMessage("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"begin_time":0,"text":"hi"}}}}
                """);
        session.handleMessage("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"begin_time":1000,"text":""}}}}
                """);
        assertEquals(2, session.resultEventCount());
    }

    @Test
    @DisplayName("taskFinishedRaised flips once task-finished arrives — sender uses it to bail out early")
    void taskFinishedRaised_signalsSender() {
        // The sender loop polls this between paced chunks so a server that
        // closes the stream early doesn't make us sleep through the rest of
        // the audio for nothing.
        assertFalse(session.taskFinishedRaised());
        session.handleMessage("""
                {"header":{"event":"task-finished"},"payload":{}}
                """);
        assertTrue(session.taskFinishedRaised());
    }

    @Test
    @DisplayName("malformed JSON doesn't crash the session")
    void malformedJson_isLoggedNotThrown() {
        // The session is fed straight from WS frames — corrupt input must
        // not bubble up into the WebSocket.Listener and tear down the
        // connection.
        session.handleMessage("not valid json");
        session.handleMessage("{\"missing_header\":true}");
        // No event released either latch; session is still waiting.
        assertFalse(session.failed());
    }

    @Test
    @DisplayName("buildRunTask serialises the documented run-task envelope")
    void buildRunTask_envelopeShape() throws Exception {
        // The wire format is documented by Aliyun — pin it so future
        // refactors don't accidentally drop a required field.
        String json = provider.buildRunTask(
                "abcd1234efgh5678", "paraformer-realtime-v2", 16_000, "zh-CN");
        JsonNode node = mapper.readTree(json);
        assertEquals("run-task", node.path("header").path("action").asText());
        assertEquals("abcd1234efgh5678", node.path("header").path("task_id").asText());
        assertEquals("duplex", node.path("header").path("streaming").asText());
        assertEquals("audio", node.path("payload").path("task_group").asText());
        assertEquals("asr", node.path("payload").path("task").asText());
        assertEquals("recognition", node.path("payload").path("function").asText());
        assertEquals("paraformer-realtime-v2", node.path("payload").path("model").asText());
        assertEquals("pcm", node.path("payload").path("parameters").path("format").asText());
        assertEquals(16_000, node.path("payload").path("parameters").path("sample_rate").asInt());
        // language_hints strips the locale: zh-CN → zh
        assertEquals("zh", node.path("payload").path("parameters").path("language_hints").get(0).asText());
    }

    @Test
    @DisplayName("buildRunTask omits language_hints when language is null")
    void buildRunTask_skipsLanguageHintsWhenNull() throws Exception {
        // Null language means "let DashScope auto-detect" — sending an
        // empty array would flag as a parameter error on some accounts.
        String json = provider.buildRunTask("task1", "paraformer-realtime-v2", 16_000, null);
        JsonNode node = mapper.readTree(json);
        assertTrue(node.path("payload").path("parameters").path("language_hints").isMissingNode(),
                "language_hints should be omitted when language is null");
    }

    @Test
    @DisplayName("buildFinishTask serialises the documented finish-task envelope")
    void buildFinishTask_envelopeShape() throws Exception {
        String json = provider.buildFinishTask("abcd1234");
        JsonNode node = mapper.readTree(json);
        assertEquals("finish-task", node.path("header").path("action").asText());
        assertEquals("abcd1234", node.path("header").path("task_id").asText());
        assertEquals("duplex", node.path("header").path("streaming").asText());
        // payload.input is required to be an empty object — DashScope
        // rejects requests where it's missing or null.
        assertTrue(node.path("payload").path("input").isObject());
    }

    @Test
    @DisplayName("computePcmPeakRms returns 0,0 on silence; non-zero on synthetic tone")
    void computePcmPeakRms_distinguishesSilenceFromSignal() {
        // The diagnostic distinguishing "mic captured silence" (peak=0) from
        // "DashScope rejected non-empty audio" (peak>0 but 0 events) is a
        // critical user-visible signal — pin its math.
        byte[] silent = new byte[1000];                          // all zeros
        int[] silentStats = DashScopeSttProvider.computePcmPeakRms(silent);
        assertEquals(0, silentStats[0]);
        assertEquals(0, silentStats[1]);

        // Two samples: 0x4000 (16384, positive) and 0xC000 (-16384, negative).
        // peak should be 16384, rms = sqrt((16384^2 + 16384^2) / 2) = 16384.
        byte[] tone = new byte[]{
                0x00, 0x40,   // 16384 little-endian
                0x00, (byte) 0xC0  // -16384 little-endian
        };
        int[] toneStats = DashScopeSttProvider.computePcmPeakRms(tone);
        assertEquals(16384, toneStats[0]);
        assertEquals(16384, toneStats[1]);
    }

    @Test
    @DisplayName("autoDetectOrder boosts DashScope on Chinese, defaults otherwise")
    void autoDetectOrder_languageRouting() {
        assertEquals(60, provider.autoDetectOrder("zh"));
        assertEquals(60, provider.autoDetectOrder("zh-CN"));
        assertEquals(60, provider.autoDetectOrder("ZH-Hant"));   // case-insensitive
        assertEquals(150, provider.autoDetectOrder("en-US"));    // default order
        assertEquals(150, provider.autoDetectOrder(null));       // language unknown
    }
}
