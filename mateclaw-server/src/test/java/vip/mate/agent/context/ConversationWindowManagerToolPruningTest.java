package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.config.ConversationWindowProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior of {@link ConversationWindowManager#pruneOldToolResultsForModelInput} —
 * the pre-pass that runs before every model request to keep old tool results
 * from inflating the prompt.
 *
 * <p>The current contract:
 * <ul>
 *   <li>The latest tool response is kept verbatim.</li>
 *   <li>Older bodies under the dedup threshold are kept verbatim.</li>
 *   <li>Older bodies that are byte-identical to a newer body are replaced
 *       with a short "duplicate omitted" placeholder.</li>
 *   <li>Older bodies above {@link ToolResultStorage}'s spill threshold are
 *       written to disk; the in-prompt body becomes a preview + path so the
 *       model can call {@code read_file} for the full content.</li>
 *   <li>Without storage wired, old bodies stay verbatim. The previous
 *       behaviour — rewriting them into a lossy single-line summary —
 *       destroyed too much context on long tasks and was removed.</li>
 * </ul>
 */
class ConversationWindowManagerToolPruningTest {

    @Test
    void withoutStorageOlderLargeBodiesStayVerbatim() {
        ConversationWindowManager manager = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);
        String oldLarge = "old-result\n".repeat(700);   // ~7700 chars
        String latestLarge = "latest-result\n".repeat(700);
        List<Message> messages = List.of(
                new UserMessage("read earlier file"),
                toolMessage("old-1", "read_file", oldLarge),
                new UserMessage("read latest file"),
                toolMessage("new-1", "read_file", latestLarge)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(messages);

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.get(1);
        ToolResponseMessage latestToolMessage = (ToolResponseMessage) pruned.get(3);
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        String latestData = latestToolMessage.getResponses().getFirst().responseData();

        // No storage → keep the old body untouched, do NOT collapse to a lossy summary.
        assertEquals(oldLarge, oldData,
                "without storage, older tool bodies must be preserved verbatim "
                        + "(the lossy single-line rewrite has been removed)");
        assertEquals(latestLarge, latestData);
    }

    @Test
    void olderDuplicateToolResultStillUsesDuplicatePlaceholder(@TempDir Path tempDir) {
        ConversationWindowManager manager = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);
        String repeated = "same-output\n".repeat(700);
        List<Message> messages = List.of(
                toolMessage("old-1", "read_file", repeated),
                toolMessage("new-1", "read_file", repeated)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(messages);

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.getFirst();
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        assertTrue(oldData.contains("duplicate tool output omitted"),
                "byte-identical duplicates older than the latest copy still get the dedup placeholder");
    }

    @Test
    void withStorageOlderLargeBodiesGetSpilledToDisk(@TempDir Path tempDir) throws Exception {
        ConversationWindowManager manager = newManagerWithStorage(tempDir, /*threshold*/ 2000);

        String oldLarge = "alpha\n".repeat(800);        // 4800 chars > threshold 2000
        String latestLarge = "beta\n".repeat(800);
        List<Message> messages = List.of(
                new UserMessage("turn 1"),
                toolMessage("old-1", "web_search", oldLarge),
                new UserMessage("turn 2"),
                toolMessage("new-1", "web_search", latestLarge)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(
                messages, "conv-A", tempDir.toString());

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.get(1);
        ToolResponseMessage latestToolMessage = (ToolResponseMessage) pruned.get(3);
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        String latestData = latestToolMessage.getResponses().getFirst().responseData();

        assertTrue(oldData.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                "older oversized body should be spilled and replaced with a SPILL_MARKER preview");
        assertNotEquals(oldLarge, oldData, "old data should be replaced");
        // Latest one is always kept full regardless of size.
        assertEquals(latestLarge, latestData);

        // Verify the spill file contains the FULL raw body, not a truncated version.
        Matcher m = Pattern.compile("path=(\\S+)").matcher(oldData);
        assertTrue(m.find(), "preview must report the spill path");
        Path spillFile = Path.of(m.group(1));
        assertTrue(Files.exists(spillFile));
        assertEquals(oldLarge, Files.readString(spillFile),
                "spill file must hold the full original body — the whole point of preserving "
                        + "raw output for read_file recovery");
    }

    @Test
    void withStorageOlderSmallBodiesStayVerbatim(@TempDir Path tempDir) {
        ConversationWindowManager manager = newManagerWithStorage(tempDir, /*threshold*/ 2000);

        String oldSmall = "small old body";              // far under threshold
        String latestLarge = "x".repeat(3000);
        List<Message> messages = List.of(
                toolMessage("old-1", "web_search", oldSmall),
                new UserMessage("turn"),
                toolMessage("new-1", "web_search", latestLarge)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(
                messages, "conv-B", tempDir.toString());

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.get(0);
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        assertEquals(oldSmall, oldData,
                "bodies under the spill threshold stay verbatim — small results carry no compression win");
    }

    @Test
    void alreadySpilledMarkerIsNotReSpilled(@TempDir Path tempDir) {
        ConversationWindowManager manager = newManagerWithStorage(tempDir, /*threshold*/ 1000);

        // Simulate a body that was spilled at tool-execution time: it already
        // starts with the spill marker. Prune must leave it alone instead of
        // trying to spill a spill preview (which would write the preview text
        // to a new file, ad infinitum).
        String alreadySpilled = ToolResultStorage.SPILL_MARKER_PREFIX
                + " tool=web_search full_chars=22000 path=/tmp/x.txt\n[Preview ...]\nbody preview ...";
        String latestLarge = "y".repeat(3000);
        List<Message> messages = List.of(
                toolMessage("old-1", "web_search", alreadySpilled),
                toolMessage("new-1", "web_search", latestLarge)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(
                messages, "conv-C", tempDir.toString());

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.get(0);
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        assertEquals(alreadySpilled, oldData,
                "previously-spilled previews must pass through untouched — no double-spill");
    }

    @Test
    void exemptToolStaysVerbatimEvenWhenOversized(@TempDir Path tempDir) {
        ConversationWindowManager manager = newManagerWithStorage(tempDir, /*threshold*/ 1000);

        String oldLarge = "z".repeat(5000);
        String latestLarge = "z".repeat(5000);
        List<Message> messages = List.of(
                toolMessage("old-1", "delegateToAgent", oldLarge),  // exempt tool
                toolMessage("new-1", "delegateToAgent", latestLarge)
        );

        List<Message> pruned = manager.pruneOldToolResultsForModelInput(
                messages, "conv-D", tempDir.toString());

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.get(0);
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        assertEquals(oldLarge, oldData,
                "sub-agent delegation results are irreplaceable — must never be rewritten");
        assertFalse(oldData.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                "exempt tools should also not be spilled (they're already cheap to keep)");
    }

    @Test
    void blankConversationIdDisablesSpill(@TempDir Path tempDir) {
        ConversationWindowManager manager = newManagerWithStorage(tempDir, /*threshold*/ 1000);

        String oldLarge = "q".repeat(5000);
        String latestLarge = "r".repeat(5000);
        List<Message> messages = List.of(
                toolMessage("old-1", "web_search", oldLarge),
                toolMessage("new-1", "web_search", latestLarge)
        );

        // Without a conversationId, spill cannot scope files safely → falls back to verbatim.
        List<Message> pruned = manager.pruneOldToolResultsForModelInput(
                messages, null, tempDir.toString());

        ToolResponseMessage oldToolMessage = (ToolResponseMessage) pruned.get(0);
        String oldData = oldToolMessage.getResponses().getFirst().responseData();
        assertEquals(oldLarge, oldData,
                "null conversationId must not trigger spill — caller cannot scope files correctly");
    }

    // ------------------------------------------------------------------ helpers

    private static ConversationWindowManager newManagerWithStorage(Path tempDir, int threshold) {
        ToolResultProperties props = new ToolResultProperties();
        props.setStorageBaseDir(tempDir.toString());
        props.setPerResultThresholdChars(threshold);
        props.setPreviewHeadChars(120);
        ToolResultStorage storage = new ToolResultStorage(props);

        ConversationWindowManager manager = new ConversationWindowManager(
                new ConversationWindowProperties(), null, null);
        manager.setToolResultStorage(storage);
        return manager;
    }

    private static ToolResponseMessage toolMessage(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }
}
