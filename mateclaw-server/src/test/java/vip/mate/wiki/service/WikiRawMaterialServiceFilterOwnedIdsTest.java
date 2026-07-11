package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;
import vip.mate.wiki.support.MybatisPlusLambdaCacheInitializer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WikiRawMaterialService#filterOwnedIds} is the single-query replacement for the
 * previous listByKbId()+stream().filter() pattern used to keep updateRawGroupBatch from
 * accepting a foreign-KB rawId — it must scope by both kbId and the requested ids in one
 * query rather than filtering an in-memory full table scan.
 */
class WikiRawMaterialServiceFilterOwnedIdsTest {

    @BeforeAll
    static void initLambdaCache() {
        MybatisPlusLambdaCacheInitializer.init(WikiRawMaterialEntity.class);
    }

    private WikiRawMaterialMapper rawMapper;
    private WikiRawMaterialService service;

    @BeforeEach
    void setUp() {
        rawMapper = mock(WikiRawMaterialMapper.class);
        service = new WikiRawMaterialService(
                rawMapper,
                mock(WikiKnowledgeBaseService.class),
                mock(WikiProperties.class),
                mock(ApplicationEventPublisher.class),
                mock(DocumentExtractTool.class),
                mock(WikiChunkService.class),
                mock(ImageVisionService.class),
                mock(PdfImageExtractor.class),
                mock(FeatureFlagService.class));
    }

    @Test
    @DisplayName("filterOwnedIds: only returns rawIds the scoped query actually matched")
    void filterOwnedIds_returnsOnlyMatchedFromSingleQuery() {
        WikiRawMaterialEntity owned = new WikiRawMaterialEntity();
        owned.setId(7L);
        when(rawMapper.selectList(any())).thenReturn(List.of(owned));

        List<Long> result = service.filterOwnedIds(1L, List.of(7L, 999L));

        assertEquals(List.of(7L), result, "a foreign-KB rawId (999L) must not survive the query scope");
    }

    @Test
    @DisplayName("filterOwnedIds: empty rawIds short-circuits without querying")
    void filterOwnedIds_emptyInputShortCircuits() {
        List<Long> result = service.filterOwnedIds(1L, List.of());

        assertEquals(List.of(), result);
        verifyNoInteractions(rawMapper);
    }
}
