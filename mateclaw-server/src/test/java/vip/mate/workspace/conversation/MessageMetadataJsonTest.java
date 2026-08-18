package vip.mate.workspace.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the metadata normalization every reader of {@code mate_message.metadata}
 * depends on, and the two failure shapes it exists to prevent.
 */
class MessageMetadataJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLAIN =
            "{\"finishReason\":\"incomplete\",\"directToolNames\":[\"readFile\"],\"segments\":[]}";

    private static String asH2ReturnsIt(String json) throws Exception {
        return MAPPER.writeValueAsString(json);
    }

    @Test
    @DisplayName("a JSON string literal is unwrapped to the document it holds")
    void unwrapsStringLiteral() throws Exception {
        assertEquals(PLAIN, MessageMetadataJson.normalize(asH2ReturnsIt(PLAIN)));
    }

    @Test
    @DisplayName("plain JSON passes through untouched")
    void passesPlainJsonThrough() {
        assertEquals(PLAIN, MessageMetadataJson.normalize(PLAIN));
    }

    @Test
    @DisplayName("null, blank and undecodable values are handed back as-is")
    void leavesUnusableValuesAlone() {
        assertNull(MessageMetadataJson.normalize(null));
        assertEquals("", MessageMetadataJson.normalize(""));
        assertEquals("{not json", MessageMetadataJson.normalize("{not json"));
        // Opens like a string literal but cannot be decoded — the caller's own
        // error handling should see the original, not a silently mangled value.
        assertEquals("\"unterminated", MessageMetadataJson.normalize("\"unterminated"));
    }

    @Test
    @DisplayName("key-matching regexes miss the escaped form — the reason normalize exists")
    void escapedFormDefeatsRegexes() throws Exception {
        // Same patterns the finish-reason gate and the direct-tool-name reader use.
        Pattern finishReason = Pattern.compile("\"(?:finishReason|finish_reason)\"\\s*:\\s*\"([^\"]+)\"");
        Pattern directToolNames = Pattern.compile(
                "\"directToolNames\"\\s*:\\s*\\[(\\s*\"[^\"]*\"\\s*(?:,\\s*\"[^\"]*\"\\s*)*)\\]");
        String wrapped = asH2ReturnsIt(PLAIN);

        assertTrue(wrapped.contains("finishReason"),
                "the bare key still greps — a guard written that way keeps working");
        assertFalse(wrapped.contains("\"finishReason\""),
                "a quoted guard does NOT: escaping puts a backslash between the quote and the name, "
                        + "so such a guard exits early and the reader never even reaches its pattern");
        assertFalse(finishReason.matcher(wrapped).find(), "escaped form must not match");
        assertFalse(directToolNames.matcher(wrapped).find(), "escaped form must not match");

        String normalized = MessageMetadataJson.normalize(wrapped);
        assertTrue(finishReason.matcher(normalized).find());
        assertTrue(directToolNames.matcher(normalized).find());
    }

    @Test
    @DisplayName("normalized output is parseable as an object, not a text node")
    void normalizedOutputParsesAsObject() throws Exception {
        var node = MAPPER.readTree(MessageMetadataJson.normalize(asH2ReturnsIt(PLAIN)));
        assertTrue(node.isObject(), "a text node is how this failure looks when unnoticed");
        assertEquals("incomplete", node.path("finishReason").asText());
    }
}
