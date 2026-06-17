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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies every visitor-side write lands in {@code mate_audit_event} with
 * the right actor ({@code "webchat:<channelId>:<visitorId>"}) and action
 * prefix ({@code webchat.*}).
 *
 * <p>{@code AuditEventService.recordAs} writes asynchronously — the test
 * polls briefly for the row to appear rather than asserting synchronously.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_audit_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatAuditTrailTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh";
    private static final long CHANNEL_ID = 9_147_701L;
    private static final long AGENT_ID = 9_147_7011L;

    @Autowired private WebChatController controller;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id = ?", AGENT_ID);
        jdbc.update("DELETE FROM mate_audit_event WHERE resource_id = ?", String.valueOf(CHANNEL_ID));
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-audit-agent', 'react', '', 10, TRUE, 1, " +
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

    private long awaitAuditCount(String action, long expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mate_audit_event WHERE action = ?",
                    Integer.class, action);
            if (c != null && c >= expected) return c;
            Thread.sleep(50);
        }
        return -1;
    }

    @Test
    @DisplayName("createSession lands an audit row with the right actor + action")
    void createSessionIsAudited() throws InterruptedException {
        R<?> r = controller.createSession(API_KEY, req("vAudit", "s1"));
        assertThat(r.getCode()).isEqualTo(200);

        long count = awaitAuditCount("webchat.create-session", 1, 3_000);
        assertThat(count).isGreaterThan(0);

        String actor = jdbc.queryForObject(
                "SELECT username FROM mate_audit_event WHERE action = 'webchat.create-session' ORDER BY create_time DESC LIMIT 1",
                String.class);
        assertThat(actor).isEqualTo("webchat:" + CHANNEL_ID + ":vAudit");
    }

    @Test
    @DisplayName("rename + pin + archive + stop each leave an audit row")
    void stateMutationsAreAudited() throws InterruptedException {
        controller.createSession(API_KEY, req("vState", "s1"));
        String token = tokenFor("vState");

        controller.renameSession(API_KEY, token, "vState", "s1", Map.of("title", "Renamed"));
        controller.pinSession(API_KEY, token, "vState", "s1", Map.of("pinned", true));
        controller.archiveSession(API_KEY, token, "vState", "s1", Map.of("archived", true));
        controller.stopSession(API_KEY, token, "vState", "s1");

        assertThat(awaitAuditCount("webchat.rename-session", 1, 3_000)).isGreaterThan(0);
        assertThat(awaitAuditCount("webchat.pin-session", 1, 3_000)).isGreaterThan(0);
        assertThat(awaitAuditCount("webchat.archive-session", 1, 3_000)).isGreaterThan(0);
        assertThat(awaitAuditCount("webchat.stop-session", 1, 3_000)).isGreaterThan(0);
    }
}
