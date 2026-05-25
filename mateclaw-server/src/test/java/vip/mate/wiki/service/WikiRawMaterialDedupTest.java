package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that addText / addFile deduplication works across all processing
 * statuses, not just 'completed'. A duplicate upload should always reuse
 * the existing row — never insert a second one.
 */
class WikiRawMaterialDedupTest {

    private WikiRawMaterialMapper rawMapper;
    private WikiKnowledgeBaseService kbService;
    private ApplicationEventPublisher eventPublisher;
    private WikiChunkService chunkService;
    private WikiRawMaterialService service;

    private static final Long KB_ID = 1L;

    @BeforeEach
    void setUp() {
        rawMapper = mock(WikiRawMaterialMapper.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        chunkService = mock(WikiChunkService.class);
        WikiProperties props = new WikiProperties();
        props.setAutoProcessOnUpload(false); // don't fire events during test
        DocumentExtractTool docTool = mock(DocumentExtractTool.class);
        ImageVisionService visionService = mock(ImageVisionService.class);
        PdfImageExtractor pdfImageExtractor = mock(PdfImageExtractor.class);
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);

        service = new WikiRawMaterialService(rawMapper, kbService, props, eventPublisher, docTool,
                chunkService, visionService, pdfImageExtractor, featureFlagService);
    }

    private WikiRawMaterialEntity existingRow(Long id, String status) {
        WikiRawMaterialEntity e = new WikiRawMaterialEntity();
        e.setId(id);
        e.setKbId(KB_ID);
        e.setTitle("test.pdf");
        e.setProcessingStatus(status);
        e.setContentHash("abc123");
        return e;
    }

    // ==================== addText dedup ====================

    @ParameterizedTest(name = "addText dedup when existing status = {0}")
    @ValueSource(strings = {"completed", "partial", "failed", "pending", "processing"})
    @DisplayName("addText: same hash should reuse existing row regardless of status")
    void addText_dedup_all_statuses(String status) {
        WikiRawMaterialEntity existing = existingRow(42L, status);
        when(rawMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        // reprocess() calls selectById internally for partial/failed
        when(rawMapper.selectById(42L)).thenReturn(existing);

        WikiRawMaterialEntity result = service.addText(KB_ID, "test", "some content");

        assertEquals(42L, result.getId(), "should return existing id");
        // Should never insert a new row
        verify(rawMapper, never()).insert(any(WikiRawMaterialEntity.class));
    }

    @Test
    @DisplayName("addText: reprocess triggered for partial status")
    void addText_dedup_partial_triggers_reprocess() {
        WikiRawMaterialEntity existing = existingRow(42L, "partial");
        // First call: dedup lookup returns existing
        // Second call (inside reprocess): selectById returns existing
        when(rawMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(rawMapper.selectById(42L)).thenReturn(existing);

        service.addText(KB_ID, "test", "some content");

        // reprocess changes status to pending and fires event
        assertEquals("pending", existing.getProcessingStatus());
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    @DisplayName("addText: reprocess triggered for failed status")
    void addText_dedup_failed_triggers_reprocess() {
        WikiRawMaterialEntity existing = existingRow(42L, "failed");
        when(rawMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(rawMapper.selectById(42L)).thenReturn(existing);

        service.addText(KB_ID, "test", "some content");

        assertEquals("pending", existing.getProcessingStatus());
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    @DisplayName("addText: completed status returns as-is, no reprocess")
    void addText_dedup_completed_no_reprocess() {
        WikiRawMaterialEntity existing = existingRow(42L, "completed");
        when(rawMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        WikiRawMaterialEntity result = service.addText(KB_ID, "test", "some content");

        assertEquals("completed", result.getProcessingStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("addText: pending/processing status returns as-is, no reprocess")
    void addText_dedup_pending_no_reprocess() {
        WikiRawMaterialEntity existing = existingRow(42L, "pending");
        when(rawMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        WikiRawMaterialEntity result = service.addText(KB_ID, "test", "some content");

        assertEquals("pending", result.getProcessingStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("addText: no existing row inserts new record")
    void addText_no_duplicate_inserts() {
        when(rawMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.addText(KB_ID, "test", "brand new content");

        verify(rawMapper).insert(any(WikiRawMaterialEntity.class));
        verify(kbService).incrementRawCount(KB_ID);
    }
}
