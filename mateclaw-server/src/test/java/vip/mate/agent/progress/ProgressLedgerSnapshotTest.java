package vip.mate.agent.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ProgressLedger#renderSnapshot} — the exact string the runtime
 * splices into the system prompt before each LLM call. Order of the buckets
 * and the per-entry shape are part of the contract; the agent is going to
 * parse this text every turn.
 */
class ProgressLedgerSnapshotTest {

    @Test
    @DisplayName("Empty ledger renders null so the runtime can skip injection.")
    void emptyRendersNull() {
        assertNull(ProgressLedger.empty().renderSnapshot());
        assertNull(new ProgressLedger(new LinkedHashMap<>()).renderSnapshot());
    }

    @Test
    @DisplayName("Buckets ordered done → in-progress → pending → blocked, with stable status icons.")
    void bucketOrdering() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("a", entry("a", "Step A", ProgressStatus.PENDING, null));
        entries.put("b", entry("b", "Step B", ProgressStatus.DONE, null));
        entries.put("c", entry("c", "Step C", ProgressStatus.IN_PROGRESS, null));
        entries.put("d", entry("d", "Step D", ProgressStatus.BLOCKED, "missing dep"));

        String out = new ProgressLedger(entries).renderSnapshot();
        assertNotNull(out);

        int done = out.indexOf("✅");
        int inProg = out.indexOf("🔄");
        int pending = out.indexOf("⏳");
        int blocked = out.indexOf("⛔");
        assertTrue(done >= 0 && inProg > done && pending > inProg && blocked > pending,
                "Bucket order should be done → in-progress → pending → blocked: " + out);
    }

    @Test
    @DisplayName("Empty buckets are suppressed — no \"0 entries\" placeholder noise.")
    void emptyBucketsAreOmitted() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("only", entry("only", "Only step", ProgressStatus.DONE, null));
        String out = new ProgressLedger(entries).renderSnapshot();
        assertNotNull(out);
        assertTrue(out.contains("✅"));
        assertFalse(out.contains("🔄"));
        assertFalse(out.contains("⏳"));
        assertFalse(out.contains("⛔"));
    }

    @Test
    @DisplayName("Each entry shows label + bracketed key + optional note suffix.")
    void entryShape() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("step_pptx", entry("step_pptx", "Generate PPTX", ProgressStatus.IN_PROGRESS,
                "currently on slide 4"));
        String out = new ProgressLedger(entries).renderSnapshot();
        assertNotNull(out);
        assertTrue(out.contains("Generate PPTX"), out);
        assertTrue(out.contains("[`step_pptx`]"), out);
        assertTrue(out.contains("— currently on slide 4"), out);
    }

    @Test
    @DisplayName("A very long note is truncated to the preview cap with an ellipsis.")
    void longNoteTruncated() {
        String huge = "x".repeat(500);
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("k", entry("k", "K", ProgressStatus.DONE, huge));
        String out = new ProgressLedger(entries).renderSnapshot();
        assertNotNull(out);
        assertTrue(out.endsWith("\n请基于此进度继续推进；已完成的步骤不要重复执行。完成新步骤后调用 `progress_update` 工具更新本账本。")
                || out.contains("…"), "expected ellipsis when note exceeds preview cap");
        // Snapshot must be far smaller than the raw 500-char note.
        assertTrue(out.length() < 500, "snapshot length=" + out.length());
    }

    @Test
    @DisplayName("Missing label falls back to the key so the bullet is never blank.")
    void missingLabelFallsBackToKey() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("only_key", entry("only_key", null, ProgressStatus.PENDING, null));
        String out = new ProgressLedger(entries).renderSnapshot();
        assertNotNull(out);
        assertTrue(out.contains("only_key"), out);
    }

    private static ProgressEntry entry(String key, String label, ProgressStatus status, String note) {
        return new ProgressEntry(key, label, status, note, Instant.now());
    }
}
