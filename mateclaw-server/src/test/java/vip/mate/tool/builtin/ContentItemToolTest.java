package vip.mate.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.content.model.ContentItemEntity;
import vip.mate.content.service.ContentItemService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pin {@link ContentItemTool}: the fingerprint is stable across cosmetic
 * differences, and check_recent / record / mark_published delegate correctly.
 */
class ContentItemToolTest {

    private ContentItemService service;
    private ContentItemTool tool;

    @BeforeEach
    void setUp() {
        service = mock(ContentItemService.class);
        tool = new ContentItemTool(service);
    }

    @Test
    @DisplayName("fingerprint ignores case / whitespace / punctuation but distinguishes real topics")
    void fingerprintStable() {
        String a = ContentItemService.fingerprint("周末咖啡探店");
        String b = ContentItemService.fingerprint("  周末 咖啡，探店！ ");
        assertEquals(a, b, "cosmetic differences must collapse to the same fingerprint");
        assertNotEquals(a, ContentItemService.fingerprint("露营装备清单"), "different topics differ");
    }

    @Test
    @DisplayName("check_recent: empty history → not a repeat")
    void checkRecentEmpty() {
        when(service.findRecent(eq("gzh"), eq("周末咖啡探店"), anyInt())).thenReturn(List.of());
        String out = tool.content_item("check_recent", "gzh", "周末咖啡探店",
                null, null, null, null, 14, null, null);
        assertTrue(out.contains("未重复"), out);
    }

    @Test
    @DisplayName("check_recent: recent same-topic row → flagged as repeat with its title")
    void checkRecentRepeat() {
        ContentItemEntity prior = new ContentItemEntity();
        prior.setTitle("上周那篇咖啡探店");
        prior.setStatus("published");
        prior.setCreateTime(LocalDateTime.now().minusDays(3));
        when(service.findRecent(eq("gzh"), eq("周末咖啡探店"), anyInt())).thenReturn(List.of(prior));

        String out = tool.content_item("check_recent", "gzh", "周末咖啡探店",
                null, null, null, null, 14, null, null);
        assertTrue(out.contains("疑似重复"), out);
        assertTrue(out.contains("上周那篇咖啡探店"), "should show the prior title");
    }

    @Test
    @DisplayName("record: delegates to the service and reports the item id")
    void recordDelegates() {
        when(service.record(any(), eq("xhs"), eq("露营装备清单"), any(), any(), any(), any()))
                .thenReturn(999L);
        String out = tool.content_item("record", "xhs", "露营装备清单",
                "新手露营必带的8样东西", "packaged", "http://x/preview", null, null, null, null);
        verify(service, times(1)).record(any(), eq("xhs"), eq("露营装备清单"),
                eq("新手露营必带的8样东西"), eq("packaged"), eq("http://x/preview"), isNull());
        assertTrue(out.contains("999"), out);
    }

    @Test
    @DisplayName("mark_published: reports success / not-found from the service")
    void markPublished() {
        when(service.markPublished(123L, "media_abc")).thenReturn(true);
        assertTrue(tool.content_item("mark_published", null, null, null, null,
                null, "media_abc", null, 123L, null).contains("已标记为已发布"));

        when(service.markPublished(404L, null)).thenReturn(false);
        assertTrue(tool.content_item("mark_published", null, null, null, null,
                null, null, null, 404L, null).startsWith("Error:"));
    }

    @Test
    @DisplayName("unknown action is rejected")
    void unknownAction() {
        assertTrue(tool.content_item("frobnicate", null, null, null, null, null, null, null, null, null)
                .startsWith("Error:"));
    }
}
