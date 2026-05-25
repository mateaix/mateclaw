package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.event.WikiProcessingEvent;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that raw materials stuck in 'processing' status after a server
 * restart are recovered to 'pending' with progress fields cleared.
 */
class WikiRawMaterialRecoveryTest {

    private WikiRawMaterialMapper rawMapper;
    private ApplicationEventPublisher eventPublisher;
    private WikiRawMaterialService service;
    private WikiProperties props;

    @BeforeEach
    void setUp() {
        rawMapper = mock(WikiRawMaterialMapper.class);
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        WikiChunkService chunkService = mock(WikiChunkService.class);
        DocumentExtractTool docTool = mock(DocumentExtractTool.class);
        ImageVisionService visionService = mock(ImageVisionService.class);
        PdfImageExtractor pdfImageExtractor = mock(PdfImageExtractor.class);
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);
        props = new WikiProperties();
        props.setAutoProcessOnUpload(true);
        service = new WikiRawMaterialService(rawMapper, kbService, props, eventPublisher, docTool,
                chunkService, visionService, pdfImageExtractor, featureFlagService);
    }

    private WikiRawMaterialEntity stuckRow(Long id, Long kbId) {
        WikiRawMaterialEntity e = new WikiRawMaterialEntity();
        e.setId(id);
        e.setKbId(kbId);
        e.setProcessingStatus("processing");
        e.setProgressPhase("route");
        e.setProgressTotal(5);
        e.setProgressDone(2);
        return e;
    }

    @Test
    @DisplayName("Stuck 'processing' rows are reset to 'pending' with cleared progress")
    void recoverStuck_resetsToPending() {
        WikiRawMaterialEntity row1 = stuckRow(10L, 1L);
        WikiRawMaterialEntity row2 = stuckRow(20L, 2L);
        when(rawMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row1, row2));

        int count = service.recoverStuckRawMaterialsOnStartup();

        assertEquals(2, count);
        assertEquals("pending", row1.getProcessingStatus());
        assertNull(row1.getProgressPhase());
        assertEquals(0, row1.getProgressTotal());
        assertEquals(0, row1.getProgressDone());
        assertEquals("pending", row2.getProcessingStatus());
        verify(rawMapper, times(2)).updateById(any(WikiRawMaterialEntity.class));
    }

    @Test
    @DisplayName("Recovery fires WikiProcessingEvent when autoProcessOnUpload is true")
    void recoverStuck_firesEvents() {
        WikiRawMaterialEntity row = stuckRow(10L, 1L);
        when(rawMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));

        service.recoverStuckRawMaterialsOnStartup();

        ArgumentCaptor<WikiProcessingEvent> captor = ArgumentCaptor.forClass(WikiProcessingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(10L, captor.getValue().getRawMaterialId());
    }

    @Test
    @DisplayName("Recovery does NOT fire events when autoProcessOnUpload is false")
    void recoverStuck_noEventsWhenDisabled() {
        props.setAutoProcessOnUpload(false);
        WikiRawMaterialEntity row = stuckRow(10L, 1L);
        when(rawMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));

        service.recoverStuckRawMaterialsOnStartup();

        assertEquals("pending", row.getProcessingStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("No stuck rows means zero recovery, no events")
    void recoverStuck_emptyIsNoop() {
        when(rawMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        int count = service.recoverStuckRawMaterialsOnStartup();

        assertEquals(0, count);
        verify(rawMapper, never()).updateById(any(WikiRawMaterialEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }
}
