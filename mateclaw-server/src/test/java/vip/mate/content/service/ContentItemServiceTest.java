package vip.mate.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.content.model.ContentItemEntity;
import vip.mate.content.repository.ContentItemMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pin {@link ContentItemService#record} idempotency: re-packaging the same topic
 * on the same platform within the dedup window updates the existing ledger row
 * instead of inserting a duplicate; a genuinely new topic inserts a fresh row.
 */
class ContentItemServiceTest {

    private ContentItemMapper mapper;
    private ContentItemService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ContentItemMapper.class);
        service = new ContentItemService(mapper);
    }

    @Test
    @DisplayName("re-package of a recent same topic updates the existing row, no duplicate insert")
    void rePackageUpdatesInsteadOfInserting() {
        ContentItemEntity existing = new ContentItemEntity();
        existing.setId(555L);
        existing.setStatus("packaged");
        when(mapper.selectOne(any())).thenReturn(existing);

        Long id = service.record(1L, "gzh", "科技数码选题", "新标题", "packaged", "http://p/2", null);

        assertEquals(555L, id, "should return the existing row's id");
        verify(mapper, never()).insert(any(ContentItemEntity.class));
        verify(mapper, times(1)).updateById(existing);
        assertEquals("新标题", existing.getTitle(), "title refreshed on the existing row");
        assertEquals("http://p/2", existing.getPreviewUrl());
    }

    @Test
    @DisplayName("a new topic (no recent match) inserts a fresh row")
    void newTopicInserts() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.record(1L, "xhs", "全新选题", "标题", "packaged", "http://p/1", null);

        verify(mapper, times(1)).insert(any(ContentItemEntity.class));
        verify(mapper, never()).updateById(any(ContentItemEntity.class));
    }

    @Test
    @DisplayName("fingerprint falls back to title when topic is null")
    void fingerprintFallsBackToTitle() {
        assertEquals(ContentItemService.fingerprint("我的标题"),
                ContentItemService.fingerprint("我的标题"));
        assertNotEquals(ContentItemService.fingerprint("甲"), ContentItemService.fingerprint("乙"));
    }
}
