package vip.mate.agent.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ProgressLedger#renderStaleReminder} — the heuristic that
 * decides whether to inject a "the model is forgetting its ledger" warning
 * into the next reasoning step. Triggers were calibrated against the
 * round-4 failure mode where the model called progress_update 3 times in
 * the first 30s and then never again across the remaining 27 minutes.
 */
class ProgressLedgerStaleReminderTest {

    private static final Instant NOW = Instant.parse("2026-05-24T19:30:00Z");

    @Test
    @DisplayName("Iteration < 10 → no reminder regardless of ledger state.")
    void warmupPeriodNoReminder() {
        assertNull(ProgressLedger.empty().renderStaleReminder(0, NOW));
        assertNull(ProgressLedger.empty().renderStaleReminder(5, NOW));
        assertNull(ProgressLedger.empty().renderStaleReminder(9, NOW));
    }

    @Test
    @DisplayName("Empty ledger between iter 10 and 14 → still no reminder.")
    void emptyLedgerBelowNudgeThreshold() {
        assertNull(ProgressLedger.empty().renderStaleReminder(10, NOW));
        assertNull(ProgressLedger.empty().renderStaleReminder(14, NOW));
    }

    @Test
    @DisplayName("Empty ledger at iter ≥ 15 → emit empty-ledger reminder.")
    void emptyLedgerTriggersReminder() {
        String out = ProgressLedger.empty().renderStaleReminder(15, NOW);
        assertNotNull(out);
        assertTrue(out.contains("进度账本是空的"), out);
        assertTrue(out.contains("15 轮"), out);
        assertTrue(out.contains("progress_update"), out);
    }

    @Test
    @DisplayName("Non-empty ledger with fresh update → no reminder.")
    void freshUpdateNoReminder() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("a", new ProgressEntry("a", "A", ProgressStatus.IN_PROGRESS, null,
                NOW.minusSeconds(30)));  // 30s ago — well within threshold
        assertNull(new ProgressLedger(entries).renderStaleReminder(20, NOW));
    }

    @Test
    @DisplayName("Non-empty ledger with last update ≥ 90s ago → emit stale reminder.")
    void staleUpdateTriggersReminder() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("a", new ProgressEntry("a", "A", ProgressStatus.DONE, null,
                NOW.minusSeconds(180)));   // 3 min ago
        entries.put("b", new ProgressEntry("b", "B", ProgressStatus.PENDING, null,
                NOW.minusSeconds(200)));
        String out = new ProgressLedger(entries).renderStaleReminder(40, NOW);
        assertNotNull(out);
        assertTrue(out.contains("180 秒"), "expected gap in reminder: " + out);
        assertTrue(out.contains("1 done"), "expected done count: " + out);
        assertTrue(out.contains("1 pending"), "expected pending count: " + out);
        assertTrue(out.contains("progress_update"), out);
    }

    @Test
    @DisplayName("Iteration < warm-up overrides stale-gap trigger.")
    void warmupBeatsStaleGap() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        entries.put("a", new ProgressEntry("a", "A", ProgressStatus.DONE, null,
                NOW.minusSeconds(600)));
        assertNull(new ProgressLedger(entries).renderStaleReminder(5, NOW));
    }

    @Test
    @DisplayName("mostRecentUpdate returns the latest updatedAt across entries.")
    void mostRecentUpdate() {
        Map<String, ProgressEntry> entries = new LinkedHashMap<>();
        Instant t1 = NOW.minusSeconds(300);
        Instant t2 = NOW.minusSeconds(100);
        Instant t3 = NOW.minusSeconds(200);
        entries.put("a", new ProgressEntry("a", "A", ProgressStatus.DONE, null, t1));
        entries.put("b", new ProgressEntry("b", "B", ProgressStatus.DONE, null, t2));
        entries.put("c", new ProgressEntry("c", "C", ProgressStatus.DONE, null, t3));
        assertTrue(new ProgressLedger(entries).mostRecentUpdate().orElseThrow().equals(t2));
    }
}
