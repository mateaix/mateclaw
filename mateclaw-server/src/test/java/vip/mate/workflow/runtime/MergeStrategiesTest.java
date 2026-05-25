package vip.mate.workflow.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-function tests for the four merge strategies. No Spring context;
 * each strategy gets an empty-file case, an existing-file case, and at
 * least one edge case (heading miss, kv miss, unknown strategy).
 */
class MergeStrategiesTest {

    @Test
    void appendOnEmptyFileWritesIncomingVerbatim() {
        assertEquals("hello", MergeStrategies.apply("", "hello", "append"));
    }

    @Test
    void appendInsertsBlankSeparator() {
        assertEquals("first\n\nsecond",
                MergeStrategies.apply("first", "second", "append"));
    }

    @Test
    void overwriteReplacesEverything() {
        assertEquals("new", MergeStrategies.apply("old\nlots\nof\nlines", "new", "overwrite"));
    }

    @Test
    void replaceSectionUpdatesMatchingSection() {
        String existing = "## Intro\nold intro\n\n## Tail\ntail body\n";
        String incoming = "## Intro\nfresh intro";
        String merged = MergeStrategies.apply(existing, incoming, "replace_section");
        assertTrue(merged.startsWith("## Intro\nfresh intro\n"),
                "expected fresh intro at top: " + merged);
        assertTrue(merged.contains("## Tail\ntail body"),
                "tail must be preserved: " + merged);
    }

    @Test
    void replaceSectionAppendsWhenHeadingMissing() {
        String existing = "## A\nbody A";
        String incoming = "## B\nbody B";
        String merged = MergeStrategies.apply(existing, incoming, "replace_section");
        assertTrue(merged.contains("## A\nbody A"));
        assertTrue(merged.contains("## B\nbody B"));
    }

    @Test
    void replaceSectionWithoutHeadingFallsBackToAppend() {
        String existing = "existing";
        String incoming = "no heading";
        assertEquals("existing\n\nno heading",
                MergeStrategies.apply(existing, incoming, "replace_section"));
    }

    @Test
    void upsertKvUpdatesExistingKeyAndAppendsNewKeys() {
        String existing = "name: alice\nrole: admin\n";
        String incoming = "role: owner\nteam: red";
        String merged = MergeStrategies.apply(existing, incoming, "upsert_kv");
        assertTrue(merged.contains("name: alice"));
        assertTrue(merged.contains("role: owner"));
        assertTrue(merged.contains("team: red"));
    }

    @Test
    void upsertKvNoOpWhenIncomingHasNoKvLines() {
        String existing = "name: alice";
        assertEquals("name: alice", MergeStrategies.apply(existing, "no kv here", "upsert_kv"));
    }

    @Test
    void unknownStrategyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> MergeStrategies.apply("", "x", "weird"));
    }
}
