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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-time backward compatibility for ISSUE #558.
 * <p>
 * Conversations created before the fix were keyed by the legacy derivation
 * {@code webchat:<key8>:<visitor>[:<session>]} (key8 = first 8 apiKey chars).
 * The fix cannot retroactively split rows that already collided, so it keeps
 * them visible via read-time fallback in {@code loadVisitorSessions} and
 * {@code resolveSessionConversationId}.
 * <p>
 * This test seeds a legacy-format conversation (+ a message) directly into the
 * DB and asserts the visitor can still list it and read its messages, proving
 * the fallback path works and pre-fix sessions don't "disappear".
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_legacy_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatLegacySessionCompatTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "mc_webchat_xxx"; // key8 = "mc_webch" (legacy collision prefix)
    private static final long CHANNEL_ID = 9_156_001L;
    private static final long AGENT_ID = 9_156_011L;
    private static final String VISITOR = "legacyVisitor";

    @Autowired private WebChatController controller;
    @Autowired private ConversationService conversationService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-legacy-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc-legacy', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");

        String owner = WebChatController.webchatUsername(VISITOR);
        // A legacy conversationId exactly as the pre-fix derivation produced it.
        // The persisted webchat_session_id column is left NULL to force the
        // recoverSessionId fallback path (parse the legacy id).
        String legacyCid = WebChatController.legacyConversationId(API_KEY, VISITOR, "legacy-s1");
        conversationService.getOrCreateWebchatConversation(legacyCid, null, owner, 1L, null);
        conversationService.saveMessage(legacyCid, "user", "hello from the past");
    }

    @Test
    @DisplayName("legacy webchat:<key8>:... session stays visible in listSessions")
    @SuppressWarnings("unchecked")
    void legacySessionIsStillListed() {
        R<List<WebChatSessionView>> r = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY, tokenFor(), VISITOR, false);
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).extracting(WebChatSessionView::getSessionId)
                .contains("legacy-s1");
    }

    @Test
    @DisplayName("legacy session messages are readable via the resolver fallback")
    void legacySessionMessagesReadable() {
        R<?> r = controller.sessionMessages(API_KEY, tokenFor(), VISITOR, "legacy-s1", null, 50);
        assertThat(r.getCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<?> messages = (List<?>) ((java.util.Map<String, Object>) r.getData()).get("messages");
        assertThat(messages).isNotEmpty();
    }

    private String tokenFor() {
        return WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, VISITOR);
    }
}
