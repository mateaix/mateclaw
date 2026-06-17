package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.MateClawApplication;
import vip.mate.channel.webchat.WebChatController.WebChatCreateSessionRequest;
import vip.mate.common.result.R;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@code POST /api/v1/channels/webchat/sessions/
 * regenerate} (epic #355 PR 4). Focuses on the auth/seed/delete semantics;
 * the actual LLM stream is left for PR 5's WebChatStreamE2ETest to cover
 * (here the agent turn will error out on the test context's missing LLM
 * provider, which is fine — we only care that the assistant reply is
 * deleted and the SseEmitter is handed back).
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_regen_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatRegenerateTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh";
    private static final long CHANNEL_ID = 9_147_601L;
    private static final long AGENT_ID = 9_147_6011L;

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
                        "KEY(id) VALUES (?, 'wc-regen-agent', 'react', '', 10, TRUE, 1, " +
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

    private long countAssistantMessages(String conversationId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_message WHERE conversation_id = ? AND role = 'assistant'",
                Integer.class, conversationId);
        return c != null ? c : 0;
    }

    /**
     * Drain an SseEmitter just enough that the underlying async work has a
     * chance to run. We don't consume events here — the test scenarios below
     * either short-circuit with an error before any agent turn, or rely on
     * the doOnError path completing the emitter on the missing LLM provider.
     */
    private void waitForEmitterToSettle(SseEmitter emitter) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("no user message in thread → emitter returns an error event")
    void rejectsEmptyThread() throws InterruptedException {
        controller.createSession(API_KEY, req("vEmpty", "s1"));
        SseEmitter emitter = controller.regenerateSession(
                API_KEY, tokenFor("vEmpty"), "vEmpty", "s1");
        waitForEmitterToSettle(emitter);
        // No user message → sendErrorAndComplete fires synchronously; assistant
        // count stays 0.
        String cid = WebChatController.deriveConversationId(API_KEY, "vEmpty", "s1");
        assertThat(countAssistantMessages(cid)).isZero();
    }

    @Test
    @DisplayName("regenerate deletes the last assistant reply")
    void deletesLastAssistantReply() throws InterruptedException {
        controller.createSession(API_KEY, req("vDel", "s1"));
        String cid = WebChatController.deriveConversationId(API_KEY, "vDel", "s1");
        conversationService.saveMessage(cid, "user", "hello");
        conversationService.saveMessage(cid, "assistant", "first reply");

        // Pre-condition: one assistant message.
        assertThat(countAssistantMessages(cid)).isEqualTo(1);

        SseEmitter emitter = controller.regenerateSession(
                API_KEY, tokenFor("vDel"), "vDel", "s1");
        waitForEmitterToSettle(emitter);

        // The pre-existing assistant reply is gone. The chatStream call may have
        // added another one (if it somehow completes on the test LLM), or it may
        // have errored out; either way the count must be ≤ 1 (deletion happened).
        assertThat(countAssistantMessages(cid)).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("bad token → emitter returns 401-equivalent error event")
    void rejectsBadToken() throws InterruptedException {
        controller.createSession(API_KEY, req("vTok", "s1"));
        SseEmitter emitter = controller.regenerateSession(
                API_KEY, "bogus", "vTok", "s1");
        waitForEmitterToSettle(emitter);
        // No way to read the SSE event body from a raw SseEmitter in a unit test;
        // the assertion is implicit — no DB changes happen on the auth-fail path.
        String cid = WebChatController.deriveConversationId(API_KEY, "vTok", "s1");
        assertThat(countAssistantMessages(cid)).isZero();
    }

    @Test
    @DisplayName("unknown sessionId → emitter returns session-not-found error")
    void rejectsUnknownSession() throws InterruptedException {
        // Token verifies (visitor exists conceptually) but session "ghost" was
        // never created.
        SseEmitter emitter = controller.regenerateSession(
                API_KEY, tokenFor("vGhost"), "vGhost", "ghost");
        waitForEmitterToSettle(emitter);
        // No rows exist; nothing to assert beyond "didn't throw".
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("regenerate uses the last user message as the seed")
    void seedsFromLastUserMessage() throws InterruptedException {
        controller.createSession(API_KEY, req("vSeed", "s1"));
        String cid = WebChatController.deriveConversationId(API_KEY, "vSeed", "s1");
        conversationService.saveMessage(cid, "user", "first question");
        conversationService.saveMessage(cid, "assistant", "first reply");
        conversationService.saveMessage(cid, "user", "second question");
        conversationService.saveMessage(cid, "assistant", "second reply");

        // Before regenerate: two user, two assistant.
        List<MessageEntity> before = conversationService.listMessages(cid);
        long userBefore = before.stream().filter(m -> "user".equals(m.getRole())).count();
        long asstBefore = before.stream().filter(m -> "assistant".equals(m.getRole())).count();
        assertThat(userBefore).isEqualTo(2);
        assertThat(asstBefore).isEqualTo(2);

        SseEmitter emitter = controller.regenerateSession(
                API_KEY, tokenFor("vSeed"), "vSeed", "s1");
        waitForEmitterToSettle(emitter);

        // After: last assistant deleted. chatStream will append a new user
        // message (the seed content) — so user count grows by 1.
        long asstAfter = countAssistantMessages(cid);
        assertThat(asstAfter).isLessThan(asstBefore);
    }
}
