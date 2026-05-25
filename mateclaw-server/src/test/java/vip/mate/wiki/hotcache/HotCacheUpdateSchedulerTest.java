package vip.mate.wiki.hotcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiHotCacheEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotCacheUpdateSchedulerTest {

    private WikiHotCacheService cacheService;
    private WikiHotCacheUpdater updater;
    private HotCacheProperties props;
    private HotCacheUpdateScheduler scheduler;

    @BeforeEach
    void setUp() {
        cacheService = mock(WikiHotCacheService.class);
        updater = mock(WikiHotCacheUpdater.class);
        props = new HotCacheProperties();
        props.setDebounce(Duration.ofMinutes(5));
        scheduler = new HotCacheUpdateScheduler(props, cacheService, updater);
    }

    @Test
    @DisplayName("blocking call: no existing row → rebuild fires once")
    void firstRebuild() {
        when(cacheService.findByKb(7L)).thenReturn(Optional.empty());
        scheduler.rebuildNowBlocking(7L, HotCacheUpdateReason.CONVERSATION_END);
        verify(updater).rebuild(7L, HotCacheUpdateReason.CONVERSATION_END);
    }

    @Test
    @DisplayName("debounce: rebuild started 1 minute ago + window=5min → next call skipped")
    void withinDebounce_skipped() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(7L);
        row.setLastRebuildStartedAt(LocalDateTime.now().minus(Duration.ofMinutes(1)));
        when(cacheService.findByKb(7L)).thenReturn(Optional.of(row));

        scheduler.rebuildNowBlocking(7L, HotCacheUpdateReason.CONVERSATION_END);

        verify(updater, never()).rebuild(anyLong(), any());
    }

    @Test
    @DisplayName("debounce: rebuild started 6 minutes ago + window=5min → next call passes")
    void outsideDebounce_passes() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(7L);
        row.setLastRebuildStartedAt(LocalDateTime.now().minus(Duration.ofMinutes(6)));
        when(cacheService.findByKb(7L)).thenReturn(Optional.of(row));

        scheduler.rebuildNowBlocking(7L, HotCacheUpdateReason.CONVERSATION_END);

        verify(updater).rebuild(7L, HotCacheUpdateReason.CONVERSATION_END);
    }

    @Test
    @DisplayName("MANUAL reason bypasses debounce")
    void manualBypassesDebounce() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(7L);
        row.setLastRebuildStartedAt(LocalDateTime.now()); // just now
        when(cacheService.findByKb(7L)).thenReturn(Optional.of(row));

        scheduler.rebuildNowBlocking(7L, HotCacheUpdateReason.MANUAL);

        verify(updater).rebuild(7L, HotCacheUpdateReason.MANUAL);
    }

    @Test
    @DisplayName("null kbId is a no-op")
    void nullKbId() {
        scheduler.rebuildNowBlocking(null, HotCacheUpdateReason.MANUAL);
        verify(updater, never()).rebuild(any(), any());
    }

    @Test
    @DisplayName("updater throws → caught, lock released for next call")
    void updaterThrows_lockReleased() {
        when(cacheService.findByKb(eq(7L))).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(updater).rebuild(eq(7L), any());

        // Must not throw
        scheduler.rebuildNowBlocking(7L, HotCacheUpdateReason.MANUAL);

        // Lock released — second call goes through
        org.mockito.Mockito.reset(updater);
        scheduler.rebuildNowBlocking(7L, HotCacheUpdateReason.MANUAL);
        verify(updater, times(1)).rebuild(7L, HotCacheUpdateReason.MANUAL);
    }
}
