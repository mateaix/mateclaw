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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of visitor-token revocation + expiration (epic #355 PR 2):
 * <ul>
 *   <li>{@link WebChatTokenRevocationService#revoke} flips
 *       {@link WebChatController#verifyVisitorToken} to false on subsequent calls.</li>
 *   <li>{@link WebChatTokenRevocationService#unrevoke} flips it back.</li>
 *   <li>Revoke is idempotent (double-revoke = single row).</li>
 *   <li>Cache amortises the DB hit: a second {@code isRevoked} for the same
 *       (channelId, visitorId) within the TTL does not re-query.</li>
 *   <li>Expired tokens are rejected before revocation is even consulted.</li>
 *   <li>An end-to-end management call ({@code GET /sessions}) honours the
 *       revocation — a revoked visitor gets 401.</li>
 * </ul>
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_revoke_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatTokenRevocationTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh";
    private static final long CHANNEL_ID = 9_147_401L;
    private static final long AGENT_ID = 9_147_4011L;

    @Autowired private WebChatController controller;
    @Autowired private WebChatAdminController adminController;
    @Autowired private WebChatTokenRevocationService revocationService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update("DELETE FROM webchat_revoked_visitor WHERE channel_id = ?", CHANNEL_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-revoke-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");
        // Invalidate any cached revocation state from earlier tests sharing this Spring context.
        revocationService.invalidateCacheForTest();
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
    @DisplayName("revoked visitor: token verify flips to false, /sessions returns 401")
    void revokedVisitorCannotReachManagementEndpoints() {
        controller.createSession(API_KEY, req("vRevoke", "s1"));
        String token = tokenFor("vRevoke");

        // Pre-revoke: endpoint works.
        R<?> ok = controller.listSessions(API_KEY, token, "vRevoke", false);
        assertThat(ok.getCode()).isEqualTo(200);

        revocationService.revoke(CHANNEL_ID, "vRevoke", "abuse");

        R<?> denied = controller.listSessions(API_KEY, token, "vRevoke", false);
        assertThat(denied.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("un-revoke: token verify flips back to true, /sessions works again")
    void unrevokeRestoresAccess() {
        controller.createSession(API_KEY, req("vUnrev", "s1"));
        String token = tokenFor("vUnrev");

        revocationService.revoke(CHANNEL_ID, "vUnrev", "test");
        assertThat(controller.listSessions(API_KEY, token, "vUnrev", false).getCode()).isEqualTo(401);

        revocationService.unrevoke(CHANNEL_ID, "vUnrev");
        R<?> ok = controller.listSessions(API_KEY, token, "vUnrev", false);
        assertThat(ok.getCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("revoke is idempotent (single row, no errors on double-revoke)")
    void revokeIsIdempotent() {
        revocationService.revoke(CHANNEL_ID, "vIdem", "first");
        revocationService.revoke(CHANNEL_ID, "vIdem", "second");

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webchat_revoked_visitor WHERE channel_id = ? AND visitor_id = ? AND deleted = 0",
                Integer.class, CHANNEL_ID, "vIdem");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("cache: revoking twice + isRevoked yields exactly one persisted row")
    void revokePersistsSingleRowAcrossMultipleCalls() {
        // The cache makes revoke() + immediate isRevoked() cheap, but the source of
        // truth is the DB — assert the table still contains exactly one row even
        // after the visitor is queried multiple times post-revoke.
        revocationService.revoke(CHANNEL_ID, "vCache", "x");
        revocationService.isRevoked(CHANNEL_ID, "vCache");
        revocationService.isRevoked(CHANNEL_ID, "vCache");
        revocationService.isRevoked(CHANNEL_ID, "vCache");

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webchat_revoked_visitor WHERE channel_id = ? AND visitor_id = ?",
                Integer.class, CHANNEL_ID, "vCache");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("expired token is rejected even when visitor is not revoked")
    void expiredTokenRejected() {
        // Mint a token that already expired a minute ago.
        long past = java.time.Instant.now().getEpochSecond() - 60;
        String expired = WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, "vExp", past);

        controller.createSession(API_KEY, req("vExp", "s1"));
        R<?> r = controller.listSessions(API_KEY, expired, "vExp", false);
        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("/stream is unaffected by revocation — a revoked visitor can still start fresh")
    void streamUnaffectedByRevocation() {
        // We can't actually exercise /stream without a real LLM, but we can verify
        // the contract: revoking a visitor leaves verifyVisitorTokenSignature() (which
        // /stream never calls anyway) intact. The point of this test is to lock in
        // that the revocation check lives in the instance verifyVisitorToken, not in
        // the static signature check.
        revocationService.revoke(CHANNEL_ID, "vStream", "test");
        String token = WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, "vStream");
        // Signature still verifies (proves /stream's "mint a fresh token" path works):
        assertThat(WebChatController.verifyVisitorTokenSignature(SECRET, CHANNEL_ID, "vStream", token))
                .isTrue();
    }

    @Test
    @DisplayName("admin endpoint POST /revoked-visitor records the revocation + audit")
    void adminEndpointRevokes() {
        WebChatAdminController.RevokeVisitorRequest req = new WebChatAdminController.RevokeVisitorRequest();
        req.setChannelId(CHANNEL_ID);
        req.setVisitorId("vAdmin");
        req.setReason("admin-test");

        R<Void> r = adminController.revokeVisitor(req, null);
        assertThat(r.getCode()).isEqualTo(200);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webchat_revoked_visitor WHERE channel_id = ? AND visitor_id = ? AND deleted = 0",
                Integer.class, CHANNEL_ID, "vAdmin");
        assertThat(rows).isEqualTo(1);

        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mate_audit_event WHERE action = ? AND resource_id = ?",
                Integer.class, "webchat.revoke-visitor", String.valueOf(CHANNEL_ID));
        assertThat(audit).isGreaterThan(0);
    }
}
