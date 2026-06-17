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
import vip.mate.common.result.R;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@code POST /api/v1/channels/webchat/sessions}
 * (explicit empty-session creation) against a booted context + real H2 with
 * migrations (incl. V147 {@code webchat_session_id}) applied.
 * <p>
 * Covers the four behaviors promised in issue #351:
 * <ol>
 *   <li>happy path inserts an empty thread and returns sessionId/conversationId/
 *       visitorToken;</li>
 *   <li>a caller-supplied title is persisted and survives the first /stream
 *       user message (saveMessage's "title-derive" guard must not fire);</li>
 *   <li>re-creating with a colliding sessionId is idempotent — 200, no title
 *       clobber;</li>
 *   <li>the empty-session quota (≤ 5) is enforced with a clear 409.</li>
 * </ol>
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_create_sess_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatCreateSessionTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh"; // key8 = "testkey1"
    private static final long CHANNEL_ID = 9_147_101L;
    private static final long AGENT_ID = 9_147_1011L;

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
                        "KEY(id) VALUES (?, 'wc-test-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");
    }

    private WebChatCreateSessionRequest req(String visitorId, String sessionId, String title) {
        WebChatCreateSessionRequest r = new WebChatCreateSessionRequest();
        r.setVisitorId(visitorId);
        r.setSessionId(sessionId);
        r.setTitle(title);
        return r;
    }

    @Test
    @DisplayName("createSession inserts an empty thread and returns all required fields")
    void createsEmptySession() {
        R<Map<String, Object>> r = controller.createSession(API_KEY, req("visitorA", "s1", null));
        assertThat(r.getCode()).isEqualTo(200);
        Map<String, Object> data = r.getData();
        assertThat(data.get("sessionId")).isEqualTo("s1");
        assertThat(data.get("conversationId"))
                .isEqualTo(WebChatController.deriveConversationId(API_KEY, "visitorA", "s1"));
        assertThat(data.get("visitorToken"))
                .isEqualTo(WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, "visitorA"));
        // No title supplied → default placeholder, will be derived from first user message later.
        assertThat(data.get("title")).isEqualTo("新对话");
        assertThat(data.get("createTime")).isNotNull();

        // Row actually persisted with message_count = 0.
        Integer count = jdbc.queryForObject(
                "SELECT message_count FROM mate_conversation WHERE conversation_id = ?",
                Integer.class, data.get("conversationId"));
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("caller-supplied title survives the first /stream user message")
    void titleSurvivesFirstMessage() {
        String cid = (String) controller
                .createSession(API_KEY, req("visitorB", "s-title", "Quarterly Report"))
                .getData().get("conversationId");

        // Simulate /stream saving the first user message.
        conversationService.saveMessage(cid, "user", "随便说点什么,看看会不会把 title 覆盖掉");

        String persisted = jdbc.queryForObject(
                "SELECT title FROM mate_conversation WHERE conversation_id = ?",
                String.class, cid);
        assertThat(persisted).isEqualTo("Quarterly Report");
    }

    @Test
    @DisplayName("default-title thread still derives its title from the first user message")
    void defaultTitleIsDerivedFromFirstMessage() {
        String cid = (String) controller
                .createSession(API_KEY, req("visitorC", "s-default", null))
                .getData().get("conversationId");

        conversationService.saveMessage(cid, "user", "今天天气不错");

        String persisted = jdbc.queryForObject(
                "SELECT title FROM mate_conversation WHERE conversation_id = ?",
                String.class, cid);
        assertThat(persisted).isEqualTo("今天天气不错");
    }

    @Test
    @DisplayName("re-create with colliding sessionId is idempotent — no title clobber")
    void isIdempotentOnCollision() {
        // First call creates with a caller title.
        controller.createSession(API_KEY, req("visitorD", "s-collide", "OriginalTitle"));

        // Second call tries to re-create the same sessionId with a different title.
        R<Map<String, Object>> r = controller
                .createSession(API_KEY, req("visitorD", "s-collide", "AttemptedOverride"));
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().get("title")).isEqualTo("OriginalTitle");

        String persisted = jdbc.queryForObject(
                "SELECT title FROM mate_conversation WHERE conversation_id = ?",
                String.class, r.getData().get("conversationId"));
        assertThat(persisted).isEqualTo("OriginalTitle");
    }

    @Test
    @DisplayName("empty-session quota (≤ 5) is enforced with a 409")
    void enforcesQuota() {
        // Pre-seed 5 empty threads directly through the service (bypasses the controller
        // quota so we can verify the controller is the gate, not the service).
        String owner = WebChatController.webchatUsername("visitorE");
        for (int i = 1; i <= 5; i++) {
            conversationService.getOrCreateWebchatConversation(
                    WebChatController.deriveConversationId(API_KEY, "visitorE", "seed" + i),
                    null, owner, 1L, "seed" + i);
        }

        R<Map<String, Object>> r = controller.createSession(API_KEY, req("visitorE", "s-new", null));
        assertThat(r.getCode()).isEqualTo(409);
        assertThat(r.getMsg()).contains("未活跃会话数已达上限");
    }

    @Test
    @DisplayName("bad API Key → 401")
    void rejectsBadApiKey() {
        R<Map<String, Object>> r = controller.createSession("bogus-key", req("visitorF", "s1", null));
        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("illegal sessionId charset → 400")
    void rejectsIllegalSessionId() {
        R<Map<String, Object>> r = controller
                .createSession(API_KEY, req("visitorG", "has space", null));
        assertThat(r.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("illegal title length (>100) → 400")
    void rejectsOverlongTitle() {
        R<Map<String, Object>> r = controller
                .createSession(API_KEY, req("visitorH", "s1", "x".repeat(101)));
        assertThat(r.getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("once a session is created, listSessions sees it (with the recovered sessionId)")
    @SuppressWarnings("unchecked")
    void createdSessionIsListable() {
        controller.createSession(API_KEY, req("visitorI", "s-listed", null));

        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, "visitorI");
        R<?> r = controller.listSessions(API_KEY, token, "visitorI");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(((java.util.List<WebChatController.WebChatSessionView>) (Object) r.getData()))
                .extracting(WebChatController.WebChatSessionView::getSessionId)
                .contains("s-listed");
    }
}
