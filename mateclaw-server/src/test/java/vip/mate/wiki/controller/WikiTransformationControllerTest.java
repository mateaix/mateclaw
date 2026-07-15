package vip.mate.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiTransformationAggregator;
import vip.mate.wiki.service.WikiTransformationExecutor;
import vip.mate.wiki.service.WikiTransformationService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiTransformationControllerTest {

    private WikiTransformationService transformationService;
    private WikiTransformationController controller;

    @BeforeEach
    void setUp() {
        transformationService = mock(WikiTransformationService.class);
        controller = new WikiTransformationController(
                transformationService,
                mock(WikiTransformationExecutor.class),
                mock(WikiTransformationAggregator.class),
                mock(WikiKnowledgeBaseService.class));
    }

    @Test
    void applyMissingTemplateReturns404Envelope() {
        when(transformationService.getById(99L)).thenReturn(null);

        R<WikiTransformationRunEntity> response = controller.apply(
                99L, Map.of("rawId", 1L), false, 1L);

        assertEquals(404, response.getCode());
    }

    @Test
    void applyWithRawIdAndPageIdReturns400Envelope() {
        WikiTransformationEntity transformation = new WikiTransformationEntity();
        transformation.setId(99L);
        transformation.setWorkspaceId(1L);
        when(transformationService.getById(99L)).thenReturn(transformation);

        R<WikiTransformationRunEntity> response = controller.apply(
                99L, Map.of("rawId", 1L, "pageId", 2L), false, 1L);

        assertEquals(400, response.getCode());
    }

    @Test
    void updateGlobalTemplateThrows403() {
        WikiTransformationEntity global = new WikiTransformationEntity();
        global.setId(1000004001L);
        global.setWorkspaceId(null); // global starter pack
        when(transformationService.getById(1000004001L)).thenReturn(global);

        MateClawException ex = assertThrows(MateClawException.class, () ->
                controller.update(1000004001L, new WikiTransformationEntity(), 999L));

        assertEquals(403, ex.getCode());
        assertEquals("err.wiki.global_template_readonly", ex.getMsgKey());
        // The mutating service call must never be reached — no partial write.
        verify(transformationService, never()).update(anyLong(), any());
    }

    @Test
    void deleteGlobalTemplateThrows403() {
        WikiTransformationEntity global = new WikiTransformationEntity();
        global.setId(1000004001L);
        global.setWorkspaceId(null);
        when(transformationService.getById(1000004001L)).thenReturn(global);

        MateClawException ex = assertThrows(MateClawException.class, () ->
                controller.delete(1000004001L, 999L));

        assertEquals(403, ex.getCode());
        assertEquals("err.wiki.global_template_readonly", ex.getMsgKey());
        verify(transformationService, never()).delete(anyLong());
    }
}
