package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.config.ConversationWindowProperties;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three compaction phases (soft trim, hard clear, pre-prune for
 * summary) must never destroy a spill-marker body — doing so would erase
 * the {@code path=...} pointer the model needs to recover the original
 * full output via {@code read_file}, which is the whole reason that body
 * was spilled in the first place.
 *
 * <p>This is the "recoverable" invariant: once a tool output makes it
 * into the spill store, the in-context representation stays a stable
 * preview + path for the rest of the conversation regardless of how
 * aggressively the window manager has to compress the prefix.
 */
class ConversationWindowManagerSpillMarkerPreservationTest {

    private static final String SPILL_BODY = ToolResultStorage.SPILL_MARKER_PREFIX
            + " tool=web_search full_chars=22000 path=/tmp/x.txt\n"
            + "[Preview — first 800 of 22000 chars. Use read_file with the path above to retrieve the rest.]\n"
            + "preview body fragment that contributes most of the inline size...";

    @Test
    void softTrimLeavesSpillMarkerUntouched() {
        ConversationWindowManager mgr = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);

        List<Message> messages = new ArrayList<>(List.of(
                toolMessage("call-spill", "web_search", SPILL_BODY),
                toolMessage("call-big", "search", "x".repeat(2000))
        ));

        // Pass the same list through Phase 1.
        int trimmed = mgr.softTrimToolResults(messages);

        // The non-spill body must have been trimmed (it was > 500 chars).
        // The spill body must remain identical to the original.
        ToolResponseMessage trm0 = (ToolResponseMessage) messages.get(0);
        ToolResponseMessage trm1 = (ToolResponseMessage) messages.get(1);
        assertEquals(SPILL_BODY, trm0.getResponses().getFirst().responseData(),
                "Phase 1 soft trim must not modify a spill-marker body");
        assertTrue(trm1.getResponses().getFirst().responseData().contains("[trimmed "),
                "non-spill bodies should still be trimmed by Phase 1");
        assertEquals(1, trimmed,
                "trim counter should reflect only the non-spill body that was actually shortened");
    }

    @Test
    void hardClearLeavesSpillMarkerUntouched() {
        ConversationWindowManager mgr = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);

        List<Message> messages = new ArrayList<>(List.of(
                toolMessage("call-spill", "web_search", SPILL_BODY),
                toolMessage("call-big", "search", "y".repeat(2000))
        ));

        int cleared = mgr.hardClearToolResults(messages);

        ToolResponseMessage trm0 = (ToolResponseMessage) messages.get(0);
        ToolResponseMessage trm1 = (ToolResponseMessage) messages.get(1);
        assertEquals(SPILL_BODY, trm0.getResponses().getFirst().responseData(),
                "Phase 2 hard clear must not replace a spill-marker body with [tool result removed]");
        assertEquals("[tool result removed]", trm1.getResponses().getFirst().responseData(),
                "non-spill bodies should still be replaced by Phase 2");
        assertEquals(1, cleared,
                "clear counter should reflect only the non-spill body that was actually replaced");
    }

    @Test
    void prePruneForSummaryLeavesSpillMarkerUntouched() {
        ConversationWindowManager mgr = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);

        List<Message> messages = new ArrayList<>(List.of(
                toolMessage("call-spill", "web_search", SPILL_BODY),
                toolMessage("call-big", "search", "z".repeat(2000))
        ));

        int pruned = mgr.prePruneForSummary(messages);

        ToolResponseMessage trm0 = (ToolResponseMessage) messages.get(0);
        ToolResponseMessage trm1 = (ToolResponseMessage) messages.get(1);
        assertEquals(SPILL_BODY, trm0.getResponses().getFirst().responseData(),
                "Phase 3 pre-prune must not replace a spill-marker body with the cleared-output placeholder");
        assertTrue(trm1.getResponses().getFirst().responseData().contains("旧工具输出已清理"),
                "non-spill bodies should still be replaced by Phase 3");
        assertEquals(1, pruned);
    }

    @Test
    void mixedMessageWithSpillAndNonSpillResponsesPreservesOnlyTheMarker() {
        // A single ToolResponseMessage can hold multiple ToolResponses (one
        // assistant tool_calls turn could ask for several tools at once).
        // The phase guards must operate at the response level, not the
        // message level — the spill response stays, the non-spill response
        // gets the placeholder.
        ConversationWindowManager mgr = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);

        ToolResponseMessage mixed = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-spill", "web_search", SPILL_BODY),
                new ToolResponseMessage.ToolResponse("call-big", "search", "q".repeat(2000))
        )).build();
        List<Message> messages = new ArrayList<>(List.of(mixed));

        mgr.hardClearToolResults(messages);

        ToolResponseMessage trm = (ToolResponseMessage) messages.getFirst();
        assertEquals(SPILL_BODY, trm.getResponses().get(0).responseData(),
                "the spill response in a mixed message must survive Phase 2");
        assertEquals("[tool result removed]", trm.getResponses().get(1).responseData(),
                "the non-spill response in a mixed message must still be cleared");
    }

    @Test
    void smallSpillMarkerStillStaysVerbatim() {
        // Edge case: even when the preview is short (under the 500-char
        // soft-trim threshold), the marker check should still apply. This
        // protects against future changes to the trim threshold.
        ConversationWindowManager mgr = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);

        String tinySpill = ToolResultStorage.SPILL_MARKER_PREFIX
                + " tool=test full_chars=600 path=/tmp/t.txt\n[tiny]";
        List<Message> messages = new ArrayList<>(List.of(
                toolMessage("call-1", "test", tinySpill)
        ));

        mgr.softTrimToolResults(messages);
        mgr.hardClearToolResults(messages);
        mgr.prePruneForSummary(messages);

        ToolResponseMessage trm = (ToolResponseMessage) messages.getFirst();
        assertEquals(tinySpill, trm.getResponses().getFirst().responseData(),
                "the marker check is what protects the body — not the size of the preview");
    }

    // ------------------------------------------------------------------ helpers

    private static ToolResponseMessage toolMessage(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }
}
