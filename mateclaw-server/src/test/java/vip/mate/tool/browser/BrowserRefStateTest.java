package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserRefStateTest {

    @Test
    @DisplayName("a snapshot with zero interactive refs is still a valid snapshot")
    void emptySnapshotIsValid() {
        BrowserRefState state = new BrowserRefState();

        int generation = state.recordSnapshot("https://example.com/empty", List.of(), Map.of());

        assertEquals(1, generation);
        assertEquals(BrowserRefState.Status.VALID, state.status());
        assertTrue(state.refsValid());
        assertEquals(0, state.refCount());
        assertEquals("https://example.com/empty", state.snapshotUrl());
    }

    @Test
    @DisplayName("main-frame navigation invalidates refs and advances navigation epoch")
    void navigationInvalidatesSnapshot() {
        BrowserRefState state = new BrowserRefState();
        state.recordSnapshot("https://example.com/", List.of("e1"), Map.of());

        state.onMainFrameNavigated("https://www.iana.org/help/example-domains");

        assertEquals(BrowserRefState.Status.INVALIDATED, state.status());
        assertFalse(state.refsValid());
        assertEquals(0, state.refCount());
        assertEquals(1L, state.navigationEpoch());
        assertEquals("https://www.iana.org/help/example-domains", state.currentUrl());
    }

    @Test
    @DisplayName("surface URL reconciliation catches SPA URL changes without frame navigation")
    void reconcileUrlInvalidatesSnapshot() {
        BrowserRefState state = new BrowserRefState();
        state.recordSnapshot("https://example.com/page/1", List.of("e1"), Map.of());

        state.reconcileUrl("https://example.com/page/2");

        assertEquals(BrowserRefState.Status.INVALIDATED, state.status());
        assertFalse(state.refsValid());
        assertEquals(1L, state.navigationEpoch());
    }

    @Test
    @DisplayName("navigation epoch keeps advancing after refs are already invalidated")
    void repeatedUrlChangesAdvanceEpoch() {
        BrowserRefState state = new BrowserRefState();
        state.recordSnapshot("https://example.com/page/1", List.of("e1"), Map.of());

        state.reconcileUrl("https://example.com/page/2");
        state.reconcileUrl("https://example.com/page/3");

        assertEquals(2L, state.navigationEpoch());
        assertEquals("https://example.com/page/3", state.currentUrl());
    }
}
