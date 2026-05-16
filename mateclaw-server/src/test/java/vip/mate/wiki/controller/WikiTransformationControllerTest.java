package vip.mate.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.common.result.R;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiTransformationAggregator;
import vip.mate.wiki.service.WikiTransformationExecutor;
import vip.mate.wiki.service.WikiTransformationService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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
}
