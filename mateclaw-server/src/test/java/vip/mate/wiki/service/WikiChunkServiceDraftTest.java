package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.wiki.dto.WikiChunkDraft;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.repository.WikiChunkMapper;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RFC-051 PR-1a: verifies the draft-aware persistChunks overload writes the
 * structural metadata (page_number / token_count / header_breadcrumb /
 * source_section) onto persisted chunk rows.
 */
class WikiChunkServiceDraftTest {

    private WikiChunkMapper chunkMapper;
    private WikiChunkService service;

    private static final Long KB_ID = 1L;
    private static final Long RAW_ID = 100L;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(WikiChunkMapper.class);
        service = new WikiChunkService(chunkMapper);
    }

    @Test
    @DisplayName("persistChunks(drafts): inserts metadata fields onto chunk rows")
    void persistChunksWithDrafts_writesMetadata() {
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        WikiChunkDraft d0 = new WikiChunkDraft("hello world", 0, 11, 1, 3, "Intro", "section-a");
        WikiChunkDraft d1 = new WikiChunkDraft("second piece", 12, 24, 2, 4, "Intro / Setup", "section-b");

        service.persistChunks(KB_ID, RAW_ID, List.of(d0, d1));

        ArgumentCaptor<WikiChunkEntity> captor = ArgumentCaptor.forClass(WikiChunkEntity.class);
        verify(chunkMapper, times(2)).insert(captor.capture());

        List<WikiChunkEntity> inserted = captor.getAllValues();

        WikiChunkEntity first = inserted.get(0);
        assertEquals(0, first.getOrdinal());
        assertEquals("hello world", first.getContent());
        assertEquals(11, first.getCharCount());
        assertEquals(0, first.getStartOffset());
        assertEquals(11, first.getEndOffset());
        assertEquals(1, first.getPageNumber());
        assertEquals(3, first.getTokenCount());
        assertEquals("Intro", first.getHeaderBreadcrumb());
        assertEquals("section-a", first.getSourceSection());
        assertNotNull(first.getContentHash(), "contentHash should be computed");

        WikiChunkEntity second = inserted.get(1);
        assertEquals(1, second.getOrdinal());
        assertEquals(2, second.getPageNumber());
        assertEquals(4, second.getTokenCount());
        assertEquals("Intro / Setup", second.getHeaderBreadcrumb());
        assertEquals("section-b", second.getSourceSection());
    }

    @Test
    @DisplayName("persistChunks(drafts): null metadata fields persist as null")
    void persistChunksWithDrafts_nullMetadataAllowed() {
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        WikiChunkDraft draft = new WikiChunkDraft("plain text", 0, 10, null, null, null, null);

        service.persistChunks(KB_ID, RAW_ID, List.of(draft));

        ArgumentCaptor<WikiChunkEntity> captor = ArgumentCaptor.forClass(WikiChunkEntity.class);
        verify(chunkMapper).insert(captor.capture());

        WikiChunkEntity entity = captor.getValue();
        assertNull(entity.getPageNumber());
        assertNull(entity.getTokenCount());
        assertNull(entity.getHeaderBreadcrumb());
        assertNull(entity.getSourceSection());
        assertEquals("plain text", entity.getContent());
    }
}
