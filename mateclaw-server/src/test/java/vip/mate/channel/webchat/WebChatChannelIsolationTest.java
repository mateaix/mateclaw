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
 * The fix embeds the stable channel DB id ({@code channel.getId()}) into the
 * conversationId. This test seeds two channels whose apiKeys share their first
 * 8 chars (mirroring the collision) plus the same visitorId, then asserts each
 * channel's {@code listSessions} returns only its own sessions.
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
        // Channel A gets two sessions; Channel B gets one — all under the same visitor.
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_A, VISITOR, "a-s1"), null, owner, 1L, "a-s1");
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_A, VISITOR, "a-s2"), null, owner, 1L, "a-s2");
        conversationService.getOrCreateWebchatConversation(
                WebChatController.deriveConversationId(CHANNEL_B, VISITOR, "b-s1"), null, owner, 1L, "b-s1");
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
    @DisplayName("two channels sharing an apiKey prefix isolate their sessions (#558)")
    @SuppressWarnings("unchecked")
    void channelsWithSharedKeyPrefixAreIsolated() {
        // Channel A lists only its own sessions.
        R<List<WebChatSessionView>> ra = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY_A, tokenFor(CHANNEL_A), VISITOR, false);
        assertThat(ra.getCode()).isEqualTo(200);
        assertThat(ra.getData()).extracting(WebChatSessionView::getSessionId)
                .containsExactlyInAnyOrder("a-s1", "a-s2");

        // Channel B lists only its own.
        R<List<WebChatSessionView>> rb = (R<List<WebChatSessionView>>) (R<?>)
                controller.listSessions(API_KEY_B, tokenFor(CHANNEL_B), VISITOR, false);
        assertThat(rb.getCode()).isEqualTo(200);
        assertThat(rb.getData()).extracting(WebChatSessionView::getSessionId)
                .containsExactly("b-s1");
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
}
