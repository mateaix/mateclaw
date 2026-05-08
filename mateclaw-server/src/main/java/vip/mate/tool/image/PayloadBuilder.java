package vip.mate.tool.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuration-driven payload builder for image-generation providers.
 *
 * <p>Without this, every provider class collects an {@code if/else} chain
 * mapping (model id) → (request shape, sizing dialect, available knobs). Each
 * new model in a family forces another branch. With it, each provider holds a
 * static {@code Map<modelId, ImageModelSpec>}; the builder consults that spec
 * for sizing dialect, default parameters, and a {@code supports} whitelist of
 * payload keys. Values not in the whitelist are dropped at the end so the API
 * never sees keys it would reject.
 *
 * <p>Sizing dialect handling:
 * <ul>
 *   <li>{@link SizeStyle#LITERAL_DIMENSION} — output {@code "1024x1024"} or
 *       a separator-replaced form (e.g. DashScope wants {@code "1024*1024"} —
 *       the spec's sizeMap can carry the alternative).</li>
 *   <li>{@link SizeStyle#ASPECT_RATIO} — output {@code "1:1"} / {@code "16:9"}.</li>
 *   <li>{@link SizeStyle#PRESET_NAME} — output the model-native preset
 *       (e.g. {@code square_hd}). The spec's sizeMap drives the lookup keyed
 *       by orientation token (landscape / square / portrait).</li>
 * </ul>
 *
 * @author MateClaw Team
 */
public final class PayloadBuilder {

    private final ImageModelSpec spec;
    private final Map<String, Object> entries = new LinkedHashMap<>();

    private PayloadBuilder(ImageModelSpec spec) {
        this.spec = spec;
        if (spec.defaults() != null) {
            entries.putAll(spec.defaults());
        }
    }

    public static PayloadBuilder from(ImageModelSpec spec) {
        return new PayloadBuilder(spec);
    }

    public PayloadBuilder withPrompt(String prompt) {
        if (prompt != null) {
            entries.put("prompt", prompt);
        }
        return this;
    }

    public PayloadBuilder withCount(Integer count) {
        if (count != null && count > 0) {
            entries.put("n", Math.min(count, Math.max(1, spec.maxCount() == 0 ? count : spec.maxCount())));
        }
        return this;
    }

    /**
     * Translate the unified {@code size} / {@code aspectRatio} inputs to whichever
     * key/value pair this model expects. The spec's {@link SizeStyle} drives
     * which key is set; the spec's sizeMap (orientation → native value) drives
     * the value when the caller did not pass an exact match.
     */
    public PayloadBuilder withSize(String requestedSize, String requestedAspectRatio) {
        SizeStyle style = spec.sizeStyle();
        if (style == null) {
            return this;
        }
        Map<String, String> sizeMap = spec.sizeMap();
        switch (style) {
            case LITERAL_DIMENSION -> entries.put("size",
                    resolveLiteralDimension(requestedSize, requestedAspectRatio, sizeMap));
            case ASPECT_RATIO -> entries.put("aspect_ratio",
                    resolveAspectRatio(requestedAspectRatio, sizeMap));
            case PRESET_NAME -> entries.put("image_size",
                    resolvePreset(requestedAspectRatio, sizeMap));
        }
        return this;
    }

    public PayloadBuilder withSeed(Integer seed) {
        if (seed != null) {
            entries.put("seed", seed);
        }
        return this;
    }

    public PayloadBuilder put(String key, Object value) {
        if (value != null) {
            entries.put(key, value);
        }
        return this;
    }

    /**
     * Produce a Jackson {@link ObjectNode} containing only the keys this model's
     * {@code supports} whitelist allows. Empty whitelist means "passthrough".
     */
    public ObjectNode toJsonNode(ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        Set<String> supports = spec.supports();
        boolean filter = supports != null && !supports.isEmpty();
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            if (filter && !supports.contains(e.getKey())) {
                continue;
            }
            out.set(e.getKey(), mapper.valueToTree(e.getValue()));
        }
        return out;
    }

    /** Read-only view of accumulated entries (post defaults / pre supports filter). */
    public Map<String, Object> entries() {
        return Map.copyOf(entries);
    }

    // ==================== size resolution ====================

    private String resolveLiteralDimension(String requestedSize, String aspectRatio,
                                            Map<String, String> sizeMap) {
        if (requestedSize != null && !requestedSize.isBlank()) {
            // Allow the spec's sizeMap to translate (e.g. "1024x1024" -> "1024*1024").
            String mapped = sizeMap == null ? null : sizeMap.get(requestedSize);
            return mapped != null ? mapped : requestedSize;
        }
        String orientation = orientationOf(aspectRatio);
        if (sizeMap != null && sizeMap.containsKey(orientation)) {
            return sizeMap.get(orientation);
        }
        return "1024x1024";
    }

    private String resolveAspectRatio(String requested, Map<String, String> sizeMap) {
        if (requested != null && !requested.isBlank()) {
            String mapped = sizeMap == null ? null : sizeMap.get(requested);
            return mapped != null ? mapped : requested;
        }
        return "1:1";
    }

    private String resolvePreset(String aspectRatio, Map<String, String> sizeMap) {
        String orientation = orientationOf(aspectRatio);
        if (sizeMap != null && sizeMap.containsKey(orientation)) {
            return sizeMap.get(orientation);
        }
        return "square_hd";
    }

    private static String orientationOf(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return "square";
        }
        String[] parts = aspectRatio.split(":");
        if (parts.length != 2) {
            return "square";
        }
        try {
            double w = Double.parseDouble(parts[0].trim());
            double h = Double.parseDouble(parts[1].trim());
            if (w == h) return "square";
            return w > h ? "landscape" : "portrait";
        } catch (NumberFormatException e) {
            return "square";
        }
    }
}
