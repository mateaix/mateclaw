package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the tool-result executor pipeline and its associated config.
 *
 * <ul>
 *   <li>Virtual-thread tool executor is wired with named carrier threads.</li>
 *   <li>{@link ToolResultProperties} defaults stay aligned with the executor
 *       inline hard cap so spill and truncate share one semantic threshold.</li>
 *   <li>{@link ToolExecutionExecutor#spillRawOrTruncate} attempts spill on the
 *       raw body first and falls back to truncation only when spill cannot run.</li>
 * </ul>
 */
class LaneDExecutorAndConfigTest {

    // ============================================================
    // D-4: ToolExecutionExecutor uses virtual threads
    // ============================================================

    @Nested
    @DisplayName("D-4: ToolExecutionExecutor virtual thread pool")
    class VirtualThreadPoolTests {

        @Test
        @DisplayName("TOOL_EXECUTOR is a named virtual thread executor (not fixed thread pool)")
        void toolExecutorIsVirtualThreadBased() throws Exception {
            Field field = ToolExecutionExecutor.class.getDeclaredField("TOOL_EXECUTOR");
            field.setAccessible(true);
            ExecutorService executor = (ExecutorService) field.get(null);

            assertNotNull(executor, "TOOL_EXECUTOR should not be null");

            // Virtual thread executor class name contains "ThreadPerTaskExecutor"
            // when created via Executors.newThreadPerTaskExecutor(factory).
            String className = executor.getClass().getName();
            assertTrue(className.contains("ThreadPerTaskExecutor"),
                    "Expected ThreadPerTaskExecutor (named virtual threads), but got: " + className);
        }

        @Test
        @DisplayName("Virtual threads are named 'tool-executor-N' for log traceability")
        void virtualThreadsAreNamed() throws Exception {
            Field field = ToolExecutionExecutor.class.getDeclaredField("TOOL_EXECUTOR");
            field.setAccessible(true);
            ExecutorService executor = (ExecutorService) field.get(null);

            // Submit a task and capture the thread name
            var future = executor.submit(() -> Thread.currentThread().getName());
            String threadName = future.get();

            assertTrue(threadName.startsWith("tool-executor-"),
                    "Virtual thread should be named 'tool-executor-N', but got: " + threadName);
        }
    }

    // ============================================================
    // D-5: ToolResultProperties defaults
    // ============================================================

    @Nested
    @DisplayName("ToolResultProperties defaults")
    class ToolResultPropertiesDefaultsTests {

        @Test
        @DisplayName("perResultThresholdChars default aligns with executor hard cap (8000)")
        void perResultThresholdCharsDefault() {
            ToolResultProperties props = new ToolResultProperties();
            assertEquals(8000, props.getPerResultThresholdChars(),
                    "Default perResultThresholdChars should equal the executor's MAX_TOOL_RESULT_CHARS=8000");
        }

        @Test
        @DisplayName("perTurnBudgetChars default is 32000")
        void perTurnBudgetCharsDefault() {
            ToolResultProperties props = new ToolResultProperties();
            assertEquals(32000, props.getPerTurnBudgetChars(),
                    "Default perTurnBudgetChars should be 32000");
        }

        @Test
        @DisplayName("Other defaults remain unchanged")
        void otherDefaultsUnchanged() {
            ToolResultProperties props = new ToolResultProperties();
            assertTrue(props.isEnabled(), "enabled should default to true");
            assertEquals(800, props.getPreviewHeadChars(),
                    "previewHeadChars should still default to 800");
            assertEquals(2500, props.getExcludedToolInlineChars(),
                    "excludedToolInlineChars should default to 2500");
            assertEquals("", props.getStorageBaseDir(),
                    "storageBaseDir should still default to empty string");
        }

        @Test
        @DisplayName("retentionDays defaults to 0 so spill files outlive their conversation")
        void retentionDaysDefaultsToZero() {
            // The recoverability invariant: a summary or preview that cites
            // a spill path must keep working for the whole life of the
            // conversation. Time-based deletion is opt-in; operators with
            // disk pressure can raise this value explicitly.
            ToolResultProperties props = new ToolResultProperties();
            assertEquals(0, props.getRetentionDays(),
                    "retentionDays must default to 0 — time-based purge is opt-in to preserve recoverability");
            assertTrue(props.getCleanupCron() != null && !props.getCleanupCron().isBlank(),
                    "cleanupCron stays defined; it is a no-op while retentionDays=0");
        }

        @Test
        @DisplayName("Per-result threshold matches the executor inline hard cap so spill and truncate share one ladder")
        void thresholdMatchesExecutorHardCap() throws Exception {
            ToolResultProperties props = new ToolResultProperties();
            Field field = ToolExecutionExecutor.class.getDeclaredField("MAX_TOOL_RESULT_CHARS");
            field.setAccessible(true);
            int hardCap = (int) field.get(null);
            assertEquals(hardCap, props.getPerResultThresholdChars(),
                    "perResultThresholdChars must equal MAX_TOOL_RESULT_CHARS; misalignment would silently shorten "
                            + "bodies between the two values when spill is disabled.");
        }
    }

    // ============================================================
    // Raw-first spill ordering — the critical issue #110 fix
    // ============================================================

    @Nested
    @DisplayName("ToolExecutionExecutor.spillRawOrTruncate: raw body reaches disk before the inline cap")
    class SpillOrTruncateOrderingTests {

        @TempDir
        Path tempDir;

        private ToolResultStorage storage(int threshold, List<String> excluded) {
            ToolResultProperties props = new ToolResultProperties();
            props.setStorageBaseDir(tempDir.toString());
            props.setPerResultThresholdChars(threshold);
            props.setPreviewHeadChars(120);
            if (excluded != null) props.setExcludedTools(excluded);
            return new ToolResultStorage(props);
        }

        @Test
        @DisplayName("raw body > threshold → spill writes full original bytes to disk and returns preview")
        void rawOverThresholdSpillsFullContent() throws Exception {
            ToolResultStorage st = storage(1000, null);
            String raw = "0123456789\n".repeat(2000); // ~22000 chars, well over both threshold AND hard cap
            int rawLen = raw.length();

            String out = ToolExecutionExecutor.spillRawOrTruncate(
                    st, 8000, raw, "web_search", "call-1", "conv-x", tempDir.toString());

            assertTrue(out.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                    "should return a spill preview when raw exceeds threshold");
            assertTrue(out.contains("full_chars=" + rawLen),
                    "preview header must report the original size, proving the raw bytes were what we measured");

            // The file on disk should be the FULL raw body — not the 8000-char truncate.
            // Path is encoded inside the preview as "path=/abs/path".
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("path=(\\S+)").matcher(out);
            assertTrue(m.find(), "preview must include path=...");
            Path spillFile = Path.of(m.group(1));
            assertTrue(java.nio.file.Files.exists(spillFile), "spill file should have been created");
            String fileContent = java.nio.file.Files.readString(spillFile);
            assertEquals(rawLen, fileContent.length(),
                    "spill file must contain the full raw body, not a pre-truncated copy");
        }

        @Test
        @DisplayName("raw body > threshold but tool is on exclusion list → no spill, inline hard cap")
        void rawOverThresholdExcludedToolTruncatesOnly() {
            ToolResultStorage st = storage(1000, List.of("read_file"));
            String raw = "x".repeat(20000);

            String out = ToolExecutionExecutor.spillRawOrTruncate(
                    st, 8000, raw, "read_file", "call-1", "conv-x", tempDir.toString());

            assertFalse(out.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                    "excluded tool must not be spilled");
            assertTrue(out.length() <= 8000,
                    "excluded body still must fit the inline hard cap (was " + out.length() + ")");
        }

        @Test
        @DisplayName("raw body ≤ threshold → returned unchanged, no spill, no truncation marker added")
        void rawUnderThresholdInlineVerbatim() {
            ToolResultStorage st = storage(1000, null);
            String raw = "small body";

            String out = ToolExecutionExecutor.spillRawOrTruncate(
                    st, 8000, raw, "web_search", "call-1", "conv-x", tempDir.toString());

            assertEquals(raw, out, "small bodies should pass through untouched");
        }

        @Test
        @DisplayName("storage null → falls back to inline hard cap, never crashes")
        void nullStorageFallsBackToTruncate() {
            String raw = "x".repeat(20000);

            String out = ToolExecutionExecutor.spillRawOrTruncate(
                    null, 8000, raw, "web_search", "call-1", "conv-x", tempDir.toString());

            assertFalse(out.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX));
            assertTrue(out.length() <= 8000,
                    "with no storage, body must still fit the inline hard cap");
        }

        @Test
        @DisplayName("null result stays null (no NPE)")
        void nullResultStaysNull() {
            ToolResultStorage st = storage(1000, null);
            String out = ToolExecutionExecutor.spillRawOrTruncate(
                    st, 8000, null, "web_search", "call-1", "conv-x", tempDir.toString());
            assertNull(out);
        }

        @Test
        @DisplayName("blank conversationId is replaced with a safe 'unknown' bucket so spill still lands on disk")
        void blankConversationIdRoutesToUnknownBucket() {
            ToolResultStorage st = storage(1000, null);
            String raw = "y".repeat(20000);

            String out = ToolExecutionExecutor.spillRawOrTruncate(
                    st, 8000, raw, "web_search", "call-1", "", tempDir.toString());

            assertTrue(out.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX),
                    "blank conversationId must not stop spill — caller can be the legacy executePreApproved path");
            assertTrue(out.contains("unknown"), "spill path should land under the 'unknown' bucket");
        }
    }

    @Nested
    @DisplayName("Tool result aggregate budget")
    class ToolResultAggregateBudgetTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("excluded retrieval tools are compacted when aggregate budget is exceeded")
        void excludedToolResultsCompactWhenTurnBudgetIsExceeded() {
            ToolResultProperties props = new ToolResultProperties();
            props.setStorageBaseDir(tempDir.toString());
            props.setPerTurnBudgetChars(5000);
            props.setExcludedToolInlineChars(1200);
            props.setExcludedTools(List.of("read_file"));
            ToolResultStorage storage = new ToolResultStorage(props);

            String largeRead = "line\n".repeat(1600);
            List<ToolResponseMessage.ToolResponse> responses = List.of(
                    new ToolResponseMessage.ToolResponse("call-1", "read_file", largeRead),
                    new ToolResponseMessage.ToolResponse("call-2", "read_file", largeRead + "tail")
            );

            List<ToolResponseMessage.ToolResponse> compacted =
                    storage.enforceTurnBudget(responses, "conv-test", tempDir.toString());

            assertTrue(compacted.stream().mapToInt(r -> r.responseData().length()).sum() < 5000);
            assertTrue(compacted.stream().allMatch(r ->
                    r.responseData().contains("tool result compacted for model context")));
        }

        @Test
        @DisplayName("large eligible tool result is spilled before entering model context")
        void largeEligibleToolResultIsSpilled() {
            ToolResultProperties props = new ToolResultProperties();
            props.setStorageBaseDir(tempDir.toString());
            props.setPerResultThresholdChars(1000);
            props.setPreviewHeadChars(120);
            ToolResultStorage storage = new ToolResultStorage(props);

            String largeResult = "0123456789\n".repeat(500);
            String contextResult = storage.persistIfOversized(
                    largeResult, "web_search", "call-1", "conv-test", tempDir.toString());

            assertTrue(contextResult.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX));
            assertTrue(contextResult.length() < largeResult.length());
            assertTrue(contextResult.contains("full_chars=" + largeResult.length()));
        }
    }
}
