package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.channel.webchat.WebChatController.WebChatCreateSessionRequest;
import vip.mate.channel.webchat.WebChatController.WebChatSessionView;
import vip.mate.common.result.R;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the pin and archive endpoints (epic #355 PR 3).
 * Both endpoints share the same shape (PUT with {flag: true|false} body +
 * query visitorId/sessionId) and the same auth chain as the other session
 * mutations. Tests assert the persisted column flips AND that
 * {@link WebChatController#listSessions} reflects the change accordingly.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_pinarch_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatArchivePinTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh";
    private static final long CHANNEL_ID = 9_147_501L;
    private static final long AGENT_ID = 9_147_5011L;

    @Autowired private WebChatController controller;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-pinarch-agent', 'react', '', 10, TRUE, 1, " +
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
    @DisplayName("PUT /sessions/pinned flips the column + view reports pinned=1")
    void pinFlipsColumn() {
        controller.createSession(API_KEY, req("vPin", "s1"));
        String cid = WebChatController.deriveConversationId(CHANNEL_ID, "vPin", "s1");

        R<Void> r = controller.pinSession(API_KEY, tokenFor("vPin"), "vPin", "s1",
                Map.of("pinned", true));
        assertThat(r.getCode()).isEqualTo(200);

        Integer col = jdbc.queryForObject(
                "SELECT pinned FROM mate_conversation WHERE conversation_id = ?",
                Integer.class, cid);
        assertThat(col).isEqualTo(1);

        @SuppressWarnings("unchecked")
        R<List<WebChatSessionView>> list = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY, tokenFor("vPin"), "vPin", false);
        assertThat(list.getData().get(0).getPinned()).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /sessions/archive hides the thread from default listing")
    void archiveHidesFromDefaultListing() {
        controller.createSession(API_KEY, req("vArch", "s1"));

        R<Void> r = controller.archiveSession(API_KEY, tokenFor("vArch"), "vArch", "s1",
                Map.of("archived", true));
        assertThat(r.getCode()).isEqualTo(200);

        // Default listing: empty.
        @SuppressWarnings("unchecked")
        R<List<WebChatSessionView>> def = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY, tokenFor("vArch"), "vArch", false);
        assertThat(def.getData()).isEmpty();

        // includeArchived=true: shows up with archived=1.
        @SuppressWarnings("unchecked")
        R<List<WebChatSessionView>> all = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY, tokenFor("vArch"), "vArch", true);
        assertThat(all.getData()).hasSize(1);
        assertThat(all.getData().get(0).getArchived()).isEqualTo(1);

        // Un-archive restores visibility.
        controller.archiveSession(API_KEY, tokenFor("vArch"), "vArch", "s1",
                Map.of("archived", false));
        @SuppressWarnings("unchecked")
        R<List<WebChatSessionView>> back = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY, tokenFor("vArch"), "vArch", false);
        assertThat(back.getData()).hasSize(1);
    }

    @Test
    @DisplayName("archived + pinned thread still hidden by default (archive dominates)")
    void archiveDominatesPin() {
        controller.createSession(API_KEY, req("vBoth", "s1"));
        controller.pinSession(API_KEY, tokenFor("vBoth"), "vBoth", "s1",
                Map.of("pinned", true));
        controller.archiveSession(API_KEY, tokenFor("vBoth"), "vBoth", "s1",
                Map.of("archived", true));

        @SuppressWarnings("unchecked")
        R<List<WebChatSessionView>> def = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY, tokenFor("vBoth"), "vBoth", false);
        assertThat(def.getData()).isEmpty();
    }

    @Test
    @DisplayName("missing or wrong-typed body → 400")
    void rejectsMalformedBody() {
        controller.createSession(API_KEY, req("vBad", "s1"));
        // Wrong type:
        R<Void> r1 = controller.pinSession(API_KEY, tokenFor("vBad"), "vBad", "s1",
                Map.of("pinned", "yes"));
        assertThat(r1.getCode()).isEqualTo(400);
        // Wrong key:
        R<Void> r2 = controller.archiveSession(API_KEY, tokenFor("vBad"), "vBad", "s1",
                Map.of("flag", true));
        assertThat(r2.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("unknown sessionId → 404 (no probing)")
    void rejectsUnknownSession() {
        R<Void> r = controller.pinSession(API_KEY, tokenFor("vGhost"), "vGhost", "ghost",
                Map.of("pinned", true));
        assertThat(r.getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("bad token → 401")
    void rejectsBadToken() {
        controller.createSession(API_KEY, req("vTok", "s1"));
        R<Void> r = controller.archiveSession(API_KEY, "bogus", "vTok", "s1",
                Map.of("archived", true));
        assertThat(r.getCode()).isEqualTo(401);
    }
}
