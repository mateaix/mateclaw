package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.channel.webchat.WebChatController.WebChatSkillView;
import vip.mate.common.result.R;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for {@code GET /api/v1/channels/webchat/skills} — the
 * visitor-facing skill catalogue that downstream integrators use to build a
 * slash picker UI. Verifies the auth chain (API Key + visitorToken HMAC), the
 * agent workspace anti-escalation guard, and the bound+enabled filtering that
 * decides which skills surface to a visitor.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_skills_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatSkillListTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh";
    private static final long CHANNEL_ID = 9_300_001L;
    private static final long AGENT_ID = 9_300_011L;
    private static final long OTHER_WORKSPACE_AGENT_ID = 9_300_012L;
    private static final long SKILL_ENABLED_A = 9_300_101L;
    private static final long SKILL_ENABLED_B = 9_300_102L;
    private static final long SKILL_DISABLED = 9_300_103L;

    @Autowired private WebChatController controller;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Wipe + re-seed. Bindings + channel + agent + skills.
        jdbc.update("DELETE FROM mate_agent_skill WHERE agent_id = ?", AGENT_ID);
        jdbc.update("DELETE FROM mate_agent_skill WHERE agent_id = ?", OTHER_WORKSPACE_AGENT_ID);
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id IN (?, ?, ?)",
                AGENT_ID, OTHER_WORKSPACE_AGENT_ID, SKILL_ENABLED_A);
        for (long id : new long[]{SKILL_ENABLED_A, SKILL_ENABLED_B, SKILL_DISABLED}) {
            jdbc.update("DELETE FROM mate_skill WHERE id = ?", id);
        }

        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-skills-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        // Agent in a different workspace — must not be reachable through this channel.
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-skills-other-ws-agent', 'react', '', 10, TRUE, 999, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                OTHER_WORKSPACE_AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");

        // Three skills: two enabled (different slugs / display names), one disabled.
        // The bound+enabled filter should keep A + B and drop the disabled one.
        jdbc.update("MERGE INTO mate_skill (id, name, name_zh, name_en, description, icon, " +
                        "skill_type, enabled, builtin, workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'beta-skill', 'Beta', 'Beta (EN)', 'B desc', 'b-emoji', " +
                        "'custom', TRUE, FALSE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                SKILL_ENABLED_B);
        jdbc.update("MERGE INTO mate_skill (id, name, name_zh, name_en, description, icon, " +
                        "skill_type, enabled, builtin, workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'alpha-skill', 'Alpha', 'Alpha (EN)', 'A desc', 'a-emoji', " +
                        "'custom', TRUE, FALSE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                SKILL_ENABLED_A);
        jdbc.update("MERGE INTO mate_skill (id, name, name_zh, name_en, description, icon, " +
                        "skill_type, enabled, builtin, workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'disabled-skill', 'Disabled', 'Disabled (EN)', 'D desc', 'd-emoji', " +
                        "'custom', FALSE, FALSE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                SKILL_DISABLED);

        // Bind all three to AGENT_ID. Binding rows themselves are enabled; the
        // SkillEntity.enabled flag is what the controller filters on.
        for (long sid : new long[]{SKILL_ENABLED_A, SKILL_ENABLED_B, SKILL_DISABLED}) {
            jdbc.update("MERGE INTO mate_agent_skill (id, agent_id, skill_id, enabled, " +
                            "create_time, update_time, deleted) " +
                            "KEY(id) VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                    sid * 10, AGENT_ID, sid);
        }
    }

    private String tokenFor(String visitorId) {
        return WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, visitorId);
    }

    @Test
    @DisplayName("happy path: bound + enabled skills surface, sorted by slug; disabled ones dropped")
    void listReturnsBoundEnabledSorted() {
        R<List<WebChatSkillView>> r = controller.listSkills(
                API_KEY, tokenFor("v1"), null, "v1");

        assertThat(r.getCode()).isEqualTo(200);
        List<WebChatSkillView> data = r.getData();
        assertThat(data).hasSize(2);
        // Sorted by slug: alpha-skill, beta-skill.
        assertThat(data.get(0).getName()).isEqualTo("alpha-skill");
        assertThat(data.get(0).getNameZh()).isEqualTo("Alpha");
        assertThat(data.get(0).getDescription()).isEqualTo("A desc");
        assertThat(data.get(1).getName()).isEqualTo("beta-skill");
        // The disabled one must NOT surface.
        assertThat(data).noneMatch(s -> "disabled-skill".equals(s.getName()));
    }

    @Test
    @DisplayName("explicit agentId matching channel workspace works")
    void explicitAgentIdSameWorkspace() {
        R<List<WebChatSkillView>> r = controller.listSkills(
                API_KEY, tokenFor("v2"), AGENT_ID, "v2");

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).hasSize(2);
    }

    @Test
    @DisplayName("explicit agentId in a different workspace → 403 (anti-escalation)")
    void explicitAgentIdDifferentWorkspace() {
        R<List<WebChatSkillView>> r = controller.listSkills(
                API_KEY, tokenFor("v3"), OTHER_WORKSPACE_AGENT_ID, "v3");

        assertThat(r.getCode()).isEqualTo(403);
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("invalid API Key → 401")
    void invalidApiKey() {
        R<List<WebChatSkillView>> r = controller.listSkills(
                "garbagekeyxyz12", tokenFor("v4"), null, "v4");

        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("missing/invalid visitor token → 401")
    void invalidVisitorToken() {
        R<List<WebChatSkillView>> r = controller.listSkills(
                API_KEY, "not-a-valid-token", null, "v5");

        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("agent with no explicit bindings returns empty (fall-through to natural language)")
    void noBindingsReturnsEmpty() {
        // Agent with no rows in mate_agent_skill. Use a fresh agent ID that
        // doesn't share the seeded bindings.
        long lonelyAgent = 9_300_099L;
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-skills-lonely', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                lonelyAgent);
        // Repoint the channel to this agent so the default-agent path resolves
        // it without needing the explicit agentId parameter.
        jdbc.update("UPDATE mate_channel SET agent_id = ? WHERE id = ?", lonelyAgent, CHANNEL_ID);

        R<List<WebChatSkillView>> r = controller.listSkills(
                API_KEY, tokenFor("v6"), null, "v6");

        assertThat(r.getCode()).isEqualTo(200);
        // null bound IDs → controller returns empty rather than surfacing every
        // enabled skill in the workspace (visitor UI should be agent-scoped).
        assertThat(r.getData()).isEmpty();
    }
}
