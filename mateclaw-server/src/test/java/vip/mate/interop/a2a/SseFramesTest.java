package vip.mate.interop.a2a;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseFramesTest {

    @Test
    void parsesSingleDataFrameAtBlankLine() {
        List<SseFrames.Frame> frames = SseFrames.parse("event: artifact-update\ndata: {\"x\":1}\n\n");

        assertEquals(1, frames.size());
        assertEquals("artifact-update", frames.getFirst().event());
        assertEquals("{\"x\":1}", frames.getFirst().data());
    }

    @Test
    void joinsMultiLineDataWithNewlines() {
        List<SseFrames.Frame> frames = SseFrames.parse("event: message\ndata: first\ndata: second\n\n");

        assertEquals("first\nsecond", frames.getFirst().data());
    }

    @Test
    void ignoresCommentHeartbeatFrames() {
        List<SseFrames.Frame> frames = SseFrames.parse(": heartbeat\n\n");

        assertTrue(frames.isEmpty());
    }

    @Test
    void flushesTrailingFrameWithoutFinalBlankLine() {
        List<SseFrames.Frame> frames = SseFrames.parse("data: tail");

        assertEquals(1, frames.size());
        assertEquals("message", frames.getFirst().event());
        assertEquals("tail", frames.getFirst().data());
    }

    @Test
    void preservesEventBoundaryAcrossMultipleFrames() {
        List<SseFrames.Frame> frames = SseFrames.parse("""
                event: status-update
                data: working

                event: artifact-update
                data: one
                data: two

                """);

        assertEquals(2, frames.size());
        assertEquals("working", frames.get(0).data());
        assertEquals("one\ntwo", frames.get(1).data());
    }
}
