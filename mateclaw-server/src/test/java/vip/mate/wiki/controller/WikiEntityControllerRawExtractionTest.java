package vip.mate.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.service.WikiEntityExtractionService;
import vip.mate.wiki.service.WikiEntityGraphService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Per-document entity extraction endpoint: ownership, readiness and dispatch. */
class WikiEntityControllerRawExtractionTest {

    private static final long KB_ID = 10L;
    private static final long RAW_ID = 2055137662148763649L;

    private WikiKnowledgeBaseService kbService;
    private WikiRawMaterialService rawService;
    private WikiEntityExtractionService extractionService;
    private WikiEntityController controller;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        rawService = mock(WikiRawMaterialService.class);
        extractionService = mock(WikiEntityExtractionService.class);
        controller = new WikiEntityController(mock(WikiEntityGraphService.class), extractionService, kbService, rawService);
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(KB_ID);
        kb.setWorkspaceId(1L);
        when(kbService.getById(KB_ID)).thenReturn(kb);
    }

    @Test
    @DisplayName("completed document in the KB → extraction dispatched with force flag")
    void completedDocumentStartsExtraction() {
        when(rawService.getById(RAW_ID)).thenReturn(raw(KB_ID, "completed"));

        Map<String, Object> result = controller.extractRaw(KB_ID, RAW_ID, true, 1L);

        assertThat(result).containsEntry("status", "started").containsEntry("rawId", RAW_ID);
        verify(extractionService, timeout(2000)).extractForRaw(KB_ID, RAW_ID, true);
    }

    @Test
    @DisplayName("document from another KB → 404, nothing dispatched")
    void documentFromAnotherKbRejected() {
        when(rawService.getById(RAW_ID)).thenReturn(raw(99L, "completed"));

        assertThatThrownBy(() -> controller.extractRaw(KB_ID, RAW_ID, false, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
        verify(extractionService, never()).extractForRaw(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("missing document → 404")
    void missingDocumentRejected() {
        assertThatThrownBy(() -> controller.extractRaw(KB_ID, RAW_ID, false, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("document still processing → rejected, nothing dispatched")
    void unfinishedDocumentRejected() {
        when(rawService.getById(RAW_ID)).thenReturn(raw(KB_ID, "processing"));

        assertThatThrownBy(() -> controller.extractRaw(KB_ID, RAW_ID, false, 1L))
                .isInstanceOf(MateClawException.class);
        verify(extractionService, never()).extractForRaw(anyLong(), anyLong(), anyBoolean());
    }

    private static WikiRawMaterialEntity raw(long kbId, String status) {
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(RAW_ID);
        raw.setKbId(kbId);
        raw.setProcessingStatus(status);
        return raw;
    }
}
