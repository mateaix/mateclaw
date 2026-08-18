package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the incremental {@code <think>} tag extractor used by the
 * streaming path. Covers whole-tag chunks, tags split across chunk
 * boundaries, multiple think spans, unterminated tags, literal {@code <}
 * characters that never become a tag, and the disable (structured-reasoning
 * bypass) mode. Every case also asserts character conservation: content +
 * thinking + tag characters must add up to the input.
 */
class ThinkTagStreamExtractorTest {

    /** Feed all chunks, then flush; returns [content, thinking]. */
    private static String[] run(ThinkTagStreamExtractor extractor, List<String> chunks) {
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        for (String chunk : chunks) {
            var ex = extractor.feed(chunk);
            content.append(ex.content());
            thinking.append(ex.thinking());
        }
        var rest = extractor.flush();
        content.append(rest.content());
        thinking.append(rest.thinking());
        return new String[]{content.toString(), thinking.toString()};
    }

    private static String[] run(List<String> chunks) {
        return run(new ThinkTagStreamExtractor(), chunks);
    }

    @Test
    void passesThroughContentWithoutTags() {
        var out = run(List.of("Hello ", "world", "!"));
        assertEquals("Hello world!", out[0]);
        assertEquals("", out[1]);
    }

    @Test
    void extractsSingleTagWithinOneChunk() {
        var out = run(List.of("<think>reasoning</think>answer"));
        assertEquals("answer", out[0]);
        assertEquals("reasoning", out[1]);
    }

    @Test
    void extractsTagSplitAcrossChunks() {
        var out = run(List.of("<thi", "nk>step one", " step two</th", "ink>final"));
        assertEquals("final", out[0]);
        assertEquals("step one step two", out[1]);
    }

    @Test
    void extractsTagSplitCharByChar() {
        var out = run("<think>ab</think>cd".chars()
                .mapToObj(c -> String.valueOf((char) c))
                .toList());
        assertEquals("cd", out[0]);
        assertEquals("ab", out[1]);
    }

    @Test
    void extractsMultipleThinkSpans() {
        var out = run(List.of("a<think>t1</think>b<think>t2</think>c"));
        assertEquals("abc", out[0]);
        assertEquals("t1t2", out[1]);
    }

    @Test
    void unterminatedTagRoutesRemainderToThinking() {
        var out = run(List.of("before<think>never closed ", "still thinking"));
        assertEquals("before", out[0]);
        assertEquals("never closed still thinking", out[1]);
    }

    @Test
    void unterminatedTagFlushesPartialCloseTagAsThinking() {
        // Stream dies right inside a partial close tag: the held-back "</thi"
        // can no longer complete, so it drains as thinking text.
        var out = run(List.of("<think>abc</thi"));
        assertEquals("", out[0]);
        assertEquals("abc</thi", out[1]);
    }

    @Test
    void literalAngleBracketsAreNotSwallowed() {
        var out = run(List.of("a < b and a << b, <thin fabric>"));
        assertEquals("a < b and a << b, <thin fabric>", out[0]);
        assertEquals("", out[1]);
    }

    @Test
    void heldBackFalseAlarmPrefixIsReleasedAsContent() {
        // "<thin" is a plausible tag start at the chunk boundary but the next
        // chunk disproves it — every character must come back as content.
        var out = run(List.of("size <thin", "g> matters"));
        assertEquals("size <thing> matters", out[0]);
        assertEquals("", out[1]);
    }

    @Test
    void flushReturnsHeldBackTailAsContentInTextMode() {
        var out = run(List.of("answer ends with <thi"));
        assertEquals("answer ends with <thi", out[0]);
        assertEquals("", out[1]);
    }

    @Test
    void contentBeforeAndAfterTagInSameChunk() {
        var out = run(List.of("intro <think>plan</think> outro"));
        assertEquals("intro  outro", out[0]);
        assertEquals("plan", out[1]);
    }

    @Test
    void disabledExtractorPassesTagsThrough() {
        var extractor = new ThinkTagStreamExtractor();
        extractor.disable();
        var out = run(extractor, List.of("<think>not extracted</think>"));
        assertEquals("<think>not extracted</think>", out[0]);
        assertEquals("", out[1]);
    }

    @Test
    void disableReleasesHeldBackTailAsContent() {
        var extractor = new ThinkTagStreamExtractor();
        var first = extractor.feed("partial <thi");
        assertEquals("partial ", first.content());
        extractor.disable();
        var second = extractor.feed("nk> stays literal");
        assertEquals("<think> stays literal", second.content());
        assertEquals("", second.thinking());
    }

    @Test
    void emptyAndNullChunksAreNoOps() {
        var extractor = new ThinkTagStreamExtractor();
        assertEquals("", extractor.feed("").content());
        assertEquals("", extractor.feed(null).content());
        assertEquals("", extractor.flush().content());
        assertEquals("", extractor.flush().thinking());
    }

    @Test
    void conservesEveryNonTagCharacterAcrossRandomSplits() {
        String input = "start<think>alpha</think>mid<think>beta gamma</think>end < loose";
        String expectedContent = "startmidend < loose";
        String expectedThinking = "alphabeta gamma";
        // Deterministic sweep over split widths instead of randomness so a
        // failure always reproduces.
        for (int width = 1; width <= input.length(); width++) {
            java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
            for (int i = 0; i < input.length(); i += width) {
                chunks.add(input.substring(i, Math.min(i + width, input.length())));
            }
            var out = run(chunks);
            assertEquals(expectedContent, out[0], "content mismatch at width " + width);
            assertEquals(expectedThinking, out[1], "thinking mismatch at width " + width);
        }
    }
}
