package vip.mate.wiki.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Routing-level coverage for the {@code kbName} / {@code kbId} resolver
 * shared by every wiki tool (exercised through {@code wiki_list_pages} and
 * {@code wiki_list_kbs}).
 *
 * <p>The behaviour these tests pin in place is the fix for the upstream
 * "single-KB collapse" bug: when an agent reaches more than one knowledge
 * base, every wiki tool used to silently operate on whichever KB the
 * primary-fallback picked, with no way for the LLM to target a different
 * one. The fix shipped in three layers:
 * <ol>
 *   <li>blank {@code kbName} + blank {@code kbId} → still routes to the
 *       primary KB so the single-KB UX stays zero-config;</li>
 *   <li>named {@code kbName} that matches exactly one visible KB → routes
 *       to that KB, not the primary;</li>
 *   <li>named {@code kbName} that doesn't match any visible KB → fail-closed
 *       error naming the bad pick and pointing at {@code wiki_list_kbs};</li>
 *   <li>named {@code kbName} that matches MORE than one visible KB (the
 *       schema has no unique constraint on KB name) → fail-closed error
 *       listing every candidate's {@code kbId} so the LLM can retry via
 *       {@code kbId} instead;</li>
 *   <li>{@code kbId} provided → uses {@link WikiKnowledgeBaseService#findVisibleById}
 *       (visibility gate enforced; out-of-set ids fail closed);</li>
 *   <li>{@code wiki_list_kbs} surfaces every visible KB with {@code kbId}
 *       rendered as a String (workspace-wide Snowflake-precision rule),
 *       plus {@code isPrimary} / {@code boundToAgent} flags.</li>
 * </ol>
 */
class WikiToolKbNameRoutingTest {

    private static final Long AGENT = 7L;
    private static final long PRIMARY_KB = 100L;
    private static final long OTHER_KB = 200L;
    private static final long DUP_BOUND_KB = 300L;
    private static final long DUP_SHARED_KB = 400L;

    private final WikiPageService pageService = mock(WikiPageService.class);
    private final WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
    private final WikiRawMaterialService rawService = mock(WikiRawMaterialService.class);
    private final HybridRetriever hybridRetriever = mock(HybridRetriever.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Allow-all permission service (no rows configured) — this test exercises
    // KB-name routing, not permissions.
    private final vip.mate.wiki.service.WikiPageTypePermissionService permissionService =
            new vip.mate.wiki.service.WikiPageTypePermissionService(
                    mock(vip.mate.wiki.repository.WikiAgentPageTypePermissionMapper.class), kbService, objectMapper);

    private final WikiTool tool = new WikiTool(pageService, kbService, rawService,
            hybridRetriever, objectMapper, permissionService);

    private static WikiKnowledgeBaseEntity kb(long id, String name, Long agentId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setAgentId(agentId);
        entity.setPageCount(0);
        return entity;
    }

    private static WikiPageEntity page(String slug, String title) {
        WikiPageEntity entity = new WikiPageEntity();
        entity.setSlug(slug);
        entity.setTitle(title);
        entity.setPageType("user");
        return entity;
    }

    /** Mock listSummaries to return KB-specific page slugs so the test can
     *  prove which KB the tool actually queried. */
    private void wirePages() {
        when(pageService.listSummaries(eq(PRIMARY_KB))).thenReturn(List.of(
                page("primary-only-slug", "Primary KB Page")));
        when(pageService.listSummaries(eq(OTHER_KB))).thenReturn(List.of(
                page("other-only-slug", "Other KB Page")));
        when(pageService.listSummaries(eq(DUP_BOUND_KB))).thenReturn(List.of(
                page("dup-bound-slug", "Bound Docs Page")));
        when(pageService.listSummaries(eq(DUP_SHARED_KB))).thenReturn(List.of(
                page("dup-shared-slug", "Shared Docs Page")));
    }

    // ==================== wiki_list_pages routing — happy paths ====================

    @Test
    @DisplayName("blank kbName + blank kbId routes to the primary KB")
    void blankRoutesToPrimary() {
        wirePages();
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_pages(AGENT, null, null, null);
        JSONObject obj = JSONUtil.parseObj(json);

        JSONArray pages = obj.getJSONArray("pages");
        assertThat(pages).hasSize(1);
        assertThat(pages.getJSONObject(0).getStr("slug")).isEqualTo("primary-only-slug");
    }

    @Test
    @DisplayName("known kbName routes to that KB instead of the primary")
    void namedKbNameRoutesToNamedKb() {
        wirePages();
        // Primary fallback still wired so the test would fail loudly if the
        // tool silently used it despite a non-blank kbName.
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));
        when(kbService.findAllByName(AGENT, "Other")).thenReturn(List.of(kb(OTHER_KB, "Other", null)));

        String json = tool.wiki_list_pages(AGENT, null, "Other", null);
        JSONObject obj = JSONUtil.parseObj(json);

        JSONArray pages = obj.getJSONArray("pages");
        assertThat(pages).hasSize(1);
        assertThat(pages.getJSONObject(0).getStr("slug")).isEqualTo("other-only-slug");
    }

    @Test
    @DisplayName("kbId routes through the visibility gate to that KB")
    void kbIdRoutesViaVisibilityGate() {
        wirePages();
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));
        when(kbService.findVisibleById(AGENT, OTHER_KB)).thenReturn(kb(OTHER_KB, "Other", null));

        String json = tool.wiki_list_pages(AGENT, null, null, OTHER_KB);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getJSONArray("pages").getJSONObject(0).getStr("slug"))
                .isEqualTo("other-only-slug");
    }

    @Test
    @DisplayName("kbId wins when both kbName and kbId are supplied")
    void kbIdWinsOverKbName() {
        wirePages();
        when(kbService.findVisibleById(AGENT, OTHER_KB)).thenReturn(kb(OTHER_KB, "Other", null));
        // Deliberately do NOT stub findAllByName — if the tool consulted
        // kbName at all (or fell back to primary), the call would NPE.

        String json = tool.wiki_list_pages(AGENT, null, "anything", OTHER_KB);
        JSONObject obj = JSONUtil.parseObj(json);
        assertThat(obj.getJSONArray("pages").getJSONObject(0).getStr("slug"))
                .isEqualTo("other-only-slug");
    }

    // ==================== fail-closed paths ====================

    @Test
    @DisplayName("unknown kbName fails closed and names the bad pick")
    void unknownKbNameFailsClosed() {
        when(kbService.findAllByName(AGENT, "Bogus")).thenReturn(List.of());
        // Primary still mockable; the routing must NOT silently fall through.
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_pages(AGENT, null, "Bogus", null);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getStr("error"))
                .as("error message should name the bad pick and point at wiki_list_kbs")
                .contains("Bogus")
                .contains("wiki_list_kbs");
    }

    @Test
    @DisplayName("ambiguous kbName fails closed and surfaces every candidate kbId")
    void ambiguousKbNameFailsClosed() {
        when(kbService.findAllByName(AGENT, "Docs")).thenReturn(List.of(
                kb(DUP_BOUND_KB, "Docs", AGENT),
                kb(DUP_SHARED_KB, "Docs", null)));
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_pages(AGENT, null, "Docs", null);
        JSONObject obj = JSONUtil.parseObj(json);

        // Error must be ambiguity-flavoured so the LLM knows to retry with kbId.
        assertThat(obj.getStr("error"))
                .contains("Ambiguous")
                .contains("Docs")
                .contains("kbId");

        // Candidates list must carry both rows with stringified kbId.
        JSONArray candidates = obj.getJSONArray("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.getJSONObject(0).getStr("kbId"))
                .isEqualTo(String.valueOf(DUP_BOUND_KB));
        assertThat(candidates.getJSONObject(0).getBool("boundToAgent")).isTrue();
        assertThat(candidates.getJSONObject(1).getStr("kbId"))
                .isEqualTo(String.valueOf(DUP_SHARED_KB));
        assertThat(candidates.getJSONObject(1).getBool("boundToAgent")).isFalse();
    }

    @Test
    @DisplayName("kbId == 0 is treated as absent (LLM default for unused numeric optionals)")
    void kbIdZeroTreatedAsAbsent() {
        wirePages();
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));
        // findVisibleById must NOT be consulted when kbId=0 — that path
        // would return null and surface a spurious "kbId=0 not visible" error,
        // which is exactly the production regression this test prevents.

        String json = tool.wiki_list_pages(AGENT, null, null, 0L);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getStr("error"))
                .as("kbId=0 must fall through to primary, NOT raise a not-visible error")
                .isNull();
        assertThat(obj.getJSONArray("pages").getJSONObject(0).getStr("slug"))
                .isEqualTo("primary-only-slug");
    }

    @Test
    @DisplayName("kbId outside the agent's visibility set fails closed")
    void kbIdOutOfVisibilityFailsClosed() {
        // Visibility gate returns null for an unrelated id.
        when(kbService.findVisibleById(AGENT, 99999L)).thenReturn(null);
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_pages(AGENT, null, null, 99999L);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getStr("error"))
                .contains("99999")
                .contains("not visible")
                .contains("wiki_list_kbs");
    }

    @Test
    @DisplayName("no resolvable KB at all emits the legacy no-KB error")
    void noResolvableKbReturnsLegacyError() {
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(null);

        String json = tool.wiki_list_pages(AGENT, null, null, null);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getStr("error")).contains("No wiki knowledge base found");
    }

    // ==================== wiki_list_kbs ====================

    @Test
    @DisplayName("wiki_list_kbs enumerates every visible KB with stringified kbId + flags")
    void wikiListKbsEnumeratesAll() {
        when(kbService.listByAgentId(AGENT)).thenReturn(List.of(
                kb(OTHER_KB, "Other", null),
                kb(PRIMARY_KB, "Primary", AGENT)));
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_kbs(AGENT);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getInt("kbCount")).isEqualTo(2);
        assertThat(obj.getStr("primary")).isEqualTo("Primary");

        JSONArray kbs = obj.getJSONArray("kbs");
        assertThat(kbs).hasSize(2);

        JSONObject other = kbs.getJSONObject(0);
        assertThat(other.getStr("kbId"))
                .as("kbId must be a String to preserve Snowflake precision")
                .isEqualTo(String.valueOf(OTHER_KB));
        assertThat(other.getStr("name")).isEqualTo("Other");
        assertThat(other.getBool("isPrimary")).isFalse();
        assertThat(other.getBool("boundToAgent")).isFalse();

        JSONObject primary = kbs.getJSONObject(1);
        assertThat(primary.getStr("kbId")).isEqualTo(String.valueOf(PRIMARY_KB));
        assertThat(primary.getStr("name")).isEqualTo("Primary");
        assertThat(primary.getBool("isPrimary")).isTrue();
        assertThat(primary.getBool("boundToAgent")).isTrue();
    }
}
