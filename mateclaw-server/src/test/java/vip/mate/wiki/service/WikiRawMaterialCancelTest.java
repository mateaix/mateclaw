package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Behavioural tests for the cancel-flag plumbing on {@link WikiRawMaterialService}:
 * ensures the flag can only be set while a row is processing, that the next
 * {@code claimForProcessing} clears any stale flag, and that transitioning to
 * a terminal status also clears it.
 */
class WikiRawMaterialCancelTest {

    private WikiRawMaterialMapper rawMapper;
    private WikiRawMaterialService service;

    private static final Long ID = 99L;

    @BeforeEach
    void setUp() {
        rawMapper = mock(WikiRawMaterialMapper.class);
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        WikiChunkService chunkService = mock(WikiChunkService.class);
        WikiProperties props = new WikiProperties();
        props.setAutoProcessOnUpload(false);
        DocumentExtractTool docTool = mock(DocumentExtractTool.class);
        ImageVisionService visionService = mock(ImageVisionService.class);
        PdfImageExtractor pdfImageExtractor = mock(PdfImageExtractor.class);
        FeatureFlagService featureFlagService = mock(FeatureFlagService.class);

        service = new WikiRawMaterialService(rawMapper, kbService, props, eventPublisher, docTool,
                chunkService, visionService, pdfImageExtractor, featureFlagService);
    }

    private WikiRawMaterialEntity row(String status, Boolean cancelRequested) {
        WikiRawMaterialEntity e = new WikiRawMaterialEntity();
        e.setId(ID);
        e.setProcessingStatus(status);
        e.setCancelRequested(cancelRequested);
        return e;
    }

    @Test
    @DisplayName("requestCancel sets the flag when status is processing")
    void requestCancel_setsFlagWhenProcessing() {
        when(rawMapper.selectById(ID)).thenReturn(row("processing", Boolean.FALSE));

        boolean ok = service.requestCancel(ID);

        assertTrue(ok);
        ArgumentCaptor<WikiRawMaterialEntity> captor = ArgumentCaptor.forClass(WikiRawMaterialEntity.class);
        verify(rawMapper).updateById(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getCancelRequested());
    }

    @ParameterizedTest(name = "requestCancel is a no-op when status = {0}")
    @ValueSource(strings = {"pending", "completed", "failed", "partial", "cancelled"})
    @DisplayName("requestCancel is a no-op outside the processing state")
    void requestCancel_noOpForNonProcessingStatuses(String status) {
        when(rawMapper.selectById(ID)).thenReturn(row(status, Boolean.FALSE));

        boolean ok = service.requestCancel(ID);

        assertFalse(ok);
        verify(rawMapper, never()).updateById(any(WikiRawMaterialEntity.class));
    }

    @Test
    @DisplayName("requestCancel returns true and skips the write when already requested")
    void requestCancel_idempotentWhenAlreadyRequested() {
        when(rawMapper.selectById(ID)).thenReturn(row("processing", Boolean.TRUE));

        boolean ok = service.requestCancel(ID);

        assertTrue(ok);
        verify(rawMapper, never()).updateById(any(WikiRawMaterialEntity.class));
    }

    @Test
    @DisplayName("requestCancel on a missing row returns false")
    void requestCancel_missingRowReturnsFalse() {
        when(rawMapper.selectById(ID)).thenReturn(null);

        assertFalse(service.requestCancel(ID));
        verify(rawMapper, never()).updateById(any(WikiRawMaterialEntity.class));
    }

    @Test
    @DisplayName("isCancelRequested mirrors the persisted flag")
    void isCancelRequested_reflectsFlag() {
        when(rawMapper.selectById(ID)).thenReturn(row("processing", Boolean.TRUE));
        assertTrue(service.isCancelRequested(ID));

        when(rawMapper.selectById(ID)).thenReturn(row("processing", Boolean.FALSE));
        assertFalse(service.isCancelRequested(ID));

        when(rawMapper.selectById(ID)).thenReturn(null);
        assertFalse(service.isCancelRequested(ID));
    }

    @Test
    @DisplayName("claimForProcessing clears a stale cancel flag from a previous run")
    void claim_clearsStaleCancelFlag() {
        when(rawMapper.selectById(ID)).thenReturn(row("pending", Boolean.TRUE));

        boolean claimed = service.claimForProcessing(ID);

        assertTrue(claimed);
        ArgumentCaptor<WikiRawMaterialEntity> captor = ArgumentCaptor.forClass(WikiRawMaterialEntity.class);
        verify(rawMapper).updateById(captor.capture());
        WikiRawMaterialEntity persisted = captor.getValue();
        assertEquals("processing", persisted.getProcessingStatus());
        assertEquals(Boolean.FALSE, persisted.getCancelRequested());
    }

    @ParameterizedTest(name = "updateProcessingStatus({0}) clears cancel flag")
    @ValueSource(strings = {"completed", "failed", "partial", "cancelled"})
    @DisplayName("Any transition out of processing clears the cancel flag")
    void updateStatus_clearsCancelFlagOnTerminal(String terminal) {
        when(rawMapper.selectById(ID)).thenReturn(row("processing", Boolean.TRUE));

        service.updateProcessingStatus(ID, terminal, "detail");

        ArgumentCaptor<WikiRawMaterialEntity> captor = ArgumentCaptor.forClass(WikiRawMaterialEntity.class);
        verify(rawMapper).updateById(captor.capture());
        WikiRawMaterialEntity persisted = captor.getValue();
        assertEquals(terminal, persisted.getProcessingStatus());
        assertEquals(Boolean.FALSE, persisted.getCancelRequested());
    }

    @Test
    @DisplayName("updateProcessingStatus to 'processing' leaves the flag untouched")
    void updateStatus_processingLeavesFlagAsIs() {
        when(rawMapper.selectById(ID)).thenReturn(row("pending", Boolean.TRUE));

        service.updateProcessingStatus(ID, "processing", null);

        ArgumentCaptor<WikiRawMaterialEntity> captor = ArgumentCaptor.forClass(WikiRawMaterialEntity.class);
        verify(rawMapper).updateById(captor.capture());
        // Stays as it was — claimForProcessing is the canonical way to clear it.
        assertEquals(Boolean.TRUE, captor.getValue().getCancelRequested());
    }
}
