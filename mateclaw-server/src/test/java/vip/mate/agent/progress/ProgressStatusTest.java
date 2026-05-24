package vip.mate.agent.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link ProgressStatus#parse} — the only entry the LLM controls.
 * The parser must tolerate the variants a model naturally produces (case,
 * hyphens, spaces) so a status like "In Progress" doesn't kick the tool
 * into a structured-error path purely over formatting.
 */
class ProgressStatusTest {

    @Test
    @DisplayName("Wire values round-trip through parse/wireValue.")
    void wireValuesRoundtrip() {
        for (ProgressStatus s : ProgressStatus.values()) {
            assertEquals(s, ProgressStatus.parse(s.wireValue()));
        }
    }

    @Test
    @DisplayName("Mixed case input parses to the same enum.")
    void caseInsensitive() {
        assertEquals(ProgressStatus.IN_PROGRESS, ProgressStatus.parse("In_Progress"));
        assertEquals(ProgressStatus.DONE, ProgressStatus.parse("DONE"));
        assertEquals(ProgressStatus.PENDING, ProgressStatus.parse("pending"));
    }

    @Test
    @DisplayName("Hyphen or space variants — \"in-progress\" / \"in progress\" — map to IN_PROGRESS.")
    void hyphensAndSpaces() {
        assertEquals(ProgressStatus.IN_PROGRESS, ProgressStatus.parse("in-progress"));
        assertEquals(ProgressStatus.IN_PROGRESS, ProgressStatus.parse("in progress"));
        assertEquals(ProgressStatus.IN_PROGRESS, ProgressStatus.parse("  In Progress  "));
    }

    @Test
    @DisplayName("Unknown or null inputs return null so the tool can return a structured error.")
    void unknownReturnsNull() {
        assertNull(ProgressStatus.parse(null));
        assertNull(ProgressStatus.parse(""));
        assertNull(ProgressStatus.parse("ready"));
        assertNull(ProgressStatus.parse("finished"));
    }
}
