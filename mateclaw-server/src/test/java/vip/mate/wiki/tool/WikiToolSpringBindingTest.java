package vip.mate.wiki.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring AI binding round-trip for the wiki tools' KB-routing contract.
 *
 * <p>The unit tests in {@link WikiToolKbNameRoutingTest} cover the Java-level
 * routing logic, but the LLM never calls those methods directly — it serializes
 * a JSON tool call which Spring AI's {@link ToolCallbacks#from} layer deserializes
 * back into method arguments. Two coercion hops in that pipeline are easy to
 * break unnoticed:
 *
 * <ol>
 *   <li>{@code wiki_list_kbs} returns {@code "kbId": "<digits>"} as a JSON
 *       string (workspace-wide Snowflake-precision rule). When the LLM hands
 *       that exact string back as {@code kbId} on a follow-up call, the Java
 *       method declares {@code Long kbId} — so the framework must coerce
 *       string → Long without precision loss.</li>
 *   <li>OpenAI-style chat models frequently populate "unused numeric
 *       optionals" with {@code 0}. The routing layer treats {@code kbId > 0}
 *       as the only "supplied" sentinel; any binding change that lets a real
 *       19-digit id collapse to 0 (e.g. silent float coercion) would also
 *       break the round-trip even though the unit tests still pass.</li>
 * </ol>
 *
 * These two tests pin the contract end-to-end.
 */
class WikiToolSpringBindingTest {

    private static final Long AGENT = 7L;
    private static final long PRIMARY_KB = 100L;
    // Real-shape Snowflake id — 19 digits, beyond JS Number.MAX_SAFE_INTEGER.
    // Verifies the precision-safe round-trip the workspace rule mandates.
    private static final long SNOWFLAKE_KB = 2054907618529591298L;

    private final WikiPageService pageService = mock(WikiPageService.class);
    private final WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
    private final WikiRawMaterialService rawService = mock(WikiRawMaterialService.class);
    private final HybridRetriever hybridRetriever = mock(HybridRetriever.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Allow-all permission service (no rows configured) — this test exercises
    // tool binding, not permissions.
    private final vip.mate.wiki.service.WikiPageTypePermissionService permissionService =
            new vip.mate.wiki.service.WikiPageTypePermissionService(
                    mock(vip.mate.wiki.repository.WikiAgentPageTypePermissionMapper.class), kbService, objectMapper);

    private final WikiTool tool = new WikiTool(pageService, kbService, rawService,
            hybridRetriever, objectMapper, permissionService);

    private ToolCallback callbackFor(String functionName) {
        return Arrays.stream(ToolCallbacks.from(tool))
                .filter(cb -> functionName.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ToolCallback for " + functionName));
    }

    private static WikiKnowledgeBaseEntity kb(long id, String name, Long agentId) {
        WikiKnowledgeBaseEntity e = new WikiKnowledgeBaseEntity();
        e.setId(id);
        e.setName(name);
        e.setAgentId(agentId);
        e.setPageCount(0);
        return e;
    }

    private static WikiPageEntity page(String slug, String title) {
        WikiPageEntity p = new WikiPageEntity();
        p.setSlug(slug);
        p.setTitle(title);
        p.setPageType("user");
        return p;
    }

    @Test
    @DisplayName("wiki_list_kbs emits kbId as a JSON STRING and round-trips back through Long kbId")
    void kbIdRoundTripsAsStringWithoutPrecisionLoss() {
        // Arrange: one agent-bound KB whose id is a real-shape 19-digit
        // Snowflake. wiki_list_kbs must surface this as a string so a JS
        // hop never truncates it; the follow-up tool call then has to
        // accept that same string and coerce it back to a Long without loss.
        WikiKnowledgeBaseEntity snowflakeKb = kb(SNOWFLAKE_KB, "Big Data KB", AGENT);
        when(kbService.listByAgentId(AGENT)).thenReturn(List.of(snowflakeKb));
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(snowflakeKb);
        when(kbService.findVisibleById(AGENT, SNOWFLAKE_KB)).thenReturn(snowflakeKb);
        when(pageService.listSummaries(eq(SNOWFLAKE_KB))).thenReturn(List.of(
                page("only-page", "Only Page")));

        // Step 1: wiki_list_kbs through the real Spring AI ToolCallback binding.
        ToolCallback listKbs = callbackFor("wiki_list_kbs");
        String listJson = listKbs.call("{\"agentId\":" + AGENT + "}");
        JSONObject listObj = JSONUtil.parseObj(listJson);

        JSONArray kbs = listObj.getJSONArray("kbs");
        assertThat(kbs).hasSize(1);
        JSONObject row = kbs.getJSONObject(0);
        // kbId MUST be a JSON string. Reading it back via getStr should equal
        // the exact 19-digit id; reading via getLong should also work (Hutool
        // parses string-or-number). The two checks combined catch a regression
        // that emits the id as a JSON number (which the LLM/JS hop would round).
        String advertisedKbId = row.getStr("kbId");
        assertThat(advertisedKbId)
                .as("wiki_list_kbs MUST publish kbId as a string")
                .isEqualTo(String.valueOf(SNOWFLAKE_KB));
        assertThat(row.get("kbId"))
                .as("the raw JSON node must be a String, not a Number")
                .isInstanceOf(String.class);

        // Step 2: feed that exact string back to wiki_list_pages as kbId,
        // exactly as an LLM tool call would. The framework must coerce
        // String → Long with no precision loss, and the routing layer must
        // resolve via findVisibleById (NOT fall back to primary).
        ToolCallback listPages = callbackFor("wiki_list_pages");
        String pagesJson = listPages.call("{\"agentId\":" + AGENT
                + ",\"kbId\":\"" + advertisedKbId + "\"}");
        JSONObject pagesObj = JSONUtil.parseObj(pagesJson);

        assertThat(pagesObj.getStr("error"))
                .as("string-kbId round-trip must NOT raise a not-visible error")
                .isNull();
        assertThat(pagesObj.getJSONArray("pages").getJSONObject(0).getStr("slug"))
                .isEqualTo("only-page");
    }

    @Test
    @DisplayName("kbId=0 from an LLM tool call falls through to primary instead of failing closed")
    void kbIdZeroFromToolCallFallsThroughToPrimary() {
        // The openai-chatgpt family was observed populating every unused
        // numeric optional with 0 in tool-call JSON. The routing layer
        // must treat that as "absent" — otherwise every wiki_* call
        // surfaces a spurious "kbId=0 not visible" error.
        WikiKnowledgeBaseEntity primary = kb(PRIMARY_KB, "Primary", AGENT);
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(primary);
        when(pageService.listSummaries(eq(PRIMARY_KB))).thenReturn(List.of(
                page("primary-page", "Primary Page")));

        ToolCallback listPages = callbackFor("wiki_list_pages");
        // Note: kbId arrives as JSON number 0 — the exact shape the
        // production regression had.
        String json = listPages.call("{\"agentId\":" + AGENT + ",\"kbId\":0}");
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getStr("error")).isNull();
        assertThat(obj.getJSONArray("pages").getJSONObject(0).getStr("slug"))
                .isEqualTo("primary-page");
    }
}
