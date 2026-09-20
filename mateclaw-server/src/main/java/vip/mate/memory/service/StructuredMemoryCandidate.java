package vip.mate.memory.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A typed memory candidate with the durability evidence required before an
 * automatic extractor may write it to long-term storage.
 */
public record StructuredMemoryCandidate(
        String type,
        String key,
        String content,
        String scope,
        String stability,
        double confidence,
        int evidenceCount,
        LocalDate expiresAt,
        boolean explicitlyPersistent) {

    private static final Set<String> TYPES = Set.of("user", "feedback", "project", "reference");
    private static final Set<String> SCOPES = Set.of("turn", "session", "project", "user", "global");
    private static final Set<String> STABILITIES = Set.of("transient", "ongoing", "durable");
    private static final double MIN_CONFIDENCE = 0.70;

    /** Parse strict LLM output. Missing durability fields fail closed. */
    public static Optional<StructuredMemoryCandidate> fromJson(JsonNode node) {
        if (node == null || !node.isObject()) return Optional.empty();
        String type = text(node, "type").toLowerCase(Locale.ROOT);
        String key = text(node, "key");
        String content = text(node, "content");
        String scope = text(node, "scope").toLowerCase(Locale.ROOT);
        String stability = text(node, "stability").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type) || key.isBlank() || content.isBlank()
                || !SCOPES.contains(scope) || !STABILITIES.contains(stability)
                || !node.has("confidence") || !node.get("confidence").isNumber()
                || !node.has("evidence_count") || !node.get("evidence_count").canConvertToInt()
                || !node.has("expires_at")
                || !node.has("explicitly_persistent") || !node.get("explicitly_persistent").isBoolean()) {
            return Optional.empty();
        }
        double confidence = node.get("confidence").asDouble();
        int evidenceCount = node.get("evidence_count").asInt();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1 || evidenceCount < 1) {
            return Optional.empty();
        }
        LocalDate expiresAt = null;
        JsonNode expiryNode = node.get("expires_at");
        if (!expiryNode.isNull()) {
            if (!expiryNode.isTextual() || expiryNode.asText().isBlank()) return Optional.empty();
            try {
                expiresAt = LocalDate.parse(expiryNode.asText().trim());
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
        return Optional.of(new StructuredMemoryCandidate(type, key, content, scope, stability,
                confidence, evidenceCount, expiresAt, node.get("explicitly_persistent").asBoolean()));
    }

    /** Explicit tool writes still carry metadata and pass through one canonical format. */
    public static StructuredMemoryCandidate explicit(String type, String key, String content) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String scope = switch (normalizedType) {
            case "user", "feedback" -> "user";
            case "project", "reference" -> "project";
            default -> "global";
        };
        String stability = switch (normalizedType) {
            case "user", "feedback" -> "durable";
            default -> "ongoing";
        };
        return new StructuredMemoryCandidate(normalizedType, key == null ? "" : key.trim(),
                content == null ? "" : content.trim(), scope, stability, 1.0, 1, null, true);
    }

    public boolean isAdmissible(LocalDate today) {
        if (!TYPES.contains(type) || key.isBlank() || content.isBlank()
                || confidence < MIN_CONFIDENCE || evidenceCount < 1
                || expiresAt != null && expiresAt.isBefore(today)
                || "turn".equals(scope) || "session".equals(scope)
                || "transient".equals(stability)) {
            return false;
        }
        if ("user".equals(type) || "feedback".equals(type)) {
            return ("user".equals(scope) || "global".equals(scope))
                    && "durable".equals(stability)
                    && (explicitlyPersistent || evidenceCount >= 2);
        }
        return ("project".equals(scope) || "user".equals(scope) || "global".equals(scope))
                && ("ongoing".equals(stability) || "durable".equals(stability));
    }

    String metadataSuffix() {
        return " | Scope: " + scope
                + " | Stability: " + stability
                + " | Confidence: " + String.format(Locale.ROOT, "%.2f", confidence)
                + " | Evidence: " + evidenceCount
                + " | Expires: " + (expiresAt == null ? "never" : expiresAt)
                + " | Explicit: " + explicitlyPersistent;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }
}
