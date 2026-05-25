package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-052 multi-turn leakage fix: verify that {@link BaseAgent#isDirectToolMessage}
 * correctly identifies persisted assistant messages produced by a returnDirect
 * tool path, so {@code toSpringMessage} replaces their content with a placeholder
 * before the next turn's prompt is built.
 *
 * <p>The DB row stays unchanged; only the in-memory {@code AssistantMessage}
 * handed to the model is scrubbed.
 */
class BaseAgentDirectToolHistoryScrubTest {

    @Test
    @DisplayName("metadata.directToolNames non-empty list => identified as direct-tool message")
    void directToolNamesNonEmpty_recognized() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setContent("EMPLOYEE-SECRET-DATA");
        msg.setMetadata("{\"segments\":[],\"directToolNames\":[\"query_employee_salary\"]}");

        assertTrue(BaseAgent.isDirectToolMessage(msg),
                "Assistant message with directToolNames must be flagged for scrubbing");
    }

    @Test
    @DisplayName("metadata.directToolNames empty list => NOT treated as direct-tool")
    void directToolNamesEmpty_notRecognized() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setContent("normal answer");
        msg.setMetadata("{\"directToolNames\":[]}");

        assertFalse(BaseAgent.isDirectToolMessage(msg),
                "Empty directToolNames means no direct tool fired — don't scrub");
    }

    @Test
    @DisplayName("metadata without directToolNames => not direct-tool")
    void noDirectToolNamesField_notRecognized() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setContent("regular tool-call answer");
        msg.setMetadata("{\"toolCalls\":[{\"name\":\"get_weather\"}]}");

        assertFalse(BaseAgent.isDirectToolMessage(msg));
    }

    @Test
    @DisplayName("null/empty metadata => not direct-tool")
    void nullOrEmptyMetadata_notRecognized() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setContent("hi");

        assertFalse(BaseAgent.isDirectToolMessage(msg), "null metadata");

        msg.setMetadata("");
        assertFalse(BaseAgent.isDirectToolMessage(msg), "empty metadata string");

        msg.setMetadata("{}");
        assertFalse(BaseAgent.isDirectToolMessage(msg), "empty JSON object");
    }

    @Test
    @DisplayName("null entity => safely returns false")
    void nullEntity_safe() {
        assertFalse(BaseAgent.isDirectToolMessage(null));
    }

    // ========== OpenClaw-inspired optimization: tool-name-aware placeholder ==========

    @Test
    @DisplayName("directToolNamesIn extracts the array contents")
    void extractToolNames_singleAndMultiple() {
        MessageEntity single = new MessageEntity();
        single.setRole("assistant");
        single.setMetadata("{\"directToolNames\":[\"query_employee_salary\"]}");
        assertEquals(List.of("query_employee_salary"),
                BaseAgent.directToolNamesIn(single));

        MessageEntity multi = new MessageEntity();
        multi.setRole("assistant");
        multi.setMetadata("{\"directToolNames\":[\"tool_a\",\"tool_b\",\"tool_c\"]}");
        assertEquals(List.of("tool_a", "tool_b", "tool_c"),
                BaseAgent.directToolNamesIn(multi));
    }

    @Test
    @DisplayName("directToolNamesIn returns empty list for non-direct messages")
    void extractToolNames_emptyForNonDirect() {
        MessageEntity msg = new MessageEntity();
        msg.setRole("assistant");
        msg.setMetadata("{\"toolCalls\":[{\"name\":\"get_weather\"}]}");
        assertTrue(BaseAgent.directToolNamesIn(msg).isEmpty());
    }

    @Test
    @DisplayName("History placeholder names the tool so the model retains conversational structure")
    void placeholder_singleTool_namesIt() {
        String placeholder = BaseAgent.directToolHistoryPlaceholder(
                List.of("query_employee_salary"));
        assertTrue(placeholder.contains("query_employee_salary"),
                "Single-tool placeholder must name the tool");
        assertTrue(placeholder.contains("withheld"),
                "Placeholder must signal the data is withheld");
        assertTrue(placeholder.contains("call the tool again"),
                "Placeholder must hint at the recovery path");
    }

    @Test
    @DisplayName("Multi-tool placeholder lists every tool")
    void placeholder_multipleTools_listAll() {
        String placeholder = BaseAgent.directToolHistoryPlaceholder(
                List.of("query_employee_salary", "read_medical_record"));
        assertTrue(placeholder.contains("query_employee_salary"));
        assertTrue(placeholder.contains("read_medical_record"));
    }

    @Test
    @DisplayName("Empty/null tool name list falls back to a generic placeholder")
    void placeholder_emptyList_genericFallback() {
        String empty = BaseAgent.directToolHistoryPlaceholder(List.of());
        String nullList = BaseAgent.directToolHistoryPlaceholder(null);
        assertEquals(empty, nullList,
                "Both null and empty must produce identical generic placeholders");
        assertTrue(empty.contains("withheld"));
    }

    @Test
    @DisplayName("Placeholder MUST NOT echo the original sensitive content")
    void placeholder_neverContainsTheSensitivePayload() {
        // Sanity: even if the metadata-extracted tool name happens to be
        // sensitive-sounding, the placeholder is bounded — it doesn't re-emit
        // the message content itself.
        String placeholder = BaseAgent.directToolHistoryPlaceholder(
                List.of("query_employee_salary"));
        assertFalse(placeholder.contains("12345"));
        assertFalse(placeholder.contains("SSN"));
        assertFalse(placeholder.contains("PWD"));
    }
}
