package vip.mate.wiki.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiAgentPageTypePermissionEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiAgentPageTypePermissionMapper;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiPageTypePermissionService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the pageType permission gate wired into {@link WikiTool} read and
 * write tools, using a real {@link WikiPageTypePermissionService} backed by a
 * mocked mapper so permission rows are controlled directly.
 */
class WikiToolPermissionTest {

    private static final long AGENT = 11L;
    private static final long KB = 7L;

    private record Harness(WikiTool tool, WikiPageService pageService,
                           WikiAgentPageTypePermissionMapper permMapper) {}

    private Harness harness(List<WikiAgentPageTypePermissionEntity> rows) {
        WikiPageService pageService = mock(WikiPageService.class);
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        WikiRawMaterialService rawService = mock(WikiRawMaterialService.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        ObjectMapper om = new ObjectMapper();

        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(KB);
        when(kbService.findVisibleById(AGENT, KB)).thenReturn(kb);
        when(kbService.getById(KB)).thenReturn(kb);

        WikiAgentPageTypePermissionMapper permMapper = mock(WikiAgentPageTypePermissionMapper.class);
        when(permMapper.selectList(any())).thenReturn(rows);
        WikiPageTypePermissionService permService =
                new WikiPageTypePermissionService(permMapper, kbService, om);

        WikiTool tool = new WikiTool(pageService, kbService, rawService, retriever, om, permService);
        return new Harness(tool, pageService, permMapper);
    }

    private WikiAgentPageTypePermissionEntity row(String type, int read, int create, int update,
                                                  int delete, String writePolicy) {
        WikiAgentPageTypePermissionEntity e = new WikiAgentPageTypePermissionEntity();
        e.setAgentId(AGENT);
        e.setKbId(KB);
        e.setPageType(type);
        e.setCanRead(read);
        e.setCanCreate(create);
        e.setCanUpdate(update);
        e.setCanDelete(delete);
        e.setWritePolicy(writePolicy);
        return e;
    }

    private WikiPageEntity page(String slug, String type) {
        WikiPageEntity p = new WikiPageEntity();
        p.setId(100L);
        p.setKbId(KB);
        p.setSlug(slug);
        p.setTitle("T " + slug);
        p.setContent("body");
        p.setPageType(type);
        p.setLastUpdatedBy("ai");
        return p;
    }

    @Test
    void readPage_unreadableType_reportsNotFound() {
        Harness h = harness(List.of(row("*", 0, 0, 0, 0, "deny")));
        when(h.pageService().getBySlug(KB, "secret")).thenReturn(page("secret", "analysis"));

        String out = h.tool().wiki_read_page(AGENT, "secret", null, null, null, KB);

        assertTrue(out.contains("Page not found"), out);
    }

    @Test
    void readPage_readableType_returnsContent() {
        Harness h = harness(List.of(row("*", 1, 0, 0, 0, "deny")));
        when(h.pageService().getBySlug(KB, "ok")).thenReturn(page("ok", "concept"));

        String out = h.tool().wiki_read_page(AGENT, "ok", null, null, null, KB);

        assertTrue(out.contains("\"content\""), out);
        assertFalse(out.contains("Page not found"), out);
    }

    @Test
    void deletePage_denied_doesNotDelete() {
        // can read, but delete flag off → DENY
        Harness h = harness(List.of(row("concept", 1, 0, 0, 0, "deny")));
        when(h.pageService().getBySlug(KB, "p")).thenReturn(page("p", "concept"));

        String out = h.tool().wiki_delete_page(AGENT, "p", null, KB);

        assertTrue(out.contains("Not permitted"), out);
        verify(h.pageService(), never()).delete(anyLong(), any());
    }

    @Test
    void deletePage_approvalRequired_blocksAndDoesNotDelete() {
        Harness h = harness(List.of(row("concept", 1, 0, 0, 1, "approval_required")));
        when(h.pageService().getBySlug(KB, "p")).thenReturn(page("p", "concept"));

        String out = h.tool().wiki_delete_page(AGENT, "p", null, KB);

        assertTrue(out.contains("Approval required"), out);
        verify(h.pageService(), never()).delete(anyLong(), any());
    }

    @Test
    void deletePage_allowed_deletes() {
        Harness h = harness(List.of(row("concept", 1, 1, 1, 1, "allow")));
        when(h.pageService().getBySlug(KB, "p")).thenReturn(page("p", "concept"));

        String out = h.tool().wiki_delete_page(AGENT, "p", null, KB);

        assertTrue(out.contains("\"ok\":true"), out);
        verify(h.pageService(), times(1)).delete(eq(KB), eq("p"));
    }

    @Test
    void createPage_deniedByWildcard_doesNotCreate() {
        // a row exists for 'episode' only → KB is gated, wildcard create not granted
        Harness h = harness(List.of(row("episode", 1, 1, 1, 1, "allow")));

        String out = h.tool().wiki_create_page(AGENT, "New Page", "content here", null, KB);

        assertTrue(out.contains("Not permitted"), out);
        verify(h.pageService(), never()).createPage(anyLong(), any(), any(), any(), any(), any());
    }

    // ---------- wiki_update_page: in-place update, no delete/recreate ----------

    @Test
    void updatePage_allowed_updatesInPlace() {
        Harness h = harness(List.of(row("concept", 1, 1, 1, 1, "allow")));
        when(h.pageService().getBySlug(KB, "p")).thenReturn(page("p", "concept"));
        WikiPageEntity updated = page("p", "concept");
        updated.setVersion(2);
        when(h.pageService().updatePageManually(eq(KB), eq("p"), any(), any())).thenReturn(updated);

        String out = h.tool().wiki_update_page(AGENT, "p", "new body", null, null, KB);

        assertTrue(out.contains("\"ok\":true"), out);
        assertTrue(out.contains("updated in place"), out);
        verify(h.pageService(), times(1)).updatePageManually(eq(KB), eq("p"), eq("new body"), any());
        // crucially, it must NOT delete or recreate (the duplicate-page bug)
        verify(h.pageService(), never()).delete(anyLong(), any());
        verify(h.pageService(), never()).createPage(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void updatePage_updateDenied_doesNotUpdate() {
        // can read + create, but update flag off → DENY
        Harness h = harness(List.of(row("concept", 1, 1, 0, 0, "allow")));
        when(h.pageService().getBySlug(KB, "p")).thenReturn(page("p", "concept"));

        String out = h.tool().wiki_update_page(AGENT, "p", "new body", null, null, KB);

        assertTrue(out.contains("Not permitted"), out);
        verify(h.pageService(), never()).updatePageManually(anyLong(), any(), any(), any());
    }

    @Test
    void updatePage_missingPage_reportsNotFound() {
        Harness h = harness(List.of(row("*", 1, 1, 1, 1, "allow")));
        when(h.pageService().getBySlug(KB, "ghost")).thenReturn(null);

        String out = h.tool().wiki_update_page(AGENT, "ghost", "body", null, null, KB);

        assertTrue(out.contains("Page not found"), out);
        verify(h.pageService(), never()).updatePageManually(anyLong(), any(), any(), any());
    }

    // ---------- wiki_stale_pages: lists stale, honours read filter ----------

    private WikiPageEntity stalePage(String slug, String type, String reason) {
        WikiPageEntity p = page(slug, type);
        p.setStale(1);
        p.setStaleReasonJson(reason);
        return p;
    }

    @Test
    void stalePages_listsOnlyStaleReadablePages() {
        // wildcard allows reading 'concept' but a specific 'secret' row denies read
        Harness h = harness(List.of(row("*", 1, 0, 0, 0, "deny"), row("secret", 0, 0, 0, 0, "deny")));
        WikiPageEntity fresh = page("fresh", "concept");        // not stale → excluded
        WikiPageEntity staleOk = stalePage("aged", "concept", "{\"reason\":\"fact updated\"}");
        WikiPageEntity staleHidden = stalePage("classified", "secret", "{\"reason\":\"x\"}");
        when(h.pageService().listByKbId(KB)).thenReturn(List.of(fresh, staleOk, staleHidden));

        String out = h.tool().wiki_stale_pages(AGENT, null, KB);

        assertTrue(out.contains("\"staleCount\":1"), out);
        assertTrue(out.contains("aged"), out);
        assertFalse(out.contains("classified"), out);  // unreadable type filtered out
        assertFalse(out.contains("fresh"), out);        // non-stale excluded
    }

    @Test
    void stalePages_noneStale_returnsZero() {
        Harness h = harness(List.of());
        when(h.pageService().listByKbId(KB)).thenReturn(List.of(page("a", "concept"), page("b", "episode")));

        String out = h.tool().wiki_stale_pages(AGENT, null, KB);

        assertTrue(out.contains("\"staleCount\":0"), out);
    }
}
