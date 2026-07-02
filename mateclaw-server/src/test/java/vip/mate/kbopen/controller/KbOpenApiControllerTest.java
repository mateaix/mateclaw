package vip.mate.kbopen.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.kbopen.dto.KbOpenApiDtos.PageCard;
import vip.mate.kbopen.service.KbOpenApiService;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiEntityMapper;
import vip.mate.wiki.repository.WikiEntityRelationMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KbOpenApiController}. Focuses on the contract layer:
 * 404 on missing page, and that the controller delegates correctly to
 * services. Auth/scope/ownership is tested via filter+interceptor integration
 * (covered by P0-A tests); here we verify the controller methods themselves.
 */
class KbOpenApiControllerTest {

    private WikiPageService pageService;
    private HybridRetriever hybridRetriever;
    private WikiKnowledgeBaseService kbService;
    private WikiPageCitationMapper citationMapper;
    private WikiChunkMapper chunkMapper;
    private WikiEntityMapper entityMapper;
    private WikiEntityRelationMapper relationMapper;
    private KbOpenApiService openApiService;
    private KbOpenApiController controller;

    @BeforeEach
    void setUp() {
        pageService = mock(WikiPageService.class);
        hybridRetriever = mock(HybridRetriever.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        citationMapper = mock(WikiPageCitationMapper.class);
        chunkMapper = mock(WikiChunkMapper.class);
        entityMapper = mock(WikiEntityMapper.class);
        relationMapper = mock(WikiEntityRelationMapper.class);
        openApiService = mock(KbOpenApiService.class);
        controller = new KbOpenApiController(
                pageService, hybridRetriever, kbService, citationMapper,
                chunkMapper, entityMapper, relationMapper, openApiService);
    }

    @Test
    @DisplayName("getPage returns 404 when slug not found")
    void getPageNotFound() {
        when(pageService.getBySlug(1L, "missing")).thenReturn(null);

        assertThatThrownBy(() -> controller.getPage(1L, "missing", "summary", null))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("getPage delegates to openApiService.assembleCard")
    void getPageDelegatesToService() {
        WikiPageEntity page = new WikiPageEntity();
        page.setSlug("test");
        page.setTitle("Test Page");
        page.setPageType("concept");
        when(pageService.getBySlug(1L, "test")).thenReturn(page);
        PageCard card = new PageCard("test", "Test Page", "concept", "fact",
                "Test Page", "summary", null, null, null, 1, null);
        when(openApiService.assembleCard(page, "summary", null)).thenReturn(card);

        var result = controller.getPage(1L, "test", "summary", null);

        assertThat(result.getData()).isNotNull();
    }

    @Test
    @DisplayName("trace returns 404 when slug not found")
    void traceNotFound() {
        when(pageService.getBySlug(1L, "missing")).thenReturn(null);

        assertThatThrownBy(() -> controller.trace(1L, "missing"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("stats returns 404 when KB not found")
    void statsKbNotFound() {
        when(kbService.getById(99L)).thenReturn(null);

        assertThatThrownBy(() -> controller.stats(99L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }
}
