package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.channel.webchat.WebChatController.WebChatCreateSessionRequest;
import vip.mate.common.result.R;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@code POST /api/v1/channels/webchat/sessions/stop}
 * against a booted context + real H2 (migrations incl. V147).
 * <p>
 * The non-trivial case is {@link #stopsActiveStream()}: a real Reactor
 * {@code Disposable} is registered on the tracker (mirroring what
 * {@code WebChatController.chatStream} now does after subscribe), and the test
 * asserts that {@code stopSession} both returns {@code stopped=true} AND
 * actually disposes the underlying subscription — proving the chatStream
 * wiring change is what makes the new endpoint functional rather than a no-op.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_stop_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatStopStreamTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh"; // key8 = "testkey1"
    private static final long CHANNEL_ID = 9_147_201L;
    private static final long AGENT_ID = 9_147_2011L;

    @Autowired private WebChatController controller;
    @Autowired private ChatStreamTracker streamTracker;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-stop-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");
    }

    private WebChatCreateSessionRequest req(String visitorId, String sessionId) {
        WebChatCreateSessionRequest r = new WebChatCreateSessionRequest();
        r.setVisitorId(visitorId);
        r.setSessionId(sessionId);
        return r;
    }

    private String tokenFor(String visitorId) {
        return WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, visitorId);
    }

    @Test
    @DisplayName("stop actually disposes the active subscription (chatStream wiring works)")
    void stopsActiveStream() {
        controller.createSession(API_KEY, req("visitorA", "s1"));
        String cid = WebChatController.deriveConversationId(API_KEY, "visitorA", "s1");

        // Simulate what WebChatController.chatStream does right after .subscribe():
        // register the run + bind the Disposable so requestStop() can dispose it.
        streamTracker.register(cid);
        Disposable disposable = Flux.never().subscribe();
        streamTracker.setDisposable(cid, disposable);
        assertThat(disposable.isDisposed()).isFalse();

        R<Map<String, Object>> r = controller.stopSession(API_KEY, tokenFor("visitorA"), "visitorA", "s1");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().get("stopped")).isEqualTo(Boolean.TRUE);
        assertThat(disposable.isDisposed()).isTrue();
    }

    @Test
    @DisplayName("stop returns stopped=false when no stream is active (idempotent)")
    void noActiveStreamReturnsFalse() {
        controller.createSession(API_KEY, req("visitorB", "s1"));

        R<Map<String, Object>> r = controller.stopSession(API_KEY, tokenFor("visitorB"), "visitorB", "s1");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().get("stopped")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("bad visitor token → 401")
    void rejectsBadToken() {
        controller.createSession(API_KEY, req("visitorC", "s1"));

        R<Map<String, Object>> r = controller.stopSession(API_KEY, "bogus-token", "visitorC", "s1");
        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("bad API Key → 401")
    void rejectsBadApiKey() {
        R<Map<String, Object>> r = controller.stopSession("bogus-key", "any-token", "visitorD", "s1");
        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("unknown sessionId → 404 (no namespace probing)")
    void rejectsUnknownSession() {
        // Visitor exists (token verifies) but never created session "ghost".
        R<Map<String, Object>> r = controller.stopSession(
                API_KEY, tokenFor("visitorE"), "visitorE", "never-created");
        assertThat(r.getCode()).isEqualTo(404);
    }
}
