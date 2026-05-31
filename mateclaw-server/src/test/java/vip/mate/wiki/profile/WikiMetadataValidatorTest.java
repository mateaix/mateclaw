package vip.mate.wiki.profile;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WikiMetadataValidator} covering required/type/enum/date
 * rules, coercion, and undeclared-field handling.
 */
class WikiMetadataValidatorTest {

    private final WikiMetadataValidator validator = new WikiMetadataValidator();

    private WikiPageTypeDef episodeDef() {
        WikiPageTypeDef def = new WikiPageTypeDef();
        Map<String, WikiFieldSchema> schema = new LinkedHashMap<>();
        schema.put("event_type", field("string", true, null));
        schema.put("event_date", field("date", true, null));
        schema.put("significance", field("enum", false, List.of("low", "medium", "high")));
        schema.put("cited_count", field("number", false, null));
        def.setSchema(schema);
        return def;
    }

    private WikiFieldSchema field(String type, boolean required, List<String> values) {
        WikiFieldSchema f = new WikiFieldSchema();
        f.setType(type);
        f.setRequired(required);
        f.setValues(values);
        return f;
    }

    @Test
    void validMetadata_isOk() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("event_type", "liquidity_shock");
        raw.put("event_date", "2024-09-18");
        raw.put("significance", "high");
        raw.put("cited_count", 12);

        WikiMetadataValidator.ValidationResult r = validator.validate(episodeDef(), raw, false, "create");

        assertEquals(WikiMetadataValidator.OK, r.getStatus());
        assertTrue(r.getWarnings().isEmpty());
        assertEquals("liquidity_shock", r.getCleaned().get("event_type"));
    }

    @Test
    void requiredMissing_warnsButKeepsGoing() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("event_type", "x");
        // event_date missing
        WikiMetadataValidator.ValidationResult r = validator.validate(episodeDef(), raw, false, "create");

        assertEquals(WikiMetadataValidator.WARNING, r.getStatus());
        assertTrue(r.getWarnings().stream().anyMatch(w ->
                w.getField().equals("event_date") && w.getReason().contains("required")));
    }

    @Test
    void numberCoercedFromString() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("event_type", "x");
        raw.put("event_date", "2024-01-01");
        raw.put("cited_count", "42");
        WikiMetadataValidator.ValidationResult r = validator.validate(episodeDef(), raw, false, "create");

        assertEquals(42L, r.getCleaned().get("cited_count"));
        assertEquals(WikiMetadataValidator.OK, r.getStatus());
    }

    @Test
    void badDate_warns() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("event_type", "x");
        raw.put("event_date", "Sept 2024");
        WikiMetadataValidator.ValidationResult r = validator.validate(episodeDef(), raw, false, "create");

        assertTrue(r.getWarnings().stream().anyMatch(w ->
                w.getField().equals("event_date") && w.getReason().contains("ISO date")));
    }

    @Test
    void enumOutOfRange_warns() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("event_type", "x");
        raw.put("event_date", "2024-01-01");
        raw.put("significance", "catastrophic");
        WikiMetadataValidator.ValidationResult r = validator.validate(episodeDef(), raw, false, "create");

        assertTrue(r.getWarnings().stream().anyMatch(w ->
                w.getField().equals("significance") && w.getReason().contains("enum")));
    }

    @Test
    void undeclaredField_droppedWithWarning_whenNotAllowed() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("event_type", "x");
        raw.put("event_date", "2024-01-01");
        raw.put("rogue", "surprise");
        WikiMetadataValidator.ValidationResult r = validator.validate(episodeDef(), raw, false, "route");

        assertFalse(r.getCleaned().containsKey("rogue"));
        WikiMetadataValidator.FieldWarning w = r.getWarnings().stream()
                .filter(x -> x.getField().equals("rogue")).findFirst().orElseThrow();
        assertTrue(w.getReason().contains("dropped"));
        assertEquals("route", w.getSource());
        assertEquals("surprise", w.getRawValuePreview());
    }

    @Test
    void undeclaredField_keptWhenAllowed() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("event_type", "x");
        raw.put("event_date", "2024-01-01");
        raw.put("extra", "kept");
        WikiMetadataValidator.ValidationResult r = validator.validate(episodeDef(), raw, true, "create");

        assertEquals("kept", r.getCleaned().get("extra"));
    }

    @Test
    void nullDef_keepsAllFields() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("anything", "goes");
        WikiMetadataValidator.ValidationResult r = validator.validate(null, raw, false, "create");
        // No schema → additional fields dropped (allowAdditional=false) with warning
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.getField().equals("anything")));
    }
}
