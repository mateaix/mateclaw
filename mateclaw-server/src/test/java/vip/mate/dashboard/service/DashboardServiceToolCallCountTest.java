package vip.mate.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tool calls are persisted inside an assistant message's {@code metadata} JSON,
 * never as standalone {@code role="tool"} rows. These tests pin the metadata
 * parsing that feeds the dashboard's {@code toolCalls} metric.
 */
class DashboardServiceToolCallCountTest {

    private final DashboardService service =
            new DashboardService(null, null, new ObjectMapper());

    @Test
    @DisplayName("Counts entries from metadata.toolCalls.")
    void countsFromToolCallsArray() {
        String metadata = "{\"toolCalls\":["
                + "{\"name\":\"search\",\"status\":\"completed\"},"
                + "{\"name\":\"load_skill\",\"status\":\"completed\"},"
                + "{\"name\":\"execute_shell_command\",\"status\":\"completed\"}"
                + "],\"currentPhase\":\"reasoning\",\"finishReason\":\"normal\"}";
        assertEquals(3, service.countToolCalls(metadata));
    }

    @Test
    @DisplayName("Unwraps H2's quoted JSON-string-literal form before counting.")
    void unwrapsH2QuotedLiteral() throws Exception {
        // H2's JSON column returns the value double-encoded: a quoted string
        // literal whose body is the escaped JSON object.
        String inner = "{\"toolCalls\":[{\"name\":\"a\"},{\"name\":\"b\"}]}";
        String h2Wrapped = new ObjectMapper().writeValueAsString(inner); // -> "\"{\\\"toolCalls\\\":...}\""
        assertEquals(2, service.countToolCalls(h2Wrapped));
    }

    @Test
    @DisplayName("Falls back to segments[type=tool_call] when toolCalls is absent.")
    void fallsBackToSegments() {
        String metadata = "{\"segments\":["
                + "{\"type\":\"text\"},"
                + "{\"type\":\"tool_call\",\"toolName\":\"a\"},"
                + "{\"type\":\"thinking\"},"
                + "{\"type\":\"tool_call\",\"toolName\":\"b\"}"
                + "]}";
        assertEquals(2, service.countToolCalls(metadata));
    }

    @Test
    @DisplayName("Prefers toolCalls over segments (no double counting).")
    void prefersToolCallsOverSegments() {
        String metadata = "{\"toolCalls\":[{\"name\":\"x\"}],"
                + "\"segments\":[{\"type\":\"tool_call\"},{\"type\":\"tool_call\"}]}";
        assertEquals(1, service.countToolCalls(metadata));
    }

    @Test
    @DisplayName("Returns 0 for null, blank, empty-object, or assistant text-only metadata.")
    void zeroForNoToolCalls() {
        assertEquals(0, service.countToolCalls(null));
        assertEquals(0, service.countToolCalls(""));
        assertEquals(0, service.countToolCalls("{}"));
        assertEquals(0, service.countToolCalls("{\"finishReason\":\"normal\"}"));
        assertEquals(0, service.countToolCalls("{\"toolCalls\":[]}"));
    }

    @Test
    @DisplayName("Malformed JSON degrades to 0 instead of throwing.")
    void malformedJsonIsZero() {
        assertEquals(0, service.countToolCalls("{not valid json"));
    }
}
