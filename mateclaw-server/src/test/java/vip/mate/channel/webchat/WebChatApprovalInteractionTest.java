package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.channel.webchat.WebChatController.WebChatCreateSessionRequest;
import vip.mate.common.result.R;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Verifies ISSUE #413 P1: the WebChat (API-Key) channel can now resolve tool
 * approvals. Before the fix a ToolGuard-protected tool would park the turn in
 * a pending approval the visitor could never clear — it hung for 30 min until
 * the GC timeout and the turn was wasted.
 *
 * <p>Covers the synchronous paths (deny + stop-sweep). The approve path drives
 * a live agent replay stream and is exercised separately; the auth + ownership
 * guards it shares with deny are validated here.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_approve_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789",
        "mateclaw.webchat.orphan-grace-sec=-1"
})
class WebChatApprovalInteractionTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh"; // key8 = "testkey1"
    private static final long CHANNEL_ID = 9_147_310L;
    private static final long AGENT_ID = 9_147_3101L;

    @Autowired private WebChatController controller;
    @Autowired private ApprovalWorkflowService approvalService;
    @Autowired private ChatStreamTracker streamTracker;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private AgentService agentService;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-approve-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setWorkspaceId(1L);
        Mockito.when(agentService.getAgent(AGENT_ID)).thenReturn(agent);
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

    private String seedPending(String visitorId, String sessionId) {
        controller.createSession(API_KEY, req(visitorId, sessionId));
        String cid = WebChatController.deriveConversationId(API_KEY, visitorId, sessionId);
        // The actor stored on the approval is the webchat username, mirroring
        // how chatStream sets it via webchatUsername(visitorId).
        String actor = "webchat:" + API_KEY.substring(0, 8) + ":" + visitorId;
        return approvalService.createPending(
                cid, actor, "write_file", "{}", "high-severity edit",
                "{}", "[]", String.valueOf(AGENT_ID));
    }

    // ---------------- deny ----------------

    @Test
    @DisplayName("deny resolves a pending approval and broadcasts tool_approval_resolved")
    void denyResolvesPending() {
        String pendingId = seedPending("visitorA", "s1");
        String cid = WebChatController.deriveConversationId(API_KEY, "visitorA", "s1");

        // Register the stream so the broadcast has a live subscriber state.
        streamTracker.register(cid);

        R<Map<String, Object>> r = controller.denySession(
                API_KEY, tokenFor("visitorA"), "visitorA", "s1", pendingId);

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().get("resolved")).isEqualTo(Boolean.TRUE);
        assertThat(r.getData().get("decision")).isEqualTo("denied");

        // The approval is no longer pending. Query by the exact pendingId (not
        // findPendingByConversation, which returns the earliest pending and
        // would be polluted by cross-test map state when several pendings
        // coexist for the same conversation).
        var after = approvalService.getPending(pendingId);
        assertThat(after.isEmpty() || !"pending".equals(after.get().getStatus()))
                .as("approval should be resolved, not pending").isTrue();
    }

    @Test
    @DisplayName("deny rejects a bad visitor token → 401")
    void denyRejectsBadToken() {
        String pendingId = seedPending("visitorB", "s1");
        R<Map<String, Object>> r = controller.denySession(
                API_KEY, "bogus-token", "visitorB", "s1", pendingId);
        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("deny rejects an unknown session → 404 (no namespace probing)")
    void denyRejectsUnknownSession() {
        String pendingId = seedPending("visitorC", "s1");
        R<Map<String, Object>> r = controller.denySession(
                API_KEY, tokenFor("visitorC"), "visitorC", "never-created", pendingId);
        assertThat(r.getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("deny on a bad API Key → 401")
    void denyRejectsBadApiKey() {
        R<Map<String, Object>> r = controller.denySession(
                "bogus-key", "any-token", "visitorD", "s1", "any-pending");
        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("deny of an unknown pendingId returns 404 (does not leak existence)")
    void denyUnknownPendingIsSafe() {
        controller.createSession(API_KEY, req("visitorE", "s1"));
        R<Map<String, Object>> r = controller.denySession(
                API_KEY, tokenFor("visitorE"), "visitorE", "s1", "wf-ghostthatdoesnotexist");
        // After the IDOR guard (review #415) an unknown / mismatched pendingId
        // is rejected with 404 rather than an idempotent 200 — this also avoids
        // leaking whether a given pendingId exists.
        assertThat(r.getCode()).isEqualTo(404);
    }

    // ---------------- IDOR guard (review #415) ----------------

    @Test
    @DisplayName("deny rejects a pendingId belonging to ANOTHER visitor's session → 404")
    void denyRejectsCrossVisitorPendingId() {
        // Victim owns session victimX and its pending approval.
        String pendingIdVictim = seedPending("victimX", "s1");
        // Attacker also has a valid token + own session (ownsConversation passes).
        controller.createSession(API_KEY, req("attackerY", "s1"));

        // Attacker tries to deny the victim's pendingId while authenticated as
        // the attacker against the attacker's own session. Before the IDOR fix
        // this would resolve the victim's approval — a cross-visitor privilege
        // escalation. Now the pendingId↔conversationId cross-check returns 404.
        R<Map<String, Object>> r = controller.denySession(
                API_KEY, tokenFor("attackerY"), "attackerY", "s1", pendingIdVictim);

        assertThat(r.getCode()).isEqualTo(404);

        // The victim's approval is untouched.
        String cidVictim = WebChatController.deriveConversationId(API_KEY, "victimX", "s1");
        var stillPending = approvalService.getPending(pendingIdVictim);
        assertThat(stillPending).as("victim's approval must not be resolved by attacker").isPresent();
        assertThat(stillPending.get().getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("deny rejects a pendingId that does not belong to the caller's session → 404")
    void denyRejectsMismatchedPendingId() {
        // Visitor owns the session, but passes a pendingId that doesn't match
        // the session's pending (e.g. a stale/guessed id).
        seedPending("visitorH", "s1");
        R<Map<String, Object>> r = controller.denySession(
                API_KEY, tokenFor("visitorH"), "visitorH", "s1", "wf-not-yours-12345");
        assertThat(r.getCode()).isEqualTo(404);
    }

    // ---------------- stop sweep (A4) ----------------

    @Test
    @DisplayName("stop denies pending approvals on the conversation (approval sweep)")
    void stopSweepsPendingApprovals() {
        seedPending("visitorF", "s1");
        String cid = WebChatController.deriveConversationId(API_KEY, "visitorF", "s1");

        // A pending approval exists before stop.
        assertThat(approvalService.findPendingByConversation(cid)).isNotNull();

        R<Map<String, Object>> r = controller.stopSession(
                API_KEY, tokenFor("visitorF"), "visitorF", "s1");

        assertThat(r.getCode()).isEqualTo(200);
        // After the sweep the approval is gone from the pending map.
        PendingApproval after = approvalService.findPendingByConversation(cid);
        assertThat(after == null || !"pending".equals(after.getStatus()))
                .as("stop should have denied the pending approval").isTrue();
    }

    @Test
    @DisplayName("stop with no pending approvals is unaffected (sweep is a no-op)")
    void stopNoPendingStillWorks() {
        controller.createSession(API_KEY, req("visitorG", "s1"));
        R<Map<String, Object>> r = controller.stopSession(
                API_KEY, tokenFor("visitorG"), "visitorG", "s1");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().get("stopped")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("disconnect before approval worker registration arms orphan cleanup")
    void disconnectBeforeApprovalWorkerRegistrationIsNotLost() throws Exception {
        String visitorId = "visitor-pre-register-disconnect";
        String sessionId = "s1";
        String pendingId = seedPending(visitorId, sessionId);
        String cid = WebChatController.deriveConversationId(API_KEY, visitorId, sessionId);
        Mockito.when(agentService.chatWithReplayStream(
                        eq(AGENT_ID), anyString(), eq(cid), anyString(), anyString(), any()))
                .thenReturn(Flux.never());

        WebChatDisconnectTestSupport.QueuedExecutorService queued =
                new WebChatDisconnectTestSupport.QueuedExecutorService();
        ExecutorService original = WebChatDisconnectTestSupport.swapExecutor(controller, queued);
        try {
            SseEmitter emitter = controller.approveSession(
                    API_KEY, tokenFor(visitorId), visitorId, sessionId, pendingId);
            WebChatDisconnectTestSupport.fireCompletion(emitter);
            queued.runNext();
        } finally {
            WebChatDisconnectTestSupport.swapExecutor(controller, original);
        }

        streamTracker.cleanupStaleRuns();

        assertThat(streamTracker.streamExistsOnThisNode(cid))
                .as("an approval disconnect observed before registration must arm orphan cleanup")
                .isFalse();
    }

    @Test
    @DisplayName("approval replay persists full usage metadata from _usage_final")
    void approvalReplayPersistsFullUsageMetadata() throws Exception {
        String visitorId = "visitor-usage-final";
        String sessionId = "s1";
        String pendingId = seedPending(visitorId, sessionId);
        String cid = WebChatController.deriveConversationId(API_KEY, visitorId, sessionId);
        Mockito.when(agentService.chatWithReplayStream(
                        eq(AGENT_ID), anyString(), eq(cid), anyString(), anyString(), any()))
                .thenReturn(Flux.just(
                        AgentService.StreamDelta.event("_usage_final", Map.of(
                                "promptTokens", 11,
                                "completionTokens", 7,
                                "cacheReadTokens", 3,
                                "cacheWriteTokens", 2,
                                "reasoningTokens", 5,
                                "runtimeModelName", "mock-replay-model",
                                "runtimeProviderId", "mock-provider")),
                        new AgentService.StreamDelta("approved reply", null)));

        WebChatDisconnectTestSupport.QueuedExecutorService queued =
                new WebChatDisconnectTestSupport.QueuedExecutorService();
        ExecutorService original = WebChatDisconnectTestSupport.swapExecutor(controller, queued);
        try {
            controller.approveSession(API_KEY, tokenFor(visitorId), visitorId, sessionId, pendingId);
            queued.runNext();
        } finally {
            WebChatDisconnectTestSupport.swapExecutor(controller, original);
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content, prompt_tokens, completion_tokens, cache_read_tokens, " +
                        "cache_write_tokens, reasoning_tokens, runtime_model, runtime_provider " +
                        "FROM mate_message WHERE conversation_id = ? AND role = 'assistant' " +
                        "ORDER BY create_time DESC LIMIT 1",
                cid);
        assertThat(row.get("CONTENT")).isEqualTo("approved reply");
        assertThat(((Number) row.get("PROMPT_TOKENS")).intValue()).isEqualTo(11);
        assertThat(((Number) row.get("COMPLETION_TOKENS")).intValue()).isEqualTo(7);
        assertThat(((Number) row.get("CACHE_READ_TOKENS")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("CACHE_WRITE_TOKENS")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("REASONING_TOKENS")).intValue()).isEqualTo(5);
        assertThat(row.get("RUNTIME_MODEL")).isEqualTo("mock-replay-model");
        assertThat(row.get("RUNTIME_PROVIDER")).isEqualTo("mock-provider");
    }
}
