package vip.mate.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the adaptive-debounce thresholds in
 * {@link ChannelMessageRouter#pickDebounceMs(int)}.
 *
 * <p>The router merges same-conversation messages within a debounce window
 * before forwarding to the agent. The default {@link
 * ChannelMessageRouter#DEBOUNCE_MS} (500ms) is right for normal chatting
 * but too short for IM clients that silently split long pasted prompts
 * across multiple frames — the second fragment can arrive 1-2 seconds
 * after the first, missing the window. When merged content crosses
 * {@link ChannelMessageRouter#LONG_TEXT_THRESHOLD} the merger switches to
 * {@link ChannelMessageRouter#LONG_DEBOUNCE_MS} so it has time to absorb
 * the rest. These tests document that boundary behavior so future tuning
 * is intentional rather than incidental.
 */
class ChannelMessageRouterDebounceTest {

    @Test
    @DisplayName("short messages keep the 500ms default debounce")
    void shortMessagesKeepDefaultDebounce() {
        assertEquals(ChannelMessageRouter.DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(0));
        assertEquals(ChannelMessageRouter.DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(50));
        assertEquals(ChannelMessageRouter.DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(500));
        assertEquals(ChannelMessageRouter.DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(ChannelMessageRouter.LONG_TEXT_THRESHOLD));
    }

    @Test
    @DisplayName("crossing the threshold flips to the extended 2.5s window")
    void longContentTriggersLongDebounce() {
        assertEquals(ChannelMessageRouter.LONG_DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(ChannelMessageRouter.LONG_TEXT_THRESHOLD + 1));
        assertEquals(ChannelMessageRouter.LONG_DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(2000));
        assertEquals(ChannelMessageRouter.LONG_DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(6000));
        assertEquals(ChannelMessageRouter.LONG_DEBOUNCE_MS,
                ChannelMessageRouter.pickDebounceMs(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("threshold + windows are sane: long > default, threshold below typical IM split")
    void thresholdsAreSane() {
        // The whole point — the extended window must actually be larger,
        // otherwise the adaptive branch is a no-op.
        assertTrue(ChannelMessageRouter.LONG_DEBOUNCE_MS > ChannelMessageRouter.DEBOUNCE_MS,
                "extended debounce must exceed default");
        // Threshold sits below the typical ~2000-char WeCom client split
        // point; if it ever crept above 2000 the merger would never
        // engage on a real paste-split.
        assertTrue(ChannelMessageRouter.LONG_TEXT_THRESHOLD < 2000,
                "threshold must stay under the IM client's split point");
        // And well above any normally typed message — typing 1500+ chars
        // in one bubble is extremely rare. Guards against accidentally
        // applying the long-debounce penalty to ordinary chats.
        assertTrue(ChannelMessageRouter.LONG_TEXT_THRESHOLD >= 1000,
                "threshold must be high enough that typing doesn't trip it");
    }
}
