package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Covers the structured error-code + non-blocking warning state on
 * {@link WikiRawMaterialService}: the 4-arg status update persists the code,
 * {@code recordWarning} flags a degraded-but-completed row without changing its
 * status, and {@code claimForProcessing} wipes any stale failure/warning so a
 * re-run starts clean (required because these columns are FieldStrategy.ALWAYS).
 */
class WikiRawMaterialFailureStateTest {

    private WikiRawMaterialMapper rawMapper;
    private WikiRawMaterialService service;

    private static final Long ID = 99L;

    @BeforeEach
    void setUp() {
        rawMapper = mock(WikiRawMaterialMapper.class);
        WikiProperties props = new WikiProperties();
        props.setAutoProcessOnUpload(false);
        service = new WikiRawMaterialService(rawMapper, mock(WikiKnowledgeBaseService.class), props,
                mock(ApplicationEventPublisher.class), mock(DocumentExtractTool.class),
                mock(WikiChunkService.class), mock(ImageVisionService.class),
                mock(PdfImageExtractor.class), mock(FeatureFlagService.class));
    }

    private WikiRawMaterialEntity row(String status) {
        WikiRawMaterialEntity e = new WikiRawMaterialEntity();
        e.setId(ID);
        e.setProcessingStatus(status);
        e.setCancelRequested(Boolean.FALSE);
        return e;
    }

    @Test
    @DisplayName("updateProcessingStatus(4-arg) persists the structured error code")
    void updateStatus_persistsErrorCode() {
        when(rawMapper.selectById(ID)).thenReturn(row("processing"));

        service.updateProcessingStatus(ID, "failed", "AUTH_ERROR", "401 Unauthorized");

        ArgumentCaptor<WikiRawMaterialEntity> captor = ArgumentCaptor.forClass(WikiRawMaterialEntity.class);
        verify(rawMapper).updateById(captor.capture());
        assertEquals("AUTH_ERROR", captor.getValue().getErrorCode());
        assertEquals("401 Unauthorized", captor.getValue().getErrorMessage());
        assertEquals("failed", captor.getValue().getProcessingStatus());
    }

    @Test
    @DisplayName("recordWarning flags a degraded row without touching its status")
    void recordWarning_persistsWithoutStatusChange() {
        when(rawMapper.selectById(ID)).thenReturn(row("completed"));

        service.recordWarning(ID, "EMBEDDING_FAILED", "circuit breaker open");

        ArgumentCaptor<WikiRawMaterialEntity> captor = ArgumentCaptor.forClass(WikiRawMaterialEntity.class);
        verify(rawMapper).updateById(captor.capture());
        assertEquals("EMBEDDING_FAILED", captor.getValue().getWarningCode());
        assertEquals("circuit breaker open", captor.getValue().getWarningMessage());
        assertEquals("completed", captor.getValue().getProcessingStatus());
    }

    @Test
    @DisplayName("claimForProcessing wipes stale error + warning state for a clean re-run")
    void claim_clearsFailureState() {
        WikiRawMaterialEntity stale = row("pending");
        stale.setErrorCode("AUTH_ERROR");
        stale.setErrorMessage("old error");
        stale.setWarningCode("EMBEDDING_FAILED");
        stale.setWarningMessage("old warning");
        when(rawMapper.selectById(ID)).thenReturn(stale);

        assertTrue(service.claimForProcessing(ID));

        ArgumentCaptor<WikiRawMaterialEntity> captor = ArgumentCaptor.forClass(WikiRawMaterialEntity.class);
        verify(rawMapper).updateById(captor.capture());
        WikiRawMaterialEntity persisted = captor.getValue();
        assertNull(persisted.getErrorCode());
        assertNull(persisted.getErrorMessage());
        assertNull(persisted.getWarningCode());
        assertNull(persisted.getWarningMessage());
        assertEquals("processing", persisted.getProcessingStatus());
    }
}
