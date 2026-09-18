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
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the V148 schema additions and the
 * {@code archived} filtering / view-field exposure that rides on them:
 * <ul>
 *   <li>{@code mate_conversation.archived} column exists and is read/write.</li>
 *   <li>{@code webchat_revoked_visitor} table exists (full DDL validation
 *       happens implicitly — Flyway would have failed to apply the migration
 *       otherwise; here we only verify the table is queryable).</li>
 *   <li>{@link WebChatSessionView} now carries {@code pinned/archived/
 *       streamStatus}, so the visitor-side listing surfaces the same state
 *       the admin console sees.</li>
 *   <li>{@code loadVisitorSessions} filters out archived threads by default;
 *       {@code includeArchived=true} opts back in.</li>
 * </ul>
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_schema_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatSchemaFieldsTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh";
    private static final long CHANNEL_ID = 9_147_301L;
    private static final long AGENT_ID = 9_147_3011L;

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
                        "KEY(id) VALUES (?, 'wc-schema-agent', 'react', '', 10, TRUE, 1, " +
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
    @DisplayName("revoked-visitor table is queryable (DDL applied)")
    void revokedVisitorTableExists() {
        // Insert + read back a row to prove the table + columns are live.
        jdbc.update("INSERT INTO webchat_revoked_visitor (id, channel_id, visitor_id, reason) " +
                "VALUES (?, ?, ?, ?)", 9991L, CHANNEL_ID, "schema-probe", "test");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webchat_revoked_visitor WHERE channel_id = ? AND visitor_id = ?",
                Integer.class, CHANNEL_ID, "schema-probe");
        assertThat(count).isEqualTo(1);
        jdbc.update("DELETE FROM webchat_revoked_visitor WHERE id = ?", 9991L);
    }

    @Test
    @DisplayName("archived column on mate_conversation is read/write")
    void archivedColumnReadWrite() {
        controller.createSession(API_KEY, req("visitorArch", "s1"));
        String cid = WebChatController.deriveConversationId(CHANNEL_ID, "visitorArch", "s1");

        Integer before = jdbc.queryForObject(
                "SELECT archived FROM mate_conversation WHERE conversation_id = ?",
                Integer.class, cid);
        assertThat(before).isZero();

        jdbc.update("UPDATE mate_conversation SET archived = 1 WHERE conversation_id = ?", cid);
        Integer after = jdbc.queryForObject(
                "SELECT archived FROM mate_conversation WHERE conversation_id = ?",
                Integer.class, cid);
        assertThat(after).isEqualTo(1);
    }

    @Test
    @DisplayName("WebChatSessionView exposes pinned/archived/streamStatus")
    void viewExposesNewFields() {
        controller.createSession(API_KEY, req("visitorView", "s1"));
        // Flip pinned via the service (endpoint comes in PR 3) so we can assert the view mirrors it.
        String cid = WebChatController.deriveConversationId(CHANNEL_ID, "visitorView", "s1");
        conversationService.setPinned(cid, true);

        R<List<WebChatSessionView>> r = controller.listSessions(
                API_KEY, tokenFor("visitorView"), "visitorView", false);
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).hasSize(1);
        WebChatSessionView view = r.getData().get(0);
        assertThat(view.getPinned()).isEqualTo(1);
        assertThat(view.getArchived()).isZero();
        assertThat(view.getStreamStatus()).isEqualTo("idle");
    }

    @Test
    @DisplayName("archived threads are hidden from /sessions by default")
    @SuppressWarnings("unchecked")
    void archivedHiddenByDefault() {
        controller.createSession(API_KEY, req("visitorHide", "active"));
        controller.createSession(API_KEY, req("visitorHide", "stale"));

        String staleCid = WebChatController.deriveConversationId(CHANNEL_ID, "visitorHide", "stale");
        jdbc.update("UPDATE mate_conversation SET archived = 1 WHERE conversation_id = ?", staleCid);

        // Default: only "active" is returned.
        R<?> def = controller.listSessions(API_KEY, tokenFor("visitorHide"), "visitorHide", false);
        assertThat(((List<WebChatSessionView>) def.getData()))
                .extracting(WebChatSessionView::getSessionId)
                .containsExactly("active");

        // includeArchived=true: both.
        R<?> all = controller.listSessions(API_KEY, tokenFor("visitorHide"), "visitorHide", true);
        assertThat(((List<WebChatSessionView>) all.getData()))
                .extracting(WebChatSessionView::getSessionId)
                .containsExactlyInAnyOrder("active", "stale");
    }

    @Test
    @DisplayName("archived empty threads don't count against the 5-empty-session quota")
    void archivedExcludedFromQuota() {
        // Pre-seed 5 archived empty threads + verify a 6th (active) creation still succeeds —
        // the quota gate filters archived out, so the active count is 0 here.
        String owner = WebChatController.webchatUsername("visitorQuota");
        for (int i = 1; i <= 5; i++) {
            String cid = WebChatController.deriveConversationId(CHANNEL_ID, "visitorQuota", "arch" + i);
            conversationService.getOrCreateWebchatConversation(
                    cid, AGENT_ID, owner, 1L, "arch" + i);
            jdbc.update("UPDATE mate_conversation SET archived = 1 WHERE conversation_id = ?", cid);
        }

        R<?> r = controller.createSession(API_KEY, req("visitorQuota", "fresh"));
        assertThat(r.getCode())
                .as("archived threads must not saturate the empty-session quota")
                .isEqualTo(200);
    }
}
