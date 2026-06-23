package vip.mate.channel.webchat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.channel.webchat.WebChatController.WebChatWikiPageView;
import vip.mate.common.result.R;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for {@code GET /api/v1/channels/webchat/wiki/pages} —
 * the visitor-facing wiki page catalogue that downstream integrators use to
 * build a {@code [[slug]]} picker UI. Mirrors {@link WebChatSkillListTest}:
 * verifies the auth chain (API Key + visitorToken HMAC), the agent workspace
 * anti-escalation guard, the bound-KB scope, the {@code synthesis} exclusion,
 * and the &gt;100-page cap that forces a keyword filter.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:webchat_wiki_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=webchat-it-secret-0123456789"
})
class WebChatWikiPageListTest {

    private static final String SECRET = "webchat-it-secret-0123456789";
    private static final String API_KEY = "testkey1abcdefgh";
    private static final long CHANNEL_ID = 9_400_001L;
    private static final long AGENT_ID = 9_400_011L;
    private static final long OTHER_WORKSPACE_AGENT_ID = 9_400_012L;
    private static final long KB_ID = 9_400_101L;
    private static final long OTHER_KB_ID = 9_400_102L;

    @Autowired private WebChatController controller;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Wipe bindings + pages + KBs + channel + agents in dependency-safe order.
        jdbc.update("DELETE FROM mate_agent_wiki_kb WHERE agent_id IN (?, ?, ?)",
                AGENT_ID, OTHER_WORKSPACE_AGENT_ID, 9_400_099L);
        jdbc.update("DELETE FROM mate_wiki_page WHERE kb_id IN (?, ?)", KB_ID, OTHER_KB_ID);
        jdbc.update("DELETE FROM mate_wiki_knowledge_base WHERE id IN (?, ?)", KB_ID, OTHER_KB_ID);
        jdbc.update("DELETE FROM mate_channel WHERE id = ?", CHANNEL_ID);
        jdbc.update("DELETE FROM mate_agent WHERE id IN (?, ?, ?)",
                AGENT_ID, OTHER_WORKSPACE_AGENT_ID, 9_400_099L);

        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-wiki-agent', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                AGENT_ID);
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-wiki-other-ws-agent', 'react', '', 10, TRUE, 999, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                OTHER_WORKSPACE_AGENT_ID);
        jdbc.update("INSERT INTO mate_channel (id, name, channel_type, agent_id, config_json, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "VALUES (?, 'wc', 'webchat', ?, ?, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                CHANNEL_ID, AGENT_ID, "{\"api_key\":\"" + API_KEY + "\"}");

        // Two KBs in workspace 1 (the channel's workspace). Bind the agent to
        // KB_ID only, so OTHER_KB_ID stays out of scope unless the agent's
        // binding set is cleared.
        jdbc.update("MERGE INTO mate_wiki_knowledge_base (id, name, description, status, " +
                        "page_count, raw_count, workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'Main KB', 'main', 'active', 0, 0, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                KB_ID);
        jdbc.update("MERGE INTO mate_wiki_knowledge_base (id, name, description, status, " +
                        "page_count, raw_count, workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'Other KB', 'other', 'active', 0, 0, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                OTHER_KB_ID);

        // Bind AGENT_ID to KB_ID only. Single enabled row → effective scope = {KB_ID}.
        jdbc.update("MERGE INTO mate_agent_wiki_kb (id, agent_id, kb_id, enabled, " +
                        "create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                9_400_201L, AGENT_ID, KB_ID);

        // Three pages in KB_ID: entity / concept / synthesis. The synthesis
        // one must be filtered out by the picker; the other two surface sorted
        // by slug (beta < zeta by slug asc... actually we use 'alpha' and
        // 'beta' to make the sort obvious).
        insertPage(9_400_301L, KB_ID, "alpha-page", "Alpha Page", "entity");
        insertPage(9_400_302L, KB_ID, "beta-page", "Beta Page", "concept");
        insertPage(9_400_303L, KB_ID, "hidden-synthesis", "Synthesis (hidden)", "synthesis");

        // One page in OTHER_KB_ID — must NOT surface (agent bound to KB_ID only).
        insertPage(9_400_304L, OTHER_KB_ID, "other-kb-page", "Other KB Page", "entity");
    }

    private void insertPage(long id, long kbId, String slug, String title, String pageType) {
        jdbc.update("MERGE INTO mate_wiki_page (id, kb_id, slug, title, content, summary, " +
                        "page_type, version, last_updated_by, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, ?, ?, '', ?, ?, 1, 'test', " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, kbId, slug, title, title + " summary", pageType);
    }

    private String tokenFor(String visitorId) {
        return WebChatController.computeVisitorToken(SECRET, CHANNEL_ID, visitorId);
    }

    @Test
    @DisplayName("happy path: bound-KB pages surface sorted by slug; synthesis filtered out; other-KB excluded")
    void listReturnsBoundKbPagesSortedExcludingSynthesis() {
        R<List<WebChatWikiPageView>> r = controller.listWikiPages(
                API_KEY, tokenFor("v1"), null, "v1", null);

        assertThat(r.getCode()).isEqualTo(200);
        List<WebChatWikiPageView> data = r.getData();
        assertThat(data).hasSize(2);
        assertThat(data.get(0).getSlug()).isEqualTo("alpha-page");
        assertThat(data.get(0).getTitle()).isEqualTo("Alpha Page");
        assertThat(data.get(0).getKbId()).isEqualTo(KB_ID);
        assertThat(data.get(0).getKbName()).isEqualTo("Main KB");
        assertThat(data.get(0).getPageType()).isEqualTo("entity");
        assertThat(data.get(1).getSlug()).isEqualTo("beta-page");
        // synthesis must NOT surface
        assertThat(data).noneMatch(p -> "synthesis".equals(p.getPageType()));
        // other-KB page must NOT surface (out of scope)
        assertThat(data).noneMatch(p -> "other-kb-page".equals(p.getSlug()));
    }

    @Test
    @DisplayName("keyword filters by slug OR title (case-insensitive LIKE)")
    void keywordFilterMatchesSlugOrTitle() {
        R<List<WebChatWikiPageView>> bySlug = controller.listWikiPages(
                API_KEY, tokenFor("v2"), null, "v2", "alpha");
        assertThat(bySlug.getCode()).isEqualTo(200);
        assertThat(bySlug.getData()).hasSize(1);
        assertThat(bySlug.getData().get(0).getSlug()).isEqualTo("alpha-page");

        R<List<WebChatWikiPageView>> byTitle = controller.listWikiPages(
                API_KEY, tokenFor("v3"), null, "v3", "Beta Page");
        assertThat(byTitle.getCode()).isEqualTo(200);
        assertThat(byTitle.getData()).hasSize(1);
        assertThat(byTitle.getData().get(0).getSlug()).isEqualTo("beta-page");

        R<List<WebChatWikiPageView>> noMatch = controller.listWikiPages(
                API_KEY, tokenFor("v4"), null, "v4", "nomatch");
        assertThat(noMatch.getCode()).isEqualTo(200);
        assertThat(noMatch.getData()).isEmpty();
    }

    @Test
    @DisplayName("explicit agentId matching channel workspace works")
    void explicitAgentIdSameWorkspace() {
        R<List<WebChatWikiPageView>> r = controller.listWikiPages(
                API_KEY, tokenFor("v5"), AGENT_ID, "v5", null);

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).hasSize(2);
    }

    @Test
    @DisplayName("explicit agentId in a different workspace → 403 (anti-escalation)")
    void explicitAgentIdDifferentWorkspace() {
        R<List<WebChatWikiPageView>> r = controller.listWikiPages(
                API_KEY, tokenFor("v6"), OTHER_WORKSPACE_AGENT_ID, "v6", null);

        assertThat(r.getCode()).isEqualTo(403);
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("invalid API Key → 401")
    void invalidApiKey() {
        R<List<WebChatWikiPageView>> r = controller.listWikiPages(
                "garbagekeyxyz12", tokenFor("v7"), null, "v7", null);

        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("missing/invalid visitor token → 401")
    void invalidVisitorToken() {
        R<List<WebChatWikiPageView>> r = controller.listWikiPages(
                API_KEY, "not-a-valid-token", null, "v8", null);

        assertThat(r.getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("no KB bindings → fall back to workspace-wide KB set (legacy behavior)")
    void noKbBindingsFallsBackToWorkspaceWide() {
        // Use a fresh agent with no mate_agent_wiki_kb rows. The channel is
        // repointed to it so the default-agent path resolves it.
        long lonelyAgent = 9_400_099L;
        jdbc.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, 'wc-wiki-lonely', 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                lonelyAgent);
        jdbc.update("UPDATE mate_channel SET agent_id = ? WHERE id = ?", lonelyAgent, CHANNEL_ID);

        R<List<WebChatWikiPageView>> r = controller.listWikiPages(
                API_KEY, tokenFor("v9"), null, "v9", null);

        assertThat(r.getCode()).isEqualTo(200);
        // Both workspace KBs visible: alpha/beta from KB_ID + other-kb-page
        // from OTHER_KB_ID. Synthesis still filtered.
        assertThat(r.getData()).hasSize(3);
        assertThat(r.getData()).extracting(WebChatWikiPageView::getSlug)
                .containsExactlyInAnyOrder("alpha-page", "beta-page", "other-kb-page");
    }

    @Test
    @DisplayName(">100 pages without keyword → 422 (force narrow with keyword)")
    void capWithoutKeywordReturns422() {
        // Seed 101 synthetic pages (slug = cap-001 ... cap-101) into KB_ID.
        // These are extra to the 3 set up in @BeforeEach; synthesis-type rows
        // still get filtered, so use 'entity' to make sure they all count.
        for (int i = 1; i <= 101; i++) {
            insertPage(9_401_000L + i, KB_ID,
                    String.format("cap-%03d", i),
                    "Cap Page " + i,
                    "entity");
        }

        R<List<WebChatWikiPageView>> noKeyword = controller.listWikiPages(
                API_KEY, tokenFor("v10"), null, "v10", null);
        assertThat(noKeyword.getCode()).isEqualTo(422);
        assertThat(noKeyword.getData()).isNull();

        // With a keyword the cap is bypassed — caller gets the filtered subset
        // (here: 3 of the 101 seeded rows match "cap-001" / "cap-010" / "cap-100").
        R<List<WebChatWikiPageView>> withKeyword = controller.listWikiPages(
                API_KEY, tokenFor("v11"), null, "v11", "cap-001");
        assertThat(withKeyword.getCode()).isEqualTo(200);
        assertThat(withKeyword.getData()).isNotEmpty();
    }
}
