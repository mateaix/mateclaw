package vip.mate.execution.evidence.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.exception.MateClawException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** Explicit, read-only check of captured bytes; never an acceptance binding. */
public final class JsonArtifactRecipe {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(1_048_576).maxNameLength(1024).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonArtifactRecipe() { }

    public record Result(String recipeId, int recipeRevision, String status, List<String> requiredFields,
                         List<String> missingFields, Instant checkedAt, boolean acceptanceEligible) { }

    public static List<String> validate(List<String> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 16
                || fields.stream().anyMatch(f -> f == null || f.isBlank() || f.length() > 128
                        || f.chars().anyMatch(Character::isISOControl))
                || new HashSet<>(fields).size() != fields.size()) {
            throw new MateClawException(400, "Specify 1–16 unique top-level JSON fields, each 1–128 characters");
        }
        return List.copyOf(fields);
    }

    public static Result outcome(String status, List<String> fields, List<String> missing) {
        return new Result("json-required-fields", 1, status, List.copyOf(fields), List.copyOf(missing),
                Instant.now(), false);
    }

    /** Shared strict parser for managed publication and diagnostic checks. */
    public static com.fasterxml.jackson.databind.JsonNode parseObject(byte[] bytes) {
        if (bytes == null || bytes.length > 1_048_576) throw new MateClawException(400, "JSON must be at most 1 MiB");
        try {
            var document = JSON.readTree(bytes);
            if (document == null || !document.isObject()) throw new IllegalArgumentException();
            return document;
        } catch (Exception invalid) {
            throw new MateClawException(400, "A strict JSON object is required");
        }
    }

    public static Result check(byte[] bytes, List<String> requestedFields) {
        List<String> fields = validate(requestedFields);
        if (bytes == null || bytes.length > 1_048_576) return outcome("UNKNOWN", fields, List.of());
        try {
            var document = parseObject(bytes);
            List<String> missing = fields.stream().filter(field -> !document.hasNonNull(field)).toList();
            return outcome(missing.isEmpty() ? "MATCH" : "MISSING_FIELDS", fields, missing);
        } catch (Exception invalid) {
            // Parser diagnostics can contain file content; do not return or log them.
            return outcome("INVALID_JSON", fields, List.of());
        }
    }
}
