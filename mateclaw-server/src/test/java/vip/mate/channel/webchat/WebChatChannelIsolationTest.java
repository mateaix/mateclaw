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
 * Regression for ISSUE #558: WebChat channel session isolation.
 * <p>
 * The bug: {@code deriveConversationId} identified a channel by the first 8
 * characters of its apiKey. All generated keys share the 11-char prefix
 * {@code mc_webchat_}, so the 8-char slice was the constant {@code mc_webch} for
 * every channel — two channels with the same visitorId derived the SAME
 * conversationId, and their sessions collided / cross-leaked in
 * {@code listSessions}.
 * <p>
 * The fix (V171 {@code channel_id} column) persists the owning channel on each
 * webchat row, so {@code listSessions} filters by {@code channel_id} exactly.
 * <p>
 * Two isolation dimensions are covered:
 * <ol>
 *   <li><b>Channel-scoped rows</b> (channel_id persisted): strictly isolated —
 *       channel A never sees channel B's sessions. This is the regression that
 *       the pre-fix code failed.</li>
 *   <li><b>Pre-fix collided rows</b> (channel_id NULL, shared legacy cid): these
 *       cannot be split retroactively and remain visible across channels that
 *       collided. This is an explicitly documented limitation, not a bug.</li>
 * </ol>
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_iso_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatChannelIsolationTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    // Both keys start with "mc_webchat_" → identical 8-char slice "mc_webch",
    // reproducing the #558 collision under the pre-fix derivation.
    private static final String API_KEY_A = "mc_webchat_aaa";
    private static final String API_KEY_B = "mc_webchat_bbb";
    private static final long CHANNEL_A = 9_155_001L;
    private static final long CHANNEL_B = 9_155_002L;
    private static final long AGENT_ID = 9_155_011L;
    private static final String VISITOR = "sharedVisitor";

    @Autowired private WebChatController controller;
    @Autowired private ConversationService conversationService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id IN (?, ?)", CHANNEL_A, CHANNEL_B);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-iso-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        seedChannel(CHANNEL_A, API_KEY_A);
        seedChannel(CHANNEL_B, API_KEY_B);

        String owner = WebChatController.webchatUsername(VISITOR);
        // Channel-scoped rows (channel_id persisted) — the new, correctly-isolated path.
        // Seeded through the channelId-aware overload exactly like createSession/chatStream do.
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_A, VISITOR, "a-s1"),
                null, owner, 1L, "a-s1", null, CHANNEL_A);
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_A, VISITOR, "a-s2"),
                null, owner, 1L, "a-s2", null, CHANNEL_A);
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_B, VISITOR, "b-s1"),
                null, owner, 1L, "b-s1", null, CHANNEL_B);

        // One pre-fix collided row: a legacy cid (webchat:mc_webch:...) with channel_id
        // left NULL. Both channels derive the SAME legacy cid for this visitor, so this
        // single row is shared — it must stay visible to both (cannot be split).
        String legacyCid = WebChatController.legacyConversationId(API_KEY_A, VISITOR, "shared-legacy");
        conversationService.getOrCreateWebchatConversation(legacyCid, null, owner, 1L, null);
    }

    private void seedChannel(long id, String apiKey) {
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, ?, 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, "wc-" + id, AGENT_ID, "{\"api_key\":\"" + apiKey + "\"}");
    }

    private String tokenFor(long channelId) {
        return WebChatController.computeVisitorToken(SECRET, channelId, VISITOR);
    }

    @Test
    @DisplayName("channel-scoped rows are strictly isolated across channels (#558)")
    @SuppressWarnings("unchecked")
    void channelScopedRowsAreIsolated() {
        // Channel A lists only its own channel-scoped sessions + the shared legacy row.
        R<List<WebChatSessionView>> ra = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY_A, tokenFor(CHANNEL_A), VISITOR, false);
        assertThat(ra.getCode()).isEqualTo(200);
        assertThat(ra.getData()).extracting(WebChatSessionView::getSessionId)
                .containsExactlyInAnyOrder("a-s1", "a-s2", "shared-legacy");
        // Critically: channel B's b-s1 does NOT leak into channel A.
        assertThat(ra.getData()).extracting(WebChatSessionView::getSessionId)
                .doesNotContain("b-s1");

        // Channel B lists only its own + the same shared legacy row.
        R<List<WebChatSessionView>> rb = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY_B, tokenFor(CHANNEL_B), VISITOR, false);
        assertThat(rb.getCode()).isEqualTo(200);
        assertThat(rb.getData()).extracting(WebChatSessionView::getSessionId)
                .containsExactlyInAnyOrder("b-s1", "shared-legacy");
        assertThat(rb.getData()).extracting(WebChatSessionView::getSessionId)
                .doesNotContain("a-s1", "a-s2");
    }

    @Test
    @DisplayName("the two channels derive distinct conversationIds for one visitor")
    void conversationIdsDifferAcrossChannels() {
        // Under the #558 bug both calls returned the SAME id (webchat:mc_webch:...).
        String cidA = WebChatController.deriveConversationId(CHANNEL_A, VISITOR, "shared");
        String cidB = WebChatController.deriveConversationId(CHANNEL_B, VISITOR, "shared");
        assertThat(cidA).isNotEqualTo(cidB);
        assertThat(cidA).startsWith("webchat:" + CHANNEL_A + ":");
        assertThat(cidB).startsWith("webchat:" + CHANNEL_B + ":");
    }

    @Test
    @DisplayName("persisted channel_id is recorded on creation")
    void channelIdIsPersisted() {
        // Direct DB check: the channel-scoped rows carry the right channel_id.
        Long aChannel = jdbc.queryForObject(
                "SELECT channel_id FROM mate_conversation WHERE conversation_id = ?",
                Long.class, WebChatController.deriveConversationId(CHANNEL_A, VISITOR, "a-s1"));
        assertThat(aChannel).isEqualTo(CHANNEL_A);
        Long legacyChannel = jdbc.queryForObject(
                "SELECT channel_id FROM mate_conversation WHERE conversation_id = ?",
                Long.class,
                WebChatController.legacyConversationId(API_KEY_A, VISITOR, "shared-legacy"));
        assertThat(legacyChannel).isNull(); // pre-fix row, not backfilled
    }
}
