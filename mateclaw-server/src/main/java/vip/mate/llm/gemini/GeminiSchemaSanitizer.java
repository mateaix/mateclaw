package vip.mate.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Translates OpenAI-flavored JSON Schema tool parameters into the restricted
 * {@code Schema} subset accepted by Gemini's {@code functionDeclarations.parameters}.
 *
 * <p>Tool schemas produced by Spring AI carry JSON Schema keywords that the
 * Gemini API rejects ({@code $schema}, {@code additionalProperties},
 * {@code $ref}, {@code definitions}, …). This sanitizer keeps only the
 * documented Gemini subset and recurses into {@code properties}, {@code items}
 * and {@code anyOf}.
 */
public final class GeminiSchemaSanitizer {

    /** Keywords Gemini's {@code Schema} object understands; everything else is dropped. */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "type", "format", "title", "description", "nullable", "enum",
            "maxItems", "minItems", "properties", "required", "minProperties",
            "maxProperties", "minLength", "maxLength", "pattern", "example",
            "anyOf", "propertyOrdering", "default", "items", "minimum", "maximum");

    private GeminiSchemaSanitizer() {}

    /**
     * Return a Gemini-compatible copy of a tool parameter schema. A null,
     * empty, or non-object input yields a minimal {@code {"type":"object"}}
     * schema so the function declaration always carries a valid parameters block.
     */
    public static ObjectNode sanitizeToolParameters(JsonNode parameters,
                                                    com.fasterxml.jackson.databind.ObjectMapper mapper) {
        JsonNode cleaned = sanitize(parameters, mapper);
        if (cleaned == null || !cleaned.isObject() || cleaned.isEmpty()) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("type", "object");
            fallback.set("properties", mapper.createObjectNode());
            return fallback;
        }
        return (ObjectNode) cleaned;
    }

    /** Recursively strip non-Gemini keywords from an arbitrary schema node. */
    static JsonNode sanitize(JsonNode schema, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (schema == null || !schema.isObject()) {
            return null;
        }
        ObjectNode cleaned = mapper.createObjectNode();
        schema.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (!ALLOWED_KEYS.contains(key)) {
                return;
            }
            switch (key) {
                case "properties" -> {
                    if (value.isObject()) {
                        ObjectNode props = mapper.createObjectNode();
                        value.fields().forEachRemaining(p -> {
                            JsonNode sub = sanitize(p.getValue(), mapper);
                            props.set(p.getKey(), sub != null ? sub : mapper.createObjectNode());
                        });
                        cleaned.set("properties", props);
                    }
                }
                case "items" -> {
                    JsonNode sub = sanitize(value, mapper);
                    cleaned.set("items", sub != null ? sub : mapper.createObjectNode());
                }
                case "anyOf" -> {
                    if (value.isArray()) {
                        ArrayNode arr = mapper.createArrayNode();
                        for (JsonNode item : value) {
                            JsonNode sub = sanitize(item, mapper);
                            if (sub != null) {
                                arr.add(sub);
                            }
                        }
                        cleaned.set("anyOf", arr);
                    }
                }
                default -> cleaned.set(key, value);
            }
        });

        // Gemini requires every enum entry to be a string. When the parent type
        // is integer/number/boolean and the enum carries non-string literals,
        // drop the enum — the type plus description still guides the model and
        // the tool handler validates the value anyway.
        JsonNode enumVal = cleaned.get("enum");
        JsonNode typeVal = cleaned.get("type");
        if (enumVal != null && enumVal.isArray() && typeVal != null
                && Set.of("integer", "number", "boolean").contains(typeVal.asText(""))) {
            boolean hasNonString = false;
            for (JsonNode item : enumVal) {
                if (!item.isTextual()) {
                    hasNonString = true;
                    break;
                }
            }
            if (hasNonString) {
                cleaned.remove("enum");
            }
        }
        return cleaned;
    }
}
