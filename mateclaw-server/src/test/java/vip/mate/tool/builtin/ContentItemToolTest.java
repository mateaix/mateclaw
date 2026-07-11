package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.content.model.ContentItemEntity;
import vip.mate.content.repository.ContentItemMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pin {@link ContentItemTool}: the topic fingerprint is stable across cosmetic
 * differences (so repeats are caught), and the check_recent / record /
 * mark_published actions behave.
 */
class ContentItemToolTest {

    private ContentItemMapper mapper;
    private ContentItemTool tool;

    @BeforeEach
    void setUp() {
        mapper = mock(ContentItemMapper.class);
        tool = new ContentItemTool(mapper);
    }

    @Test
    @DisplayName("fingerprint ignores case / whitespace / punctuation but distinguishes real topics")
    void fingerprintStable() {
        String a = ContentItemTool.fingerprint("周末咖啡探店");
        String b = ContentItemTool.fingerprint("  周末 咖啡，探店！ ");
        assertEquals(a, b, "cosmetic differences must collapse to the same fingerprint");
        assertNotEquals(a, ContentItemTool.fingerprint("露营装备清单"), "different topics differ");
    }

    @Test
    @DisplayName("check_recent: empty history → not a repeat")
    void checkRecentEmpty() {
        when(mapper.selectList(any())).thenReturn(List.of());
        String out = tool.content_item("check_recent", "gzh", "周末咖啡探店",
                null, null, null, null, 14, null);
        assertTrue(out.contains("未重复"), out);
    }

    @Test
    @DisplayName("check_recent: recent same-topic row → flagged as repeat with its title")
    void checkRecentRepeat() {
        ContentItemEntity prior = new ContentItemEntity();
        prior.setTitle("上周那篇咖啡探店");
        prior.setStatus("published");
        prior.setCreateTime(LocalDateTime.now().minusDays(3));
        when(mapper.selectList(any())).thenReturn(List.of(prior));

        String out = tool.content_item("check_recent", "gzh", "周末咖啡探店",
                null, null, null, null, 14, null);
        assertTrue(out.contains("疑似重复"), out);
        assertTrue(out.contains("上周那篇咖啡探店"), "should show the prior title");
    }

    @Test
    @DisplayName("record: inserts a row and reports the item")
    void recordInserts() {
        String out = tool.content_item("record", "xhs", "露营装备清单",
                "新手露营必带的8样东西", "packaged", "http://x/preview", null, null, null);
        verify(mapper, times(1)).insert(any(ContentItemEntity.class));
        assertTrue(out.contains("已记入内容日历"), out);
    }

    @Test
    @DisplayName("mark_published: flips status and stamps publish time")
    void markPublished() {
        ContentItemEntity e = new ContentItemEntity();
        e.setStatus("packaged");
        when(mapper.selectById(123L)).thenReturn(e);

        String out = tool.content_item("mark_published", null, null, null, null,
                null, "media_abc", null, 123L);
        assertTrue(out.contains("已标记为已发布"), out);
        assertEquals("published", e.getStatus());
        assertNotNull(e.getPublishTime());
        verify(mapper).updateById(e);
    }

    @Test
    @DisplayName("unknown action is rejected")
    void unknownAction() {
        assertTrue(tool.content_item("frobnicate", null, null, null, null, null, null, null, null)
                .startsWith("Error:"));
    }
}
