package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.channel.webchat.WebChatController.WebChatSessionView;
import vip.mate.common.result.R;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the WebChat visitor session-management endpoints
 * against a booted context + real H2 (migrations incl. V147 run). Exercises the
 * controller's real auth (channel lookup + visitor-token HMAC), pagination,
 * keyword search, rename, and — the key case — that a thread whose
 * conversationId hashed (long visitorId + sessionId) is still listed with its
 * sessionId recovered from the persisted column.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_sess_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatSessionManagementTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh"; // key8 = "testkey1"
    private static final String VISITOR = "visitorAAAA";
    private static final long CHANNEL_ID = 9_147_001L;
    // Long enough that "webchat:testkey1:visitorAAAA:<this>" exceeds 64 chars and hashes.
    private static final String LONG_SESSION = "session-1234567890-abcdefghij-klmnopqrst";

    @Autowired private WebChatController controller;
    @Autowired private ConversationService conversationService;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, "{\"api_key\":\"" + API_KEY + "\"}");

        String owner = WebChatController.webchatUsername(VISITOR);
        // default thread (no sessionId)
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_ID, VISITOR, null), null, owner, 1L, null);
        // short sessioned thread
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_ID, VISITOR, "s1"), null, owner, 1L, "s1");
        // long sessioned thread → conversationId hashes
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_ID, VISITOR, LONG_SESSION), null, owner, 1L, LONG_SESSION);

        token = WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, VISITOR);
    }

    @Test
    @DisplayName("listSessions includes the hashed long-id thread with its sessionId recovered")
    @SuppressWarnings("unchecked")
    void listsHashedThread() {
        R<List<WebChatSessionView>> r = (R<List<WebChatSessionView>>) (R<?>) controller.listSessions(API_KEY, token, VISITOR, false);
        assertThat(r.getCode()).isEqualTo(200);
        List<WebChatSessionView> sessions = r.getData();
        assertThat(sessions).hasSize(3);
        assertThat(sessions).extracting(WebChatSessionView::getSessionId)
                .containsExactlyInAnyOrder(null, "s1", LONG_SESSION);
    }

    @Test
    @DisplayName("bad visitor token is rejected")
    @SuppressWarnings("unchecked")
    void rejectsBadToken() {
        R<List<WebChatSessionView>> r = (R<List<WebChatSessionView>>) (R<?>) controller.listSessions(API_KEY, "bogus", VISITOR, false);
        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("pageSessions paginates and keyword-searches by title")
    void pagesAndSearches() {
        R<Map<String, Object>> page = controller.pageSessions(API_KEY, token, VISITOR, 1, 2, null, false);
        assertThat(page.getCode()).isEqualTo(200);
        assertThat(page.getData().get("total")).isEqualTo(3L);
        assertThat((List<?>) page.getData().get("items")).hasSize(2);

        // Rename one thread, then search for it.
        controller.renameSession(API_KEY, token, VISITOR, "s1", Map.of("title", "QuarterlyReport"));
        R<Map<String, Object>> hit = controller.pageSessions(API_KEY, token, VISITOR, 1, 20, "quarterly", false);
        assertThat((List<?>) hit.getData().get("items")).hasSize(1);
    }

    @Test
    @DisplayName("rename updates the thread title")
    void renames() {
        R<Void> r = controller.renameSession(API_KEY, token, VISITOR, "s1", Map.of("title", "Renamed"));
        assertThat(r.getCode()).isEqualTo(200);

        String cid = WebChatController.deriveConversationId(CHANNEL_ID, VISITOR, "s1");
        String title = jdbc.queryForObject(
                "SELECT title FROM mate_conversation WHERE conversation_id = ?", String.class, cid);
        assertThat(title).isEqualTo("Renamed");
    }

    @Test
    @DisplayName("sessionMessages paginates with hasMore")
    @SuppressWarnings("unchecked")
    void paginatesMessages() {
        String cid = WebChatController.deriveConversationId(CHANNEL_ID, VISITOR, "s1");
        conversationService.saveMessage(cid, "user", "m1");
        conversationService.saveMessage(cid, "assistant", "m2");
        conversationService.saveMessage(cid, "user", "m3");

        R<?> r = controller.sessionMessages(API_KEY, token, VISITOR, "s1", null, 2);
        assertThat(r.getCode()).isEqualTo(200);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertThat((List<?>) data.get("messages")).hasSize(2);
        assertThat(data.get("hasMore")).isEqualTo(true);
    }
}
