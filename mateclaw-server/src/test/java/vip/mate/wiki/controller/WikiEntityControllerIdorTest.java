package vip.mate.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiEntityExtractionService;
import vip.mate.wiki.service.WikiEntityGraphService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the IDOR guard added for ISSUE #438 on
 * {@link WikiEntityController}: entity-graph endpoints must reject requests
 * whose target KB belongs to a different workspace than the caller's
 * {@code X-Workspace-Id} header.
 */
class WikiEntityControllerIdorTest {

    private WikiKnowledgeBaseService kbService;
    private WikiEntityController controller;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        controller = new WikiEntityController(
                mock(WikiEntityGraphService.class),
                mock(WikiEntityExtractionService.class),
                kbService);
    }

    @Test
    @DisplayName("listEntities on another workspace's KB → 403")
    void listEntitiesCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.listEntities(10L, null, 100, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("kbEntityGraph on another workspace's KB → 403")
    void kbEntityGraphCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.kbEntityGraph(10L, 150, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("entityGraph (ego) on another workspace's KB → 403")
    void entityEgoGraphCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.entityGraph(10L, 77L, 50, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("extract (member-level write) on another workspace's KB → 403")
    void extractCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.extract(10L, false, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("unknown kbId → 404")
    void unknownKbReturns404() {
        when(kbService.getById(999L)).thenReturn(null);

        assertThatThrownBy(() -> controller.listEntities(999L, null, 100, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("KB in the caller's workspace passes the guard (no MateClawException)")
    void sameWorkspaceAllowed() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 1L));

        // Guard passed → no exception. (graphService is stubbed to return an
        // empty list so the method returns normally.)
        assertThatCode(() -> controller.listEntities(10L, null, 100, 1L))
                .doesNotThrowAnyException();
    }

    // ---------------- helpers ----------------

    private static WikiKnowledgeBaseEntity kb(long id, long workspaceId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        return entity;
    }
}
