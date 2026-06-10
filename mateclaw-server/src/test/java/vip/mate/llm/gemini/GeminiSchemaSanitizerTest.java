package vip.mate.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GeminiSchemaSanitizer} — the JSON Schema → Gemini
 * {@code Schema} subset translation used when sending tool declarations.
 */
class GeminiSchemaSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("drops unsupported JSON Schema keywords")
    void dropsUnsupportedKeywords() {
        JsonNode schema = parse("""
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "city": {"type": "string", "description": "City name"}
                  },
                  "required": ["city"]
                }
                """);

        ObjectNode cleaned = GeminiSchemaSanitizer.sanitizeToolParameters(schema, mapper);

        assertFalse(cleaned.has("$schema"), "$schema must be stripped");
        assertFalse(cleaned.has("additionalProperties"), "additionalProperties must be stripped");
        assertEquals("object", cleaned.path("type").asText());
        assertTrue(cleaned.path("properties").has("city"));
        assertEquals("City name", cleaned.path("properties").path("city").path("description").asText());
        assertTrue(cleaned.path("required").isArray());
    }

    @Test
    @DisplayName("recurses into nested properties and array items")
    void recursesIntoNested() {
        JsonNode schema = parse("""
                {
                  "type": "object",
                  "properties": {
                    "tags": {
                      "type": "array",
                      "additionalProperties": true,
                      "items": {"type": "string", "$comment": "drop me"}
                    },
                    "nested": {
                      "type": "object",
                      "$ref": "#/defs/x",
                      "properties": {"inner": {"type": "number"}}
                    }
                  }
                }
                """);

        ObjectNode cleaned = GeminiSchemaSanitizer.sanitizeToolParameters(schema, mapper);

        JsonNode tags = cleaned.path("properties").path("tags");
        assertFalse(tags.has("additionalProperties"));
        assertEquals("string", tags.path("items").path("type").asText());
        assertFalse(tags.path("items").has("$comment"));

        JsonNode nested = cleaned.path("properties").path("nested");
        assertFalse(nested.has("$ref"));
        assertEquals("number", nested.path("properties").path("inner").path("type").asText());
    }

    @Test
    @DisplayName("drops non-string enum on a numeric type")
    void dropsNumericEnum() {
        JsonNode schema = parse("""
                {
                  "type": "object",
                  "properties": {
                    "duration": {"type": "integer", "enum": [60, 1440, 4320]},
                    "mode": {"type": "string", "enum": ["fast", "slow"]}
                  }
                }
                """);

        ObjectNode cleaned = GeminiSchemaSanitizer.sanitizeToolParameters(schema, mapper);

        assertFalse(cleaned.path("properties").path("duration").has("enum"),
                "integer enum with numeric literals must be dropped");
        assertTrue(cleaned.path("properties").path("mode").has("enum"),
                "string enum is valid for Gemini and must be kept");
    }

    @Test
    @DisplayName("null / empty input yields a minimal object schema")
    void emptyInputYieldsObjectSchema() {
        ObjectNode fromNull = GeminiSchemaSanitizer.sanitizeToolParameters(null, mapper);
        assertEquals("object", fromNull.path("type").asText());
        assertTrue(fromNull.has("properties"));

        ObjectNode fromEmpty = GeminiSchemaSanitizer.sanitizeToolParameters(parse("{}"), mapper);
        assertEquals("object", fromEmpty.path("type").asText());
    }
}
