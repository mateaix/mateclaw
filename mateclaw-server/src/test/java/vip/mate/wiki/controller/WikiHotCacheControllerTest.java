package vip.mate.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.common.result.R;
import vip.mate.wiki.hotcache.HotCacheUpdateReason;
import vip.mate.wiki.hotcache.HotCacheUpdateScheduler;
import vip.mate.wiki.hotcache.WikiHotCacheService;
import vip.mate.wiki.model.WikiHotCacheEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain controller tests — pure behavioral verification, no MockMvc.
 * Spring wiring is exercised by WikiHotCacheProviderE2ETest; here we
 * focus on the controller's logic and call shape.
 */
class WikiHotCacheControllerTest {

    private WikiHotCacheService service;
    private HotCacheUpdateScheduler scheduler;
    private WikiHotCacheController controller;

    @BeforeEach
    void setUp() {
        service = mock(WikiHotCacheService.class);
        scheduler = mock(HotCacheUpdateScheduler.class);
        controller = new WikiHotCacheController(service, scheduler);
    }

    @Test
    @DisplayName("GET returns the row when one exists")
    void get_present() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(7L);
        row.setContent("body");
        when(service.findByKb(7L)).thenReturn(Optional.of(row));

        R<WikiHotCacheEntity> resp = controller.get(7L);

        assertThat(resp.getData()).isNotNull();
        assertThat(resp.getData().getKbId()).isEqualTo(7L);
        assertThat(resp.getData().getContent()).isEqualTo("body");
    }

    @Test
    @DisplayName("GET returns ok with null data when no row")
    void get_missing() {
        when(service.findByKb(7L)).thenReturn(Optional.empty());

        R<WikiHotCacheEntity> resp = controller.get(7L);

        // ok envelope, null payload — operators distinguish "never built" vs "error"
        assertThat(resp.getData()).isNull();
    }

    @Test
    @DisplayName("regenerate schedules a MANUAL rebuild and returns ok")
    void regenerate_schedules() {
        controller.regenerate(7L);

        verify(scheduler).scheduleRebuild(7L, HotCacheUpdateReason.MANUAL);
    }

    @Test
    @DisplayName("regenerate response carries no payload (ack only)")
    void regenerate_responseShape() {
        R<Void> resp = controller.regenerate(7L);
        assertThat(resp.getData()).isNull();
    }

    @Test
    @DisplayName("reset soft-deletes the row when one exists")
    void reset_existing() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setId(99L);
        row.setKbId(7L);
        when(service.findByKb(7L)).thenReturn(Optional.of(row));

        controller.reset(7L);

        verify(service).softDelete(99L);
    }

    @Test
    @DisplayName("reset is a no-op when no row to delete")
    void reset_missing() {
        when(service.findByKb(7L)).thenReturn(Optional.empty());

        controller.reset(7L);

        verify(service, never()).softDelete(anyLong());
    }
}
