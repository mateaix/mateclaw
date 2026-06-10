package vip.mate.wiki.profile;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates a page's raw metadata against its pageType field schema.
 *
 * <p>Policy (non-blocking — ingest is never failed by metadata issues):
 * <ul>
 *   <li>Required field missing → {@code warning}, field omitted.</li>
 *   <li>Type mismatch → attempt light coercion; on failure keep the raw value
 *       and emit a warning.</li>
 *   <li>Undeclared field → dropped unless the profile allows additional
 *       fields; a dropped field always emits a warning.</li>
 *   <li>Enum value outside the allowed set / malformed date → warning, value
 *       kept as-is.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Service
public class WikiMetadataValidator {

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Validation status persisted to {@code metadata_validation_status}. */
    public static final String OK = "ok";
    public static final String WARNING = "warning";

    /** One validation warning. Serialized to {@code metadata_validation_json}. */
    @Data
    public static class FieldWarning {
        private final String field;
        private final String reason;
        private final String source;
        private final String rawValuePreview;
    }

    @Data
    public static class ValidationResult {
        private final Map<String, Object> cleaned;
        private final String status;
        private final List<FieldWarning> warnings;
    }

    /**
     * Validate {@code raw} metadata against {@code def}'s schema.
     *
     * @param def    the pageType definition (may be {@code null} → no schema)
     * @param raw    the raw metadata produced by the LLM (may be {@code null})
     * @param allowAdditional whether undeclared fields are kept
     * @param source stage label recorded on each warning (route/create/merge)
     */
    public ValidationResult validate(WikiPageTypeDef def, Map<String, Object> raw,
                                     boolean allowAdditional, String source) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        List<FieldWarning> warnings = new ArrayList<>();
        Map<String, Object> input = raw == null ? Map.of() : raw;
        Map<String, WikiFieldSchema> schema = (def == null || def.getSchema() == null)
                ? Map.of() : def.getSchema();

        // 1. Declared fields: validate / coerce in schema order.
        for (Map.Entry<String, WikiFieldSchema> entry : schema.entrySet()) {
            String field = entry.getKey();
            WikiFieldSchema fieldSchema = entry.getValue();
            boolean present = input.containsKey(field) && input.get(field) != null;
            if (!present) {
                if (fieldSchema.isRequired()) {
                    warnings.add(new FieldWarning(field, "required field missing", source, null));
                }
                continue;
            }
            Object value = input.get(field);
            Coerced coerced = coerce(fieldSchema, value);
            if (coerced.warning != null) {
                warnings.add(new FieldWarning(field, coerced.warning, source, preview(value)));
            }
            cleaned.put(field, coerced.value);
        }

        // 2. Undeclared fields: keep or drop.
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String field = entry.getKey();
            if (schema.containsKey(field)) {
                continue;
            }
            if (allowAdditional) {
                cleaned.put(field, entry.getValue());
            } else {
                warnings.add(new FieldWarning(field, "dropped: not declared in schema",
                        source, preview(entry.getValue())));
            }
        }

        String status = warnings.isEmpty() ? OK : WARNING;
        return new ValidationResult(cleaned, status, warnings);
    }

    private record Coerced(Object value, String warning) {}

    private Coerced coerce(WikiFieldSchema schema, Object value) {
        String type = schema.getType() == null ? "string" : schema.getType().trim().toLowerCase();
        return switch (type) {
            case "string" -> new Coerced(String.valueOf(value), null);
            case "number" -> coerceNumber(value);
            case "boolean" -> coerceBoolean(value);
            case "date" -> coerceDate(value);
            case "enum" -> coerceEnum(schema, value);
            case "string_array" -> coerceStringArray(value);
            default -> new Coerced(value, null);
        };
    }

    private Coerced coerceNumber(Object value) {
        if (value instanceof Number) {
            return new Coerced(value, null);
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.contains(".")) {
                return new Coerced(Double.parseDouble(s), null);
            }
            return new Coerced(Long.parseLong(s), null);
        } catch (NumberFormatException e) {
            return new Coerced(value, "expected number");
        }
    }

    private Coerced coerceBoolean(Object value) {
        if (value instanceof Boolean) {
            return new Coerced(value, null);
        }
        String s = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(s) || "false".equals(s)) {
            return new Coerced(Boolean.valueOf(s), null);
        }
        return new Coerced(value, "expected boolean");
    }

    private Coerced coerceDate(Object value) {
        String s = String.valueOf(value).trim();
        if (ISO_DATE.matcher(s).matches()) {
            return new Coerced(s, null);
        }
        return new Coerced(value, "expected ISO date YYYY-MM-DD");
    }

    private Coerced coerceEnum(WikiFieldSchema schema, Object value) {
        String s = String.valueOf(value);
        List<String> values = schema.getValues();
        if (values == null || values.contains(s)) {
            return new Coerced(s, null);
        }
        return new Coerced(s, "value not in allowed enum set");
    }

    private Coerced coerceStringArray(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
            return new Coerced(out, null);
        }
        // A single scalar is accepted as a one-element array.
        return new Coerced(List.of(String.valueOf(value)), null);
    }

    private static String preview(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
